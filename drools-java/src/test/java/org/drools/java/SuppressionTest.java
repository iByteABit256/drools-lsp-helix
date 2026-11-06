package org.drools.java;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SuppressionTest {

    @Test
    void missingTypeIsAlwaysReported() {
        String drl = "function f() {\n    Frobnicator x = null;\n}";
        List<JavaDiagnostic> diags = JdtJavaService.diagnostics(drl, List.of());
        assertThat(diags).anyMatch(d -> d.message().contains("Frobnicator cannot be resolved to a type"));
    }

    @Test
    void rhsUnresolvedVariableIsSuppressed() {
        String drl = """
                rule r
                  when
                    d: Foo()
                  then
                    String s = d.getPayload();
                end
                """;
        List<JavaDiagnostic> diags = JdtJavaService.diagnostics(drl, List.of());
        assertThat(diags).noneMatch(d -> d.message().contains("d cannot be resolved"));
    }

    @Test
    void functionBodyUnresolvedVariableIsReported() {
        String drl = "function f(int a) {\n    int y = zzz + a;\n}";
        List<JavaDiagnostic> diags = JdtJavaService.diagnostics(drl, List.of());
        assertThat(diags).anyMatch(d -> d.message().contains("zzz cannot be resolved"));
    }
}
