package org.drools.java;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdtJavaServiceTest {

    private static final List<String> NO_CLASSPATH = List.of();

    @Test
    void cleanFunctionBodyProducesNoErrors() {
        String drl = """
                function int doubleIt(int x) {
                    return x * 2;
                }
                """;

        List<JavaDiagnostic> diagnostics = JdtJavaService.diagnostics(drl, NO_CLASSPATH);

        assertThat(diagnostics).noneMatch(d -> d.severity() == 1);
    }

    @Test
    void typeMismatchInFunctionBodyIsReportedAtCorrectLocation() {
        String drl = """
                function int broken(int x) {
                    return x + "oops";
                }
                """;

        List<JavaDiagnostic> diagnostics = JdtJavaService.diagnostics(drl, NO_CLASSPATH);

        List<JavaDiagnostic> errors = diagnostics.stream().filter(d -> d.severity() == 1).toList();
        assertThat(errors).isNotEmpty();
        JavaDiagnostic error = errors.get(0);
        assertThat(error.startOffset()).isGreaterThanOrEqualTo(drl.indexOf("return"));
        assertThat(error.startOffset()).isLessThan(drl.indexOf("}"));
        assertThat(error.message()).containsIgnoringCase("mismatch");
    }

    @Test
    void dollarVariableInRhsIsSuppressed() {
        String drl = """
                rule R
                  when
                    $dog : Dog(name == "Bart")
                  then
                    System.out.println($dog);
                end
                """;

        List<JavaDiagnostic> diagnostics = JdtJavaService.diagnostics(drl, NO_CLASSPATH);

        assertThat(diagnostics).noneMatch(d -> d.severity() == 1);
        assertThat(diagnostics).noneMatch(d -> d.message().contains("$dog"));
    }

    @Test
    void rhsGlobalResolves() {
        String drl = """
                global java.util.List list;

                rule R
                  when
                    $dog : Dog(name == "Bart")
                  then
                    list.clear();
                end
                """;

        List<JavaDiagnostic> diagnostics = JdtJavaService.diagnostics(drl, NO_CLASSPATH);

        assertThat(diagnostics).noneMatch(d -> d.severity() == 1);
    }
}
