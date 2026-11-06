package org.drools.java;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class MavenClasspathResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void resolvesDirectDependenciesFromLocalRepo() throws Exception {
        Path module = tempDir.resolve("module");
        Files.createDirectories(module.resolve("target/classes"));
        String pom = """
                <?xml version="1.0" encoding="UTF-8"?>
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>org.example</groupId>
                  <artifactId>module</artifactId>
                  <version>1.0.0</version>
                  <properties>
                    <kie.version>8.44.0.Final</kie.version>
                  </properties>
                  <dependencies>
                    <dependency>
                      <groupId>org.kie</groupId>
                      <artifactId>kie-api</artifactId>
                      <version>${kie.version}</version>
                    </dependency>
                  </dependencies>
                </project>
                """;
        Files.writeString(module.resolve("pom.xml"), pom);
        Path drl = module.resolve("src/main/resources/test.drl");
        Files.createDirectories(drl.getParent());
        Files.writeString(drl, "rule R when then end");

        List<String> classpath = MavenClasspathResolver.resolve(drl);

        assertThat(classpath).anyMatch(p -> p.endsWith("kie-api-8.44.0.Final.jar"));
        assertThat(classpath).anyMatch(p -> p.endsWith("target" + System.getProperty("file.separator") + "classes"));
    }
}
