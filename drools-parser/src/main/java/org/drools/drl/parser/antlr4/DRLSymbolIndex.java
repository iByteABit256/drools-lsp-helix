package org.drools.drl.parser.antlr4;

import java.util.ArrayList;
import java.util.List;

import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.Token;

/**
 * Extracts the named elements (rules, queries, functions, globals, fact types) from a DRL document,
 * with LSP-compatible (0-based) positions, for use by hover / go-to-definition / completion.
 */
public final class DRLSymbolIndex {

    private DRLSymbolIndex() {
    }

    public static List<DRLSymbol> extract(String drl) {
        DRL10Parser parser = DRLParserHelper.createDrlParser(drl);
        return extractFrom(parser.compilationUnit());
    }

    /**
     * Extracts the named elements from an already-parsed compilation unit, avoiding a re-parse.
     */
    static List<DRLSymbol> extractFrom(DRL10Parser.CompilationUnitContext cu) {
        List<DRLSymbol> symbols = new ArrayList<>();
        for (DRL10Parser.DrlStatementdefContext def : cu.drlStatementdef()) {
            if (def.ruledef() != null) {
                DRL10Parser.RuledefContext rule = def.ruledef();
                symbols.add(symbol(rule.name, rule.name.getText(), DRLSymbol.KIND_RULE, "rule " + rule.name.getText()));
            } else if (def.querydef() != null) {
                DRL10Parser.QuerydefContext query = def.querydef();
                symbols.add(symbol(query.name, query.name.getText(), DRLSymbol.KIND_QUERY, "query " + query.name.getText()));
            } else if (def.functiondef() != null) {
                DRL10Parser.FunctiondefContext fn = def.functiondef();
                DRL10Parser.DrlIdentifierContext nameCtx = fn.drlIdentifier();
                String name = nameCtx.getText();
                String returnType = fn.typeTypeOrVoid() != null ? fn.typeTypeOrVoid().getText() + " " : "";
                symbols.add(symbol(nameCtx, name, DRLSymbol.KIND_FUNCTION, "function " + returnType + name + fn.formalParameters().getText()));
            } else if (def.globaldef() != null) {
                DRL10Parser.GlobaldefContext g = def.globaldef();
                DRL10Parser.DrlIdentifierContext nameCtx = g.drlIdentifier();
                String name = nameCtx.getText();
                symbols.add(symbol(nameCtx, name, DRLSymbol.KIND_GLOBAL, "global " + g.type().getText() + " " + name));
            } else if (def.declaredef() != null && def.declaredef().typeDeclaration() != null) {
                DRL10Parser.TypeDeclarationContext td = def.declaredef().typeDeclaration();
                symbols.add(symbol(td.name, td.name.getText(), DRLSymbol.KIND_FACT_TYPE, "declare " + td.name.getText()));
            } else if (def.importdef() instanceof DRL10Parser.ImportStandardDefContext imp) {
                DRL10Parser.DrlQualifiedNameContext qn = imp.drlQualifiedName();
                String fullName = qn.getText();
                String simpleName = fullName.substring(fullName.lastIndexOf('.') + 1);
                Token start = qn.getStart();
                int startLine = start.getLine() - 1;
                int startChar = start.getCharPositionInLine();
                int endChar = startChar + fullName.length();
                symbols.add(new DRLSymbol(simpleName, DRLSymbol.KIND_FACT_TYPE, startLine, startChar, startLine, endChar, "import " + fullName));
            }
        }
        return symbols;
    }

    private static DRLSymbol symbol(ParserRuleContext ctx, String name, String kind, String signature) {
        Token start = ctx.getStart();
        int startLine = start.getLine() - 1;
        int startChar = start.getCharPositionInLine();
        int endChar = startChar + name.length();
        return new DRLSymbol(name, kind, startLine, startChar, startLine, endChar, signature);
    }
}
