package org.drools.lsp.server;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.drools.completion.DRLCompletionHelper;
import org.drools.drl.parser.antlr4.DRLParseResult;
import org.drools.drl.parser.antlr4.DRLParserHelper;
import org.drools.drl.parser.antlr4.DRLSymbol;
import org.drools.java.JavaIsland;
import org.drools.java.JavaIslandExtractor;
import org.drools.java.JdtJavaService;
import org.drools.java.MavenClasspathResolver;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.MarkupKind;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.TextDocumentService;

public class DroolsLspDocumentService implements TextDocumentService {

    /**
     * How long to wait after the last change before running validation. Coalesces a burst of
     * keystrokes into a single validation pass so typing does not flood the server with work.
     */
    private static final long DEBOUNCE_MS = 300;

    private final DroolsLspServer server;
    private final Map<String, DocumentState> documents = new ConcurrentHashMap<>();

    // Dedicated pools so that background validation can never starve interactive requests
    // (completion / hover / definition / references). Previously everything shared the common
    // ForkJoin pool, so a backlog of validation tasks delayed every completion request.
    private static final ScheduledExecutorService DIAGNOSTICS_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("drools-lsp-diagnostics"));
    private static final ExecutorService REQUEST_EXECUTOR =
            Executors.newCachedThreadPool(daemonThreadFactory("drools-lsp-request"));

    public DroolsLspDocumentService(DroolsLspServer server) {
        this.server = server;
    }

    // ------------------------------------------------------------------
    // Per-document state
    // ------------------------------------------------------------------

    /**
     * The immutable text and version of a document at a point in time.
     */
    private record Snapshot(String text, long version) {
    }

    /**
     * Per-document mutable state: the current snapshot, the cached parse for the current version, and
     * the pending (debounced) validation task.
     */
    private static final class DocumentState {
        final String uri;
        volatile Snapshot snapshot;
        volatile ParsedDrl parsed;
        volatile ScheduledFuture<?> pendingValidation;

        DocumentState(String uri) {
            this.uri = uri;
        }
    }

    /**
     * The cached parse and derived data for a document version, shared by all interactive LSP features
     * so that a single parse serves diagnostics, completion, hover, go-to-definition, and references.
     */
    private static final class ParsedDrl {
        final long version;
        final DRLParseResult parse;
        final List<JavaIsland> islands;
        final String imports;
        final List<int[]> functionBodyRanges;

        ParsedDrl(long version, String text) {
            this.version = version;
            this.parse = DRLParseResult.parse(text, version);
            this.islands = JavaIslandExtractor.extract(parse);
            this.imports = JavaIslandExtractor.extractImports(text);
            this.functionBodyRanges = islands.stream()
                    .filter(island -> island.kind() == JavaIsland.Kind.FUNCTION)
                    .map(island -> new int[]{island.startOffset() - 1, island.startOffset() + island.code().length() + 1})
                    .collect(Collectors.toList());
        }
    }

    // ------------------------------------------------------------------
    // LSP text document service
    // ------------------------------------------------------------------

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        DocumentState state = documents.computeIfAbsent(uri, DocumentState::new);
        state.snapshot = new Snapshot(params.getTextDocument().getText(), 0);
        scheduleValidation(state);
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        DocumentState state = documents.computeIfAbsent(uri, DocumentState::new);
        Snapshot current = state.snapshot;
        String baseText = current != null ? current.text() : "";
        String newText = applyChanges(baseText, params.getContentChanges());
        Integer clientVersion = params.getTextDocument().getVersion();
        long newVersion = clientVersion != null ? clientVersion.longValue() : (current != null ? current.version() + 1 : 0);
        state.snapshot = new Snapshot(newText, newVersion);
        scheduleValidation(state);
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        String uri = params.getTextDocument().getUri();
        DocumentState state = documents.remove(uri);
        if (state != null && state.pendingValidation != null) {
            state.pendingValidation.cancel(false);
        }
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        // Diagnostics are already published on change; nothing extra is required on save.
    }

    // ------------------------------------------------------------------
    // Validation (debounced and version-guarded)
    // ------------------------------------------------------------------

    private void scheduleValidation(DocumentState state) {
        if (state.pendingValidation != null) {
            state.pendingValidation.cancel(false);
        }
        state.pendingValidation = DIAGNOSTICS_SCHEDULER.schedule(() -> {
            try {
                Snapshot snap = state.snapshot;
                if (snap == null) {
                    return;
                }
                List<Diagnostic> diagnostics = validate(state.uri, snap.text());
                // Version guard: drop the result if the document changed again while validating.
                if (snap.version() == state.snapshot.version()) {
                    server.getClient().publishDiagnostics(new PublishDiagnosticsParams(state.uri, diagnostics));
                }
            } catch (Exception e) {
                // Never let a validation failure take down the scheduler thread.
            }
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    private List<Diagnostic> validate(String uri, String text) {
        DRLParseResult parse = DRLParseResult.parse(text, -1);
        List<JavaIsland> islands = JavaIslandExtractor.extract(parse);
        List<Diagnostic> diagnostics = new ArrayList<>();
        diagnostics.addAll(collectDrlDiagnostics(parse, islands));
        diagnostics.addAll(collectJavaDiagnostics(uri, parse, islands));
        return diagnostics;
    }

    private List<Diagnostic> collectDrlDiagnostics(DRLParseResult parse, List<JavaIsland> islands) {
        String text = parse.text();
        List<int[]> functionBodyRanges = islands.stream()
                .filter(island -> island.kind() == JavaIsland.Kind.FUNCTION)
                .map(island -> new int[]{island.startOffset() - 1, island.startOffset() + island.code().length() + 1})
                .collect(Collectors.toList());
        return parse.syntaxErrors().stream()
                .filter(error -> !inFunctionBody(text, functionBodyRanges, error.startLine(), error.startChar()))
                .map(error -> {
                    Diagnostic diagnostic = new Diagnostic();
                    diagnostic.setRange(new Range(
                            new Position(error.startLine(), error.startChar()),
                            new Position(error.endLine(), error.endChar())));
                    diagnostic.setMessage(error.message());
                    diagnostic.setSeverity(DiagnosticSeverity.Error);
                    return diagnostic;
                })
                .collect(Collectors.toList());
    }

    private List<Diagnostic> collectJavaDiagnostics(String uri, DRLParseResult parse, List<JavaIsland> islands) {
        try {
            return JdtJavaService.diagnostics(islands, JavaIslandExtractor.extractImports(parse.text()), resolveClasspath(uri)).stream()
                    .map(problem -> {
                        Diagnostic diagnostic = new Diagnostic();
                        diagnostic.setRange(new Range(
                                offsetToPosition(parse.text(), problem.startOffset()),
                                offsetToPosition(parse.text(), problem.endOffset())));
                        diagnostic.setMessage(problem.message());
                        diagnostic.setSeverity(DiagnosticSeverity.forValue(problem.severity()));
                        return diagnostic;
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<String> resolveClasspath(String uri) {
        try {
            URI parsed = URI.create(uri);
            if (!"file".equals(parsed.getScheme())) {
                return List.of();
            }
            return MavenClasspathResolver.resolve(Path.of(parsed));
        } catch (Exception e) {
            return List.of();
        }
    }

    // ------------------------------------------------------------------
    // Interactive features (run on the request pool, backed by the shared parse cache)
    // ------------------------------------------------------------------

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams completionParams) {
        return CompletableFuture.supplyAsync(() -> Either.forLeft(attempt(() -> getCompletionItems(completionParams))), REQUEST_EXECUTOR);
    }

    private <T> T attempt(Supplier<T> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            server.getClient().showMessage(new MessageParams(MessageType.Error, e.toString()));
        }
        return null;
    }

    public List<CompletionItem> getCompletionItems(CompletionParams completionParams) {
        DocumentState state = documents.get(completionParams.getTextDocument().getUri());
        if (state == null || state.snapshot == null) {
            return List.of();
        }
        ParsedDrl parsed = getOrParse(state);
        return DRLCompletionHelper.getCompletionItems(parsed.parse, completionParams.getPosition(), server.getClient());
    }

    public String getRuleName(CompletionParams completionParams) {
        DocumentState state = documents.get(completionParams.getTextDocument().getUri());
        if (state == null || state.snapshot == null) {
            return "";
        }
        ParsedDrl parsed = getOrParse(state);
        return DRLParserHelper.getFirstRuleName(parsed.parse.cu());
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        return CompletableFuture.supplyAsync(() -> {
            DocumentState state = documents.get(params.getTextDocument().getUri());
            if (state == null || state.snapshot == null) {
                return null;
            }
            ParsedDrl parsed = getOrParse(state);
            String tokenText = DRLParserHelper.getTokenTextAt(parsed.parse.tokens(), params.getPosition().getLine(), params.getPosition().getCharacter());
            if (tokenText == null) {
                return null;
            }
            return findSymbol(parsed, tokenText)
                    .map(symbol -> new Hover(new MarkupContent(MarkupKind.MARKDOWN, symbol.signature()), null))
                    .orElse(null);
        }, REQUEST_EXECUTOR);
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>> definition(DefinitionParams params) {
        return CompletableFuture.supplyAsync(() -> {
            DocumentState state = documents.get(params.getTextDocument().getUri());
            if (state == null || state.snapshot == null) {
                return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(List.of());
            }
            String uri = state.uri;
            ParsedDrl parsed = getOrParse(state);
            String text = parsed.parse.text();
            String tokenText = DRLParserHelper.getTokenTextAt(parsed.parse.tokens(), params.getPosition().getLine(), params.getPosition().getCharacter());
            if (tokenText == null) {
                return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(List.of());
            }
            Optional<DRLSymbol> symbol = findSymbol(parsed, tokenText);
            if (symbol.isPresent()) {
                DRLSymbol s = symbol.get();
                return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(List.of(
                        new Location(uri, new Range(new Position(s.startLine(), s.startChar()), new Position(s.endLine(), s.endChar())))));
            }
            Location localVar = localVariableDeclarationLocation(uri, parsed, tokenText, params.getPosition().getLine(), params.getPosition().getCharacter());
            if (localVar != null) {
                return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(List.of(localVar));
            }
            return Either.<List<? extends Location>, List<? extends LocationLink>>forLeft(List.of());
        }, REQUEST_EXECUTOR);
    }

    @Override
    public CompletableFuture<List<? extends Location>> references(ReferenceParams params) {
        return CompletableFuture.supplyAsync(() -> {
            DocumentState state = documents.get(params.getTextDocument().getUri());
            if (state == null || state.snapshot == null) {
                return List.<Location>of();
            }
            String uri = state.uri;
            ParsedDrl parsed = getOrParse(state);
            String text = parsed.parse.text();
            String tokenText = DRLParserHelper.getTokenTextAt(parsed.parse.tokens(), params.getPosition().getLine(), params.getPosition().getCharacter());
            if (tokenText == null) {
                return List.<Location>of();
            }
            int caretOffset = positionToOffset(text, params.getPosition().getLine(), params.getPosition().getCharacter());
            List<int[]> occurrences;
            if (findSymbol(parsed, tokenText).isPresent()) {
                occurrences = wordOccurrences(text, tokenText);
            } else {
                JavaIsland island = findIslandAt(parsed, caretOffset);
                if (island == null || findLocalVariableDeclaration(island.code(), tokenText) < 0) {
                    return List.<Location>of();
                }
                occurrences = wordOccurrences(island.code(), tokenText).stream()
                        .map(off -> new int[]{island.startOffset() + off[0], island.startOffset() + off[1]})
                        .collect(Collectors.toList());
            }
            return occurrences.stream()
                    .map(off -> toLocation(uri, text, off[0], off[1]))
                    .collect(Collectors.toList());
        }, REQUEST_EXECUTOR);
    }

    // ------------------------------------------------------------------
    // Parse cache
    // ------------------------------------------------------------------

    /**
     * Returns the cached parse for the document's current version, computing it on first use. Uses
     * double-checked locking so concurrent requests for the same version parse only once.
     */
    private ParsedDrl getOrParse(DocumentState state) {
        Snapshot snap = state.snapshot;
        ParsedDrl parsed = state.parsed;
        if (parsed == null || parsed.version != snap.version()) {
            synchronized (state) {
                parsed = state.parsed;
                if (parsed == null || parsed.version != snap.version()) {
                    parsed = new ParsedDrl(snap.version(), snap.text());
                    state.parsed = parsed;
                }
            }
        }
        return parsed;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Optional<DRLSymbol> findSymbol(ParsedDrl parsed, String name) {
        return parsed.parse.symbols().stream()
                .filter(symbol -> symbol.name().equals(name))
                .findFirst();
    }

    /**
     * Returns the location of a Java local variable's declaration within the enclosing island, or null
     * when the token is not a local variable declared in that island.
     */
    private Location localVariableDeclarationLocation(String uri, ParsedDrl parsed, String name, int line, int character) {
        String text = parsed.parse.text();
        int caretOffset = positionToOffset(text, line, character);
        JavaIsland island = findIslandAt(parsed, caretOffset);
        if (island == null) {
            return null;
        }
        int declInIsland = findLocalVariableDeclaration(island.code(), name);
        if (declInIsland < 0) {
            return null;
        }
        int start = island.startOffset() + declInIsland;
        return toLocation(uri, text, start, start + name.length());
    }

    private JavaIsland findIslandAt(ParsedDrl parsed, int caretOffset) {
        return parsed.islands.stream()
                .filter(island -> caretOffset >= island.startOffset() && caretOffset <= island.startOffset() + island.code().length())
                .findFirst()
                .orElse(null);
    }

    /**
     * Finds the offset (within the island code) of a local variable's declaration, or -1 when the name is
     * not declared as a local variable in the island. A declaration is the first whole-word occurrence of
     * the name whose preceding token is a type (a class name, a primitive, or a generics/array closer).
     */
    private int findLocalVariableDeclaration(String code, String name) {
        int from = 0;
        while (from <= code.length() - name.length()) {
            int idx = code.indexOf(name, from);
            if (idx < 0) {
                return -1;
            }
            char before = idx > 0 ? code.charAt(idx - 1) : '\0';
            int end = idx + name.length();
            char after = end < code.length() ? code.charAt(end) : '\0';
            if (!isIdentChar(before) && !isIdentChar(after) && isDeclarationContext(code, idx)) {
                return idx;
            }
            from = idx + 1;
        }
        return -1;
    }

    private boolean isDeclarationContext(String code, int nameStart) {
        int i = nameStart - 1;
        while (i >= 0 && Character.isWhitespace(code.charAt(i))) {
            i--;
        }
        if (i < 0) {
            return false;
        }
        char c = code.charAt(i);
        if (c == '>' || c == ']') {
            return true;
        }
        if (!isIdentChar(c)) {
            return false;
        }
        int tokenEnd = i + 1;
        while (i >= 0 && isIdentChar(code.charAt(i))) {
            i--;
        }
        return isTypeToken(code.substring(i + 1, tokenEnd));
    }

    private boolean isTypeToken(String token) {
        switch (token) {
            case "int":
            case "long":
            case "short":
            case "byte":
            case "char":
            case "float":
            case "double":
            case "boolean":
            case "void":
                return true;
            case "return":
            case "new":
            case "else":
            case "throw":
            case "case":
            case "do":
            case "yield":
            case "assert":
            case "this":
            case "super":
            case "instanceof":
            case "package":
            case "import":
                return false;
            default:
                return true;
        }
    }

    private boolean isIdentChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '$';
    }

    private List<int[]> wordOccurrences(String text, String name) {
        List<int[]> result = new ArrayList<>();
        int from = 0;
        while (from <= text.length() - name.length()) {
            int idx = text.indexOf(name, from);
            if (idx < 0) {
                break;
            }
            char before = idx > 0 ? text.charAt(idx - 1) : '\0';
            int end = idx + name.length();
            char after = end < text.length() ? text.charAt(end) : '\0';
            if (!isIdentChar(before) && !isIdentChar(after)) {
                result.add(new int[]{idx, end});
            }
            from = idx + 1;
        }
        return result;
    }

    private boolean inFunctionBody(String text, List<int[]> ranges, int line, int character) {
        int offset = positionToOffset(text, line, character);
        return ranges.stream().anyMatch(range -> offset >= range[0] && offset <= range[1]);
    }

    private int positionToOffset(String text, int line, int character) {
        int offset = 0;
        int currentLine = 0;
        while (currentLine < line && offset < text.length()) {
            if (text.charAt(offset) == '\n') {
                currentLine++;
            }
            offset++;
        }
        return Math.min(offset + character, text.length());
    }

    private Position offsetToPosition(String text, int offset) {
        int clamped = Math.max(0, Math.min(offset, text.length()));
        int line = 0;
        int lineStart = 0;
        for (int i = 0; i < clamped; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                lineStart = i + 1;
            }
        }
        return new Position(line, clamped - lineStart);
    }

    private Location toLocation(String uri, String text, int startOffset, int endOffset) {
        return new Location(uri, new Range(offsetToPosition(text, startOffset), offsetToPosition(text, endOffset)));
    }

    /**
     * Applies LSP content changes to the current text. A change without a range is a full-document
     * replacement; otherwise the change's range is replaced with its text, applied in order.
     */
    private String applyChanges(String currentText, List<TextDocumentContentChangeEvent> changes) {
        if (changes == null || changes.isEmpty()) {
            return currentText;
        }
        if (changes.get(0).getRange() == null) {
            return changes.get(0).getText();
        }
        String text = currentText;
        for (TextDocumentContentChangeEvent change : changes) {
            Range range = change.getRange();
            if (range == null) {
                text = change.getText();
                continue;
            }
            int start = positionToOffset(text, range.getStart().getLine(), range.getStart().getCharacter());
            int end = positionToOffset(text, range.getEnd().getLine(), range.getEnd().getCharacter());
            text = text.substring(0, start) + change.getText() + text.substring(end);
        }
        return text;
    }

    private static ThreadFactory daemonThreadFactory(String name) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
