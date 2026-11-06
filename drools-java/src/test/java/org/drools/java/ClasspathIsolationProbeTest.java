package org.drools.java;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ClasspathIsolationProbeTest {

    private static final String LIST_SNIPPET = "import java.util.List;\nfunction f() {\n    List<String> x = List.of();\n}";

    @Test
    void jdkTypesResolve() {
        List<JavaDiagnostic> diags = JdtJavaService.diagnostics(LIST_SNIPPET, List.of());
        diags.forEach(d -> System.out.println("  " + d.message()));
        boolean listResolved = diags.stream().noneMatch(d -> d.message().contains("List cannot be resolved"));
        System.out.println("listResolved=" + listResolved);
        assertThat(listResolved).isTrue();
    }
}
