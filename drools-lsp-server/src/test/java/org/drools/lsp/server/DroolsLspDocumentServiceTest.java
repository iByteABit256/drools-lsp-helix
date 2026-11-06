package org.drools.lsp.server;

import java.util.List;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.ReferenceParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.drools.lsp.server.TestHelperMethods.getDroolsLspDocumentService;
import static org.drools.lsp.server.TestHelperMethods.getPublishedDiagnostics;

class DroolsLspDocumentServiceTest {

    @Test
    void getCompletionItems() {
        DroolsLspDocumentService droolsLspDocumentService = getDroolsLspDocumentService("");

        CompletionParams completionParams = new CompletionParams();
        completionParams.setTextDocument(new TextDocumentIdentifier("myDocument"));
        Position caretPosition = new Position();
        caretPosition.setCharacter(0);
        caretPosition.setLine(0);
        completionParams.setPosition(caretPosition);

        List<CompletionItem> result = droolsLspDocumentService.getCompletionItems(completionParams);
        assertThat(result.stream().map(CompletionItem::getInsertText).anyMatch("package"::equals)).isTrue();
    }

    @Test
    void getRuleName() {
        String drl = "rule MyRule when Dog(name == \"Bart\") then end";

        DroolsLspDocumentService droolsLspDocumentService = getDroolsLspDocumentService(drl);

        CompletionParams completionParams = new CompletionParams();
        completionParams.setTextDocument(new TextDocumentIdentifier("myDocument"));

        String ruleName = droolsLspDocumentService.getRuleName(completionParams);
        assertThat(ruleName).isEqualTo("MyRule");
    }

    @Test
    void getCompletionItems_findLHSandRHS() {
        String drl =
                "package org.test;\n" +
                        "import org.test.model.Person;\n" +
                        "rule TestRule when\n" +
                        "  $p:Person() \n" +
                        "then\n" +
                        "  System.out.println($p.getName()); \n" +
                        "end";

        DroolsLspDocumentService droolsLspDocumentService = getDroolsLspDocumentService(drl);

        CompletionParams completionParams = new CompletionParams();
        completionParams.setTextDocument(new TextDocumentIdentifier("myDocument"));

        completionParams.setPosition(new Position(1, 0));
        List<CompletionItem> result = droolsLspDocumentService.getCompletionItems(completionParams);
        assertThat(hasItem(result, "import")).isTrue();
        assertThat(hasItem(result, "rule")).isTrue();

        completionParams.setPosition(new Position(3, 14));
        result = droolsLspDocumentService.getCompletionItems(completionParams);
        assertThat(hasItem(result, "then")).isTrue();  // LHS

        completionParams.setPosition(new Position(5, 36));
        result = droolsLspDocumentService.getCompletionItems(completionParams);
        assertThat(hasItem(result, "end")).isTrue(); // RHS
    }

    @Test
    void validate_publishesDiagnosticsForInvalidDrl() {
        String drl = """
                package org.test;
                rule TestRule
                  when
                    $p:Person( age >= 18 )
                  then
                    System.out.println($p.getName());
                """;

        List<Diagnostic> diagnostics = getPublishedDiagnostics(drl);
        assertThat(diagnostics).isNotEmpty();
        assertThat(diagnostics).allSatisfy(d -> assertThat(d.getMessage()).isNotBlank());
    }

    @Test
    void validate_publishesNoDiagnosticsForValidDrl() {
        String drl = """
                package org.test;
                import org.test.model.Person;
                rule TestRule
                  when
                    $p:Person( age >= 18 )
                  then
                    System.out.println($p.getName());
                end
                """;

        List<Diagnostic> diagnostics = getPublishedDiagnostics(drl);
        assertThat(diagnostics).isEmpty();
    }

    @Test
    void validate_reportsRealJavaTypeErrorInRhs() {
        String drl = """
                package org.test;
                rule TestRule
                  when
                    $p:Person( age >= 18 )
                  then
                    int x = "not an int";
                end
                """;

        List<Diagnostic> diagnostics = getPublishedDiagnostics(drl);
        assertThat(diagnostics).anyMatch(d -> d.getMessage().contains("cannot convert from String to int"));
    }

    @Test
    void validate_noDrlSyntaxErrorsForModernJavaInFunctionBody() {
        String drl = """
                package org.test;
                function void test() {
                    java.util.List<String> list = new java.util.ArrayList<>();
                    list.forEach(item -> System.out.println(item));
                }
                """;

        List<Diagnostic> diagnostics = getPublishedDiagnostics(drl);
        assertThat(diagnostics).isEmpty();
    }

    @Test
    void hover_onRuleName_returnsSignature() throws Exception {
        String drl = """
                package org.test;
                rule TestRule
                  when
                    $p:Person( age >= 18 )
                  then
                    System.out.println($p);
                end
                """;
        DroolsLspDocumentService service = getDroolsLspDocumentService(drl);

        HoverParams params = new HoverParams();
        params.setTextDocument(new TextDocumentIdentifier("myDocument"));
        params.setPosition(new Position(1, 6)); // inside 'TestRule'

        Hover hover = service.hover(params).get();
        assertThat(hover).isNotNull();
        assertThat(hover.getContents().getRight().getValue()).contains("TestRule");
    }

    @Test
    void hover_onLhsFactType_returnsImportSignature() throws Exception {
        String drl = """
                package org.test;
                import org.test.model.Person;
                rule R
                  when
                    Person( age >= 18 )
                  then
                end
                """;
        DroolsLspDocumentService service = getDroolsLspDocumentService(drl);

        HoverParams params = new HoverParams();
        params.setTextDocument(new TextDocumentIdentifier("myDocument"));
        params.setPosition(new Position(4, 6)); // inside 'Person' on line 4

        Hover hover = service.hover(params).get();
        assertThat(hover).isNotNull();
        assertThat(hover.getContents().getRight().getValue()).contains("org.test.model.Person");
    }

    @Test
    void definition_onFunctionName_returnsLocation() throws Exception {
        String drl = """
                package org.test;
                function int add(int a, int b) {
                  return a + b;
                }
                rule R
                  when
                  then
                end
                """;
        DroolsLspDocumentService service = getDroolsLspDocumentService(drl);

        DefinitionParams params = new DefinitionParams();
        params.setTextDocument(new TextDocumentIdentifier("myDocument"));
        params.setPosition(new Position(1, 14)); // inside 'add' on line 1

        Either<List<? extends Location>, List<? extends LocationLink>> result = service.definition(params).get();
        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft()).hasSize(1);
        assertThat(result.getLeft().get(0).getRange().getStart().getLine()).isEqualTo(1);
        assertThat(result.getLeft().get(0).getRange().getStart().getCharacter()).isEqualTo(13);
    }

    @Test
    void definition_onLocalVariableInFunctionBody_returnsDeclaration() throws Exception {
        String drl = """
                package org.test;
                function int addOne(int x) {
                    int result = x + 1;
                    return result;
                }
                """;
        DroolsLspDocumentService service = getDroolsLspDocumentService(drl);

        DefinitionParams params = new DefinitionParams();
        params.setTextDocument(new TextDocumentIdentifier("myDocument"));
        params.setPosition(new Position(3, 13)); // inside the 'result' usage on line 3

        Either<List<? extends Location>, List<? extends LocationLink>> result = service.definition(params).get();
        assertThat(result.isLeft()).isTrue();
        assertThat(result.getLeft()).hasSize(1);
        assertThat(result.getLeft().get(0).getRange().getStart().getLine()).isEqualTo(2);
        assertThat(result.getLeft().get(0).getRange().getStart().getCharacter()).isEqualTo(8);
    }

    @Test
    void references_onDrlFunction_returnsAllOccurrences() throws Exception {
        String drl = """
                package org.test;
                function int add(int a, int b) {
                  return a + b;
                }
                rule R
                  when
                  then
                    int x = add(1, 2);
                end
                """;
        DroolsLspDocumentService service = getDroolsLspDocumentService(drl);

        ReferenceParams params = new ReferenceParams();
        params.setTextDocument(new TextDocumentIdentifier("myDocument"));
        params.setPosition(new Position(7, 13)); // inside the 'add' call on line 7

        List<? extends Location> result = service.references(params).get();
        assertThat(result).hasSize(2);
    }

    @Test
    void references_onLocalVariable_returnsIslandOccurrences() throws Exception {
        String drl = """
                package org.test;
                function void process() {
                    int count = 0;
                    count = count + 1;
                    System.out.println(count);
                }
                """;
        DroolsLspDocumentService service = getDroolsLspDocumentService(drl);

        ReferenceParams params = new ReferenceParams();
        params.setTextDocument(new TextDocumentIdentifier("myDocument"));
        params.setPosition(new Position(3, 5)); // inside the first 'count' on line 3

        List<? extends Location> result = service.references(params).get();
        assertThat(result).hasSize(4);
    }

    private boolean hasItem(List<CompletionItem> result, String text) {
        return result.stream().map(CompletionItem::getInsertText).anyMatch(text::equals);
    }
}