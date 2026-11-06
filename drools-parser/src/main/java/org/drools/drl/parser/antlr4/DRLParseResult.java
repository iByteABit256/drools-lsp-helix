package org.drools.drl.parser.antlr4;

import java.util.List;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;

/**
 * The result of a single parse of a DRL document, shared by every LSP feature that needs the parse
 * tree, token stream, syntax errors, or the extracted symbol index.
 *
 * <p>Parsing a DRL document is the dominant cost in the language server. The server therefore caches
 * one {@code DRLParseResult} per document version and reuses it across diagnostics, completion, hover,
 * go-to-definition, and references, instead of re-parsing the whole document for each request.
 */
public final class DRLParseResult {

    private final String text;
    private final long version;
    private final DRL10Parser parser;
    private final CommonTokenStream tokens;
    private final DRL10Parser.CompilationUnitContext cu;
    private final List<DRLSyntaxError> syntaxErrors;
    private final List<DRLSymbol> symbols;

    private DRLParseResult(String text, long version, DRL10Parser parser, CommonTokenStream tokens,
                           DRL10Parser.CompilationUnitContext cu, List<DRLSyntaxError> syntaxErrors,
                           List<DRLSymbol> symbols) {
        this.text = text;
        this.version = version;
        this.parser = parser;
        this.tokens = tokens;
        this.cu = cu;
        this.syntaxErrors = syntaxErrors;
        this.symbols = symbols;
    }

    /**
     * Parses the given DRL text once, capturing syntax errors and the symbol index in the same pass.
     */
    public static DRLParseResult parse(String text, long version) {
        DRLDiagnosticsHelper.SyntaxErrorListener listener = new DRLDiagnosticsHelper.SyntaxErrorListener();
        DRL10Lexer lexer = new DRL10Lexer(CharStreams.fromString(text));
        lexer.removeErrorListeners();
        lexer.addErrorListener(listener);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        DRL10Parser parser = new DRL10Parser(tokens);
        parser.removeErrorListeners();
        parser.addErrorListener(listener);
        DRL10Parser.CompilationUnitContext cu = parser.compilationUnit();
        List<DRLSymbol> symbols = DRLSymbolIndex.extractFrom(cu);
        return new DRLParseResult(text, version, parser, tokens, cu, listener.getErrors(), symbols);
    }

    public String text() {
        return text;
    }

    public long version() {
        return version;
    }

    public DRL10Parser parser() {
        return parser;
    }

    public CommonTokenStream tokens() {
        return tokens;
    }

    public DRL10Parser.CompilationUnitContext cu() {
        return cu;
    }

    public List<DRLSyntaxError> syntaxErrors() {
        return syntaxErrors;
    }

    public List<DRLSymbol> symbols() {
        return symbols;
    }
}
