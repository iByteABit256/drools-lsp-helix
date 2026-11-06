package org.drools.java;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JavaIslandExtractorTest {

    @Test
    void extractsRhsAndFunctionBodies() {
        String drl = """
                package org.example;

                import org.example.model.Dog;

                global java.util.List list;

                function int doubleIt(int x) {
                    return x * 2;
                }

                rule MyRule
                  when
                    $dog : Dog(name == "Bart")
                  then
                    list.add($dog);
                end
                """;

        List<JavaIsland> islands = JavaIslandExtractor.extract(drl);

        assertThat(islands).hasSize(2);

        JavaIsland functionBody = islands.get(0);
        assertThat(functionBody.code()).contains("return x * 2");
        assertThat(functionBody.returnType()).isEqualTo("int");
        assertThat(functionBody.params()).containsExactly(new JavaIsland.Param("int", "x"));

        JavaIsland rhs = islands.get(1);
        assertThat(rhs.code()).contains("list.add($dog)");
        assertThat(rhs.returnType()).isEqualTo("void");
        assertThat(rhs.params()).containsExactly(new JavaIsland.Param("java.util.List", "list"));
    }

    @Test
    void noIslandsWhenNoJava() {
        String drl = """
                package org.example;

                query findDogs
                  Dog(name == "Bart")
                end
                """;

        assertThat(JavaIslandExtractor.extract(drl)).isEmpty();
    }
}
