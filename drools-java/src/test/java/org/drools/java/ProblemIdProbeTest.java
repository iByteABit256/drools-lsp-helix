package org.drools.java;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProblemIdProbeTest {

    @Test
    void problemConstantsAreStable() {
        assertThat(org.eclipse.jdt.core.compiler.IProblem.UndefinedName).isEqualTo(570425394);
        assertThat(org.eclipse.jdt.core.compiler.IProblem.UnresolvedVariable).isEqualTo(33554515);
    }
}
