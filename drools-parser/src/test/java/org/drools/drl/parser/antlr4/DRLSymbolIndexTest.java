package org.drools.drl.parser.antlr4;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DRLSymbolIndexTest {

    private static final String DRL = """
            package org.test;
            import org.test.model.Person;
            global String result;
            declare Person
              age : int;
            end
            function int add(int a, int b) {
              return a + b;
            }
            query activePersons
              Person( age >= 18 )
            end
            rule TestRule
              when
                $p:Person( age >= 18 )
              then
                result = "done";
            end
            """;

    @Test
    void extractsAllKinds() {
        List<DRLSymbol> symbols = DRLSymbolIndex.extract(DRL);
        assertThat(symbols).extracting(DRLSymbol::kind)
                .contains(DRLSymbol.KIND_GLOBAL, DRLSymbol.KIND_FACT_TYPE, DRLSymbol.KIND_FUNCTION, DRLSymbol.KIND_QUERY, DRLSymbol.KIND_RULE);
    }

    @Test
    void functionSymbol_hasNameAndPosition() {
        DRLSymbol add = DRLSymbolIndex.extract(DRL).stream().filter(s -> s.name().equals("add")).findFirst().orElseThrow();
        assertThat(add.kind()).isEqualTo(DRLSymbol.KIND_FUNCTION);
        assertThat(add.startLine()).isEqualTo(6);
        assertThat(add.startChar()).isEqualTo(13);
        assertThat(add.signature()).contains("add");
    }

    @Test
    void globalSymbol_position() {
        DRLSymbol result = DRLSymbolIndex.extract(DRL).stream().filter(s -> s.name().equals("result")).findFirst().orElseThrow();
        assertThat(result.kind()).isEqualTo(DRLSymbol.KIND_GLOBAL);
        assertThat(result.startLine()).isEqualTo(2);
        assertThat(result.startChar()).isEqualTo(14);
    }

    @Test
    void importedFactType_usesSimpleName() {
        DRLSymbol person = DRLSymbolIndex.extract(DRL).stream()
                .filter(s -> s.kind().equals(DRLSymbol.KIND_FACT_TYPE) && s.name().equals("Person"))
                .findFirst().orElseThrow();
        assertThat(person.signature()).contains("org.test.model.Person");
    }

    @Test
    void contains_matchesCaretOnName() {
        DRLSymbol result = DRLSymbolIndex.extract(DRL).stream().filter(s -> s.name().equals("result")).findFirst().orElseThrow();
        assertThat(result.contains(2, 14)).isTrue();
        assertThat(result.contains(2, 17)).isTrue();
        assertThat(result.contains(2, 13)).isFalse();
        assertThat(result.contains(3, 14)).isFalse();
    }
}
