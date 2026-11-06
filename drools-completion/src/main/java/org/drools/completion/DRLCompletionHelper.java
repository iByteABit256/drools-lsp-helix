/*
 * Copyright 2022 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.drools.completion;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.vmware.antlr4c3.CodeCompletionCore;
import org.antlr.v4.runtime.Token;
import org.drools.drl.parser.antlr4.DRL10Lexer;
import org.drools.drl.parser.antlr4.DRL10Parser;
import org.drools.drl.parser.antlr4.DRLParseResult;
import org.drools.drl.parser.antlr4.DRLSymbol;
import org.drools.drl.parser.antlr4.DRLSymbolIndex;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.services.LanguageClient;

import static org.drools.drl.parser.antlr4.DRLParserHelper.computeTokenIndex;
import static org.drools.drl.parser.antlr4.DRLParserHelper.createDrlParser;

public class DRLCompletionHelper {

    // PREFERRED_RULES is used to filter out the rules that consist of unwanted tokens
    // additionally, it can be used to customize getCompletionItems behavior
    private static final Set<Integer> PREFERRED_RULES = Set.of(
            DRL10Parser.RULE_drlIdentifier,
            DRL10Parser.RULE_drlQualifiedName,
            DRL10Parser.RULE_stringId,
            DRL10Parser.RULE_consequenceBody
    );

    // Keywords that introduce a name being declared; semantic symbol suggestions are not wanted at those positions.
    private static final Set<String> NAME_INTRODUCING_KEYWORDS = Set.of(
            "package", "import", "rule", "query", "function", "global", "declare"
    );

    private DRLCompletionHelper() {
    }

    public static List<CompletionItem> getCompletionItems(String text, Position caretPosition, LanguageClient client) {
        DRL10Parser drlParser = createDrlParser(text);

        int row = caretPosition == null ? -1 : caretPosition.getLine() + 1; // caret line position is zero based
        int col = caretPosition == null ? -1 : caretPosition.getCharacter();

        drlParser.compilationUnit();
        Integer nodeIndex = computeTokenIndex(drlParser, row, col);

        return getCompletionItems(drlParser, nodeIndex, text);
    }

    /**
     * Computes completion items from an already-parsed DRL document, reusing the cached parse tree,
     * token stream, and symbol index instead of re-parsing.
     */
    public static List<CompletionItem> getCompletionItems(DRLParseResult parse, Position caretPosition, LanguageClient client) {
        DRL10Parser drlParser = parse.parser();

        int row = caretPosition == null ? -1 : caretPosition.getLine() + 1; // caret line position is zero based
        int col = caretPosition == null ? -1 : caretPosition.getCharacter();

        Integer nodeIndex = computeTokenIndex(drlParser, row, col);

        return getCompletionItems(drlParser, nodeIndex, parse.text(), parse.symbols());
    }

    static List<CompletionItem> getCompletionItems(DRL10Parser drlParser, int nodeIndex, String text) {
        return getCompletionItems(drlParser, nodeIndex, text, DRLSymbolIndex.extract(text));
    }

    static List<CompletionItem> getCompletionItems(DRL10Parser drlParser, int nodeIndex, String text, List<DRLSymbol> symbols) {
        CodeCompletionCore core = new CodeCompletionCore(drlParser, PREFERRED_RULES, Tokens.IGNORED);
        CodeCompletionCore.CandidatesCollection candidates = core.collectCandidates(nodeIndex, null);

        if (candidates.rules.containsKey(DRL10Parser.RULE_consequenceBody)) {
            // in RHS consequence, parser cannot suggest DRL_RHS_END because of island mode approach, so we add it manually
            candidates.tokens.put(DRL10Lexer.DRL_RHS_END, List.of());
        }

        List<CompletionItem> items = candidates.tokens.keySet().stream().filter(Objects::nonNull)
                .map(integer -> drlParser.getVocabulary().getDisplayName(integer).replace("'", ""))
                .map(String::toLowerCase)
                .map(k -> createCompletionItem(k, CompletionItemKind.Keyword))
                .collect(Collectors.toList());

        // Add user-defined symbols (fact types, functions, queries, globals) when an identifier is expected
        // and the caret is not at a name-declaration position (e.g. right after 'rule' / 'package' / ...).
        if (expectsIdentifier(candidates.rules) && !isNameDeclaration(drlParser, nodeIndex)) {
            items.addAll(semanticCompletionItems(symbols));
        }

        return dedupe(items);
    }

    private static boolean expectsIdentifier(Map<Integer, ?> rules) {
        return rules.containsKey(DRL10Parser.RULE_drlIdentifier)
                || rules.containsKey(DRL10Parser.RULE_drlQualifiedName);
    }

    private static boolean isNameDeclaration(DRL10Parser drlParser, int nodeIndex) {
        String previous = previousSignificantTokenText(drlParser, nodeIndex);
        return previous != null && NAME_INTRODUCING_KEYWORDS.contains(previous.toLowerCase());
    }

    private static String previousSignificantTokenText(DRL10Parser drlParser, int nodeIndex) {
        for (int i = nodeIndex; i >= 0; i--) {
            Token token = drlParser.getInputStream().get(i);
            if (token.getChannel() == Token.DEFAULT_CHANNEL) {
                return token.getText();
            }
        }
        return null;
    }

    private static List<CompletionItem> semanticCompletionItems(List<DRLSymbol> symbols) {
        return symbols.stream()
                .filter(symbol -> !DRLSymbol.KIND_RULE.equals(symbol.kind()))
                .map(DRLCompletionHelper::createSemanticCompletionItem)
                .collect(Collectors.toList());
    }

    private static CompletionItem createSemanticCompletionItem(DRLSymbol symbol) {
        CompletionItem item = new CompletionItem();
        item.setLabel(symbol.name());
        item.setInsertText(symbol.name());
        item.setKind(kindFor(symbol.kind()));
        item.setDetail(symbol.signature());
        return item;
    }

    private static CompletionItemKind kindFor(String kind) {
        return switch (kind) {
            case DRLSymbol.KIND_FACT_TYPE -> CompletionItemKind.Class;
            case DRLSymbol.KIND_FUNCTION -> CompletionItemKind.Function;
            case DRLSymbol.KIND_QUERY -> CompletionItemKind.Function;
            case DRLSymbol.KIND_GLOBAL -> CompletionItemKind.Variable;
            default -> CompletionItemKind.Text;
        };
    }

    private static List<CompletionItem> dedupe(List<CompletionItem> items) {
        Set<String> seen = new LinkedHashSet<>();
        List<CompletionItem> result = new ArrayList<>();
        for (CompletionItem item : items) {
            if (seen.add(item.getLabel())) {
                result.add(item);
            }
        }
        return result;
    }

    static CompletionItem createCompletionItem(String label, CompletionItemKind itemKind) {
        CompletionItem completionItem;
        completionItem = new CompletionItem();
        if (label.startsWith("drl_rhs_")) {
            // when Lexer uses "DRL_RHS_" keywords in multiple modes with type(),
            // drlParser.getVocabulary().getDisplayName() returns the keyword name as-is (symbolicNames), so remove the prefix.
            label = label.substring("drl_rhs_".length());
        }
        completionItem.setInsertText(label);
        completionItem.setLabel(label);
        completionItem.setKind(itemKind);
        return completionItem;
    }
}
