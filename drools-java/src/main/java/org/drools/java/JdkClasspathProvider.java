package org.drools.java;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Provides the running JDK's classes as a directory that ecj can read.
 *
 * <p>ecj's {@code FileSystem} does not read {@code .jmod} files directly, so the
 * {@code classes/} tree of every platform jmod is extracted once into a cache
 * directory that ecj treats as a plain classpath directory. The result is cached
 * per JDK version and reused across calls.
 */
final class JdkClasspathProvider {

    private static final Path CACHE = Path.of(System.getProperty("user.home"), ".cache", "drools-lsp",
            "jdk-classes-" + System.getProperty("java.version"));
    private static final Path MARKER = CACHE.resolve(".extracted");

    private JdkClasspathProvider() {
    }

    /**
     * @return the directory containing the extracted JDK classes, or {@code null} when unavailable
     */
    static Path jdkClassesDir() {
        try {
            if (Files.exists(MARKER)) {
                return CACHE;
            }
            extract();
            return CACHE;
        } catch (Exception e) {
            return null;
        }
    }

    private static void extract() throws IOException {
        Path jmods = Path.of(System.getProperty("java.home"), "jmods");
        if (!Files.isDirectory(jmods)) {
            throw new IOException("no jmods directory at " + jmods);
        }
        Files.createDirectories(CACHE);
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(jmods, "*.jmod")) {
            for (Path jmod : stream) {
                try (ZipFile zip = new ZipFile(jmod.toFile())) {
                    Enumeration<? extends ZipEntry> entries = zip.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (entry.isDirectory() || !name.startsWith("classes/") || !name.endsWith(".class")) {
                            continue;
                        }
                        Path out = CACHE.resolve(name.substring("classes/".length()));
                        Files.createDirectories(out.getParent());
                        try (InputStream in = zip.getInputStream(entry)) {
                            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                }
            }
        }
        Files.writeString(MARKER, String.valueOf(System.nanoTime()));
    }
}
