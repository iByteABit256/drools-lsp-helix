package org.drools.java;

import java.util.List;

/**
 * A region of Java code embedded in a DRL document (a rule RHS body or a function body).
 *
 * @param startOffset absolute 0-based character offset in the DRL text where the Java code begins
 * @param code        the extracted Java code
 * @param returnType  the return type of the synthetic method the code is wrapped in (the function's
 *                    declared return type for a function body, {@code void} for a rule RHS)
 * @param params      variables already in scope when the island executes: a function's formal
 *                    parameters for a function body, or the DRL {@code global} variables for a rule RHS
 */
public record JavaIsland(int startOffset, String code, String returnType, List<Param> params, Kind kind) {

    /**
     * The kind of island, which affects how unresolved references are interpreted. A rule RHS can
     * reference DRL LHS pattern variables and other dynamic context that a static Java compiler cannot
     * see, so unresolved names there are expected noise; a function body has a well-defined scope.
     */
    public enum Kind {
        RHS,
        FUNCTION
    }

    /**
     * A variable in scope for an island, declared as a synthetic method parameter so the Java compiler
     * can resolve references to it.
     *
     * @param type the declared type (e.g. {@code int}, {@code java.util.List})
     * @param name the variable name
     */
    public record Param(String type, String name) {
    }
}
