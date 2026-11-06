package org.drools.drl.parser.antlr4;

/**
 * A named element defined in a DRL document, with LSP-compatible (0-based) positions.
 *
 * @param name      the element name
 * @param kind      one of: rule, query, function, global, factType
 * @param startLine 0-based line of the name token start
 * @param startChar 0-based character offset of the name token start
 * @param endLine   0-based line of the name token end
 * @param endChar   0-based character offset of the name token end (exclusive)
 * @param signature a human-readable signature for hover
 */
public record DRLSymbol(String name, String kind, int startLine, int startChar, int endLine, int endChar, String signature) {

    public static final String KIND_RULE = "rule";
    public static final String KIND_QUERY = "query";
    public static final String KIND_FUNCTION = "function";
    public static final String KIND_GLOBAL = "global";
    public static final String KIND_FACT_TYPE = "factType";

    /**
     * Returns true if the given caret position (0-based) lies on this symbol's name token.
     */
    public boolean contains(int line, int charIndex) {
        if (line != startLine) {
            return false;
        }
        return charIndex >= startChar && charIndex <= endChar;
    }
}
