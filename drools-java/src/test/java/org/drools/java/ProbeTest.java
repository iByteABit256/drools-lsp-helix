package org.drools.java;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers DRL LHS pattern variables used as a Java qualifier (e.g. {@code $p.getName()}), which ecj
 * reports as {@code UndefinedName} rather than {@code UnresolvedVariable}.
 */
class ProbeTest {

    private static final List<String> NO_CLASSPATH = List.of();

    @Test
    void dollarVariableUsedAsQualifierIsSuppressed() {
        String drl = """
                rule R
                  when
                    $p : Person(age >= 18)
                  then
                    System.out.println($p.getName());
                end
                """;

        List<JavaDiagnostic> diagnostics = JdtJavaService.diagnostics(drl, NO_CLASSPATH);

        assertThat(diagnostics).noneMatch(d -> d.severity() == 1);
        assertThat(diagnostics).noneMatch(d -> d.message().contains("$p"));
    }
}
