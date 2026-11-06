package org.drools.drl.parser.antlr4;

import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;

/**
 * Collects DRL syntax errors by running the ANTLR lexer and parser with an error listener.
 * The returned positions are 0-based, matching the LSP protocol.
 */
public final class DRLDiagnosticsHelper {

    private DRLDiagnosticsHelper() {
    }

    public static List<DRLSyntaxError> collectSyntaxErrors(String drl) {
        SyntaxErrorListener listener = new SyntaxErrorListener();
        CharStream charStream = CharStreams.fromString(drl);
        DRL10Lexer lexer = new DRL10Lexer(charStream);
        lexer.removeErrorListeners();
        lexer.addErrorListener(listener);
        CommonTokenStream tokenStream = new CommonTokenStream(lexer);
        DRL10Parser parser = new DRL10Parser(tokenStream);
        parser.removeErrorListeners();
        parser.addErrorListener(listener);
        parser.compilationUnit();
        return listener.getErrors();
    }

    static final class SyntaxErrorListener extends BaseErrorListener {

        private final List<DRLSyntaxError> errors = new ArrayList<>();

        @Override
        public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line, int charPositionInLine, String msg, RecognitionException e) {
            int startLine = Math.max(0, line - 1); // ANTLR lines are 1-based; LSP is 0-based
            int startChar = Math.max(0, charPositionInLine);
            int endChar = startChar;
            if (offendingSymbol instanceof Token token) {
                String text = token.getText();
                if (text != null && !text.isEmpty() && !"<EOF>".equals(text)) {
                    endChar = startChar + text.length();
                }
            }
            if (endChar <= startChar) {
                endChar = startChar + 1;
            }
            errors.add(new DRLSyntaxError(startLine, startChar, startLine, endChar, msg));
        }

        List<DRLSyntaxError> getErrors() {
            return errors;
        }
    }
}
