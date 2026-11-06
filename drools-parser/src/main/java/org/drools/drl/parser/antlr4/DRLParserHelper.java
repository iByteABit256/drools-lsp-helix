package org.drools.drl.parser.antlr4;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.TokenStream;

/**
 * Collection of static helper methods for DRLParser
 */
public class DRLParserHelper {

    private DRLParserHelper() {
    }

    public static DRL10Parser createDrlParser(String drl) {
        CharStream charStream = CharStreams.fromString(drl);
        return createDrlParser(charStream);
    }

    public static DRL10Parser createDrlParser(InputStream is) {
        try {
            CharStream charStream = CharStreams.fromStream(is);
            return createDrlParser(charStream);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static DRL10Parser createDrlParser(CharStream charStream) {
        DRL10Lexer drlLexer = new DRL10Lexer(charStream);
        drlLexer.removeErrorListeners();
        CommonTokenStream commonTokenStream = new CommonTokenStream(drlLexer);
        DRL10Parser parser = new DRL10Parser(commonTokenStream);
        parser.removeErrorListeners();
        return parser;
    }

    /**
     * Given a row and column of a caret in the input DRL, return the index of the matched token
     */
    public static Integer computeTokenIndex(DRL10Parser parser, int row, int col) {
        for (int i = 0; i < parser.getInputStream().size(); i++) {
            Token token = parser.getInputStream().get(i);
            int start = token.getCharPositionInLine();
            int stop = token.getCharPositionInLine() + token.getText().length();
            if (token.getLine() > row) {
                // if token is on the next line, we can stop searching. Take the previous token
                return token.getTokenIndex() - 1;
            } else if (token.getLine() == row && start >= col) {
                // if token is on the same line and positioned to the right of the caret, we can stop searching. Take the previous token
                return token.getTokenIndex() == 0 ? 0 : token.getTokenIndex() - 1;
            } else if (token.getLine() == row && start < col && stop >= col) {
                // if token is on the same line and the caret is inside the token or right after the token, we can stop searching. Take the current token
                return token.getTokenIndex();
            }
        }
        return null;
    }

    public static String getFirstRuleName(String drl) {
        DRL10Parser parser = createDrlParser(drl);
        return getFirstRuleName(parser.compilationUnit());
    }

    public static String getFirstRuleName(DRL10Parser.CompilationUnitContext cu) {
        return cu.drlStatementdef()
                .stream()
                .filter(def -> def.ruledef() != null)
                .map(def -> def.ruledef().name.getText())
                .findFirst()
                .orElse("");
    }

    /**
     * Returns the text of the token under the given caret position (0-based line/col), or null if none.
     */
    public static String getTokenTextAt(String drl, int line, int col) {
        DRL10Parser parser = createDrlParser(drl);
        parser.compilationUnit();
        return getTokenTextAt(parser.getInputStream(), line, col);
    }

    /**
     * Returns the text of the token under the given caret position (0-based line/col) from an already
     * lexed token stream, or null if none.
     */
    public static String getTokenTextAt(TokenStream tokens, int line, int col) {
        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.getLine() == line + 1) {
                int start = token.getCharPositionInLine();
                int stop = start + token.getText().length();
                if (col >= start && col <= stop) {
                    return token.getText();
                }
            }
        }
        return null;
    }
}
