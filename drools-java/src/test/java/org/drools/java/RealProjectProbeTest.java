package org.drools.java;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * End-to-end smoke test against a real Ermis DRL project. Skipped when the project is not present on
 * this machine. Verifies that a valid DRL yields no spurious Java diagnostics.
 */
class RealProjectProbeTest {

    private static final Path DRL = Path.of(
            "C:/Users/psmith/IdeaProjects/platform-certificate-handler/src/main/resources/com.intrasoft.ermis.platform.certificatehandler.brms/br10191/br10191.drl");

    @Test
    void validDrlYieldsNoSpuriousJavaDiagnostics() throws Exception {
        assumeTrue(Files.exists(DRL), "user project not present");
        String text = Files.readString(DRL);
        List<String> classpath = MavenClasspathResolver.resolve(DRL);
        List<JavaDiagnostic> diags = JdtJavaService.diagnostics(text, classpath);
        System.out.println("classpath=" + classpath.size() + " javaDiagnostics=" + diags.size());
        diags.stream().limit(20).forEach(d -> System.out.println("  [" + d.severity() + "] " + d.message()));
        assertThat(classpath).isNotEmpty();
        assertThat(diags).isEmpty();
    }
}
