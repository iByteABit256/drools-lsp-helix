package org.drools.drl.parser.antlr4;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RealDrlSyntaxProbeTest {

    private static final Path DRL = Path.of(
            "C:/Users/psmith/IdeaProjects/platform-certificate-handler/src/main/resources/com.intrasoft.ermis.platform.certificatehandler.brms/br10191/br10191.drl");

    @Test
    void probe() throws Exception {
        assumeTrue(Files.exists(DRL), "user project not present");
        String drl = Files.readString(DRL);
        List<DRLSyntaxError> errors = DRLDiagnosticsHelper.collectSyntaxErrors(drl);
        System.out.println("SYNTAX ERRORS: " + errors.size());
        errors.stream().limit(40).forEach(e -> System.out.println("  line=" + e.startLine() + " char=" + e.startChar() + " " + e.message()));
        assertThat(true).isTrue();
    }
}
