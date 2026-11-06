package org.drools.java;

/**
 * A Java diagnostic produced by compiling an embedded Java island, positioned in the original DRL
 * document. Framework-agnostic: the LSP server maps {@link #severity()} to the LSP severity enum.
 *
 * @param startOffset absolute 0-based character offset in the DRL text where the problem starts
 * @param endOffset   absolute 0-based character offset in the DRL text where the problem ends
 * @param severity    LSP numeric severity: 1 = Error, 2 = Warning, 3 = Information, 4 = Hint
 * @param message     human-readable problem message
 */
public record JavaDiagnostic(int startOffset, int endOffset, int severity, String message) {
}
