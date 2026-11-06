package org.drools.drl.parser.antlr4;

/**
 * A single DRL syntax error with LSP-compatible (0-based) positions.
 *
 * @param startLine 0-based line of the error start
 * @param startChar 0-based character offset of the error start
 * @param endLine   0-based line of the error end
 * @param endChar   0-based character offset of the error end
 * @param message   human-readable error message
 */
public record DRLSyntaxError(int startLine, int startChar, int endLine, int endChar, String message) {
}
