package org.drools.lsp.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.drools.drl.parser.antlr4.DRLDiagnosticsHelper;
import org.drools.drl.parser.antlr4.DRLSyntaxError;
import org.drools.java.JavaIsland;
import org.drools.java.JavaIslandExtractor;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RealDrlFunctionBodySuppressionProbeTest {

    private static final Path DRL = Path.of(
            "C:/Users/psmith/IdeaProjects/platform-certificate-handler/src/main/resources/com.intrasoft.ermis.platform.certificatehandler.brms/br10191/br10191.drl");

    @Test
    void probe() throws Exception {
        assumeTrue(Files.exists(DRL), "user project not present");
        String drl = Files.readString(DRL);

        List<DRLSyntaxError> all = DRLDiagnosticsHelper.collectSyntaxErrors(drl);
        List<int[]> ranges = JavaIslandExtractor.extract(drl).stream()
                .filter(i -> i.kind() == JavaIsland.Kind.FUNCTION)
                .map(i -> new int[]{i.startOffset() - 1, i.startOffset() + i.code().length() + 1})
                .collect(Collectors.toList());

        List<DRLSyntaxError> remaining = all.stream()
                .filter(e -> !inRange(drl, ranges, e.startLine(), e.startChar()))
                .collect(Collectors.toList());

        System.out.println("TOTAL DRL SYNTAX ERRORS: " + all.size());
        System.out.println("FUNCTION BODY RANGES: " + ranges.size());
        System.out.println("REMAINING (outside function bodies): " + remaining.size());
        remaining.stream().limit(20).forEach(e ->
                System.out.println("  line=" + e.startLine() + " char=" + e.startChar() + " " + e.message()));
        assertThat(true).isTrue();
    }

    private boolean inRange(String text, List<int[]> ranges, int line, int character) {
        int offset = positionToOffset(text, line, character);
        return ranges.stream().anyMatch(r -> offset >= r[0] && offset <= r[1]);
    }

    private int positionToOffset(String text, int line, int character) {
        int offset = 0;
        int currentLine = 0;
        while (currentLine < line && offset < text.length()) {
            if (text.charAt(offset) == '\n') {
                currentLine++;
            }
            offset++;
        }
        return Math.min(offset + character, text.length());
    }
}
