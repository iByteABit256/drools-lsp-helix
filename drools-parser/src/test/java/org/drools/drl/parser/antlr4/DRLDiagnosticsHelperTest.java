package org.drools.drl.parser.antlr4;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DRLDiagnosticsHelperTest {

    @Test
    void validDrl_hasNoSyntaxErrors() {
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

        List<DRLSyntaxError> errors = DRLDiagnosticsHelper.collectSyntaxErrors(drl);
        assertThat(errors).isEmpty();
    }

    @Test
    void missingEnd_reportsError() {
        String drl = """
                package org.test;
                rule TestRule
                  when
                    $p:Person( age >= 18 )
                  then
                    System.out.println($p.getName());
                """;

        List<DRLSyntaxError> errors = DRLDiagnosticsHelper.collectSyntaxErrors(drl);
        assertThat(errors).isNotEmpty();
        assertThat(errors).allSatisfy(err -> assertThat(err.message()).isNotBlank());
    }

    @Test
    void errorPositions_areLspZeroBased() {
        // The offending token is on the 2nd line (0-based line 1).
        String drl = "package org.test;\nrule TestRule when end";

        List<DRLSyntaxError> errors = DRLDiagnosticsHelper.collectSyntaxErrors(drl);
        assertThat(errors).isNotEmpty();
        assertThat(errors).anySatisfy(err -> {
            assertThat(err.startLine()).isEqualTo(1);
            assertThat(err.startChar()).isNotNegative();
        });
    }
}
