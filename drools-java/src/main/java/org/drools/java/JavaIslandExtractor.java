package org.drools.java;

import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.Token;
import org.drools.drl.parser.antlr4.DRL10Parser;
import org.drools.drl.parser.antlr4.DRLParseResult;
import org.drools.drl.parser.antlr4.DRLParserHelper;

/**
 * Extracts the embedded Java regions from a DRL document: rule RHS bodies (between {@code then}
 * and {@code end}) and function bodies (between {@code {}} and {@code }}).
 *
 * <p>For each island the variables already in scope are captured so a Java compiler can resolve
 * references to them: a function body carries the function's formal parameters, and a rule RHS
 * carries the DRL {@code global} variables.
 */
public final class JavaIslandExtractor {

    private JavaIslandExtractor() {
    }

    /**
     * Collects the DRL {@code import} statements (which are valid Java imports) so they can be
     * prepended to a synthetic compilation unit, letting the compiler resolve the types the islands
     * reference.
     */
    public static String extractImports(String drl) {
        StringBuilder imports = new StringBuilder();
        for (String line : drl.split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("import ")) {
                if (!trimmed.endsWith(";")) {
                    trimmed = trimmed + ";";
                }
                imports.append(trimmed).append('\n');
            }
        }
        return imports.toString();
    }

    public static List<JavaIsland> extract(String drl) {
        DRL10Parser parser = DRLParserHelper.createDrlParser(drl);
        return extract(parser.compilationUnit(), drl);
    }

    /**
     * Extracts the Java islands from an already-parsed DRL document, avoiding a re-parse.
     */
    public static List<JavaIsland> extract(DRLParseResult result) {
        return extract(result.cu(), result.text());
    }

    static List<JavaIsland> extract(DRL10Parser.CompilationUnitContext cu, String drl) {
        List<JavaIsland.Param> globals = extractGlobals(cu);
        List<JavaIsland> islands = new ArrayList<>();
        for (DRL10Parser.DrlStatementdefContext def : cu.drlStatementdef()) {
            if (def.ruledef() != null) {
                DRL10Parser.RuledefContext rule = def.ruledef();
                DRL10Parser.RhsContext rhs = rule.rhs();
                if (rhs != null && rhs.DRL_THEN() != null && rule.DRL_RHS_END() != null) {
                    Token then = rhs.DRL_THEN().getSymbol();
                    Token end = rule.DRL_RHS_END().getSymbol();
                    addIsland(islands, drl, then.getStopIndex() + 1, end.getStartIndex(), "void", globals, JavaIsland.Kind.RHS);
                }
            } else if (def.functiondef() != null) {
                DRL10Parser.FunctiondefContext fn = def.functiondef();
                DRL10Parser.DrlBlockContext block = fn.drlBlock();
                if (block != null && block.LBRACE() != null && block.RBRACE() != null) {
                    Token lbrace = block.LBRACE().getSymbol();
                    Token rbrace = block.RBRACE().getSymbol();
                    addIsland(islands, drl, lbrace.getStopIndex() + 1, rbrace.getStartIndex(), returnType(fn), extractParams(fn), JavaIsland.Kind.FUNCTION);
                }
            }
        }
        return islands;
    }

    private static void addIsland(List<JavaIsland> islands, String drl, int startOffset, int endOffset, String returnType, List<JavaIsland.Param> params, JavaIsland.Kind kind) {
        if (endOffset < startOffset) {
            return;
        }
        String code = drl.substring(startOffset, endOffset);
        if (!code.isBlank()) {
            islands.add(new JavaIsland(startOffset, code, returnType, params, kind));
        }
    }

    private static String returnType(DRL10Parser.FunctiondefContext fn) {
        DRL10Parser.TypeTypeOrVoidContext rt = fn.typeTypeOrVoid();
        return rt != null ? rt.getText() : "void";
    }

    private static List<JavaIsland.Param> extractGlobals(DRL10Parser.CompilationUnitContext cu) {
        List<JavaIsland.Param> globals = new ArrayList<>();
        for (DRL10Parser.DrlStatementdefContext def : cu.drlStatementdef()) {
            DRL10Parser.GlobaldefContext g = def.globaldef();
            if (g != null && g.type() != null && g.drlIdentifier() != null) {
                globals.add(new JavaIsland.Param(g.type().getText(), g.drlIdentifier().getText()));
            }
        }
        return globals;
    }

    private static List<JavaIsland.Param> extractParams(DRL10Parser.FunctiondefContext fn) {
        List<JavaIsland.Param> params = new ArrayList<>();
        DRL10Parser.FormalParametersContext fp = fn.formalParameters();
        if (fp == null || fp.formalParameterList() == null) {
            return params;
        }
        DRL10Parser.FormalParameterListContext list = fp.formalParameterList();
        for (DRL10Parser.FormalParameterContext p : list.formalParameter()) {
            if (p.typeType() != null && p.variableDeclaratorId() != null) {
                params.add(new JavaIsland.Param(p.typeType().getText(), p.variableDeclaratorId().getText()));
            }
        }
        DRL10Parser.LastFormalParameterContext last = list.lastFormalParameter();
        if (last != null && last.typeType() != null && last.variableDeclaratorId() != null) {
            params.add(new JavaIsland.Param(last.typeType().getText() + "...", last.variableDeclaratorId().getText()));
        }
        return params;
    }
}
