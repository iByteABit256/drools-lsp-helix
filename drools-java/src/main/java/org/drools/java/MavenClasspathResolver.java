package org.drools.java;

import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.maven.model.Dependency;
import org.apache.maven.model.Model;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;

/**
 * Resolves a Java classpath for a DRL document by locating the nearest Maven {@code pom.xml},
 * resolving the module's full dependency tree (transitively) against the local repository, and
 * adding the module's {@code target/classes}. Results are cached per pom and invalidated when the
 * pom changes.
 *
 * <p>This is a lightweight resolver: it walks each dependency's own pom from the local repository to
 * build the transitive closure, resolving {@code ${property}} references against each pom's own
 * properties. It does not implement full Maven semantics (no dependencyManagement/BOM version
 * mediation, no scope filtering), which is sufficient for type resolution of the Java embedded in DRL.
 */
public final class MavenClasspathResolver {

    private static final Pattern LOCAL_REPO = Pattern.compile("<localRepository>([^<]+)</localRepository>");
    private static final Map<Path, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(long mtime, List<String> classpath) {
    }

    private MavenClasspathResolver() {
    }

    /**
     * @param drlFile the DRL document location (used only to locate the nearest pom.xml)
     * @return classpath entries (jars and the module's target/classes), empty when no pom is found
     */
    public static List<String> resolve(Path drlFile) {
        Path pom = findNearestPom(drlFile);
        if (pom == null) {
            return List.of();
        }
        try {
            long mtime = Files.getLastModifiedTime(pom).toMillis();
            Cached cached = cache.get(pom);
            if (cached != null && cached.mtime() == mtime) {
                return cached.classpath();
            }
            List<String> classpath = doResolve(pom);
            cache.put(pom, new Cached(mtime, classpath));
            return classpath;
        } catch (IOException e) {
            return List.of();
        }
    }

    private static final int MAX_DEPTH = 25;

    private static List<String> doResolve(Path pom) {
        Model model = readModel(pom);
        if (model == null) {
            return List.of();
        }
        Path localRepo = localRepository();
        List<String> classpath = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Path targetClasses = pom.getParent().resolve("target").resolve("classes");
        if (Files.isDirectory(targetClasses)) {
            classpath.add(targetClasses.toString());
        }
        for (Dependency dep : model.getDependencies()) {
            resolveDependency(dep, propertiesFor(model), localRepo, classpath, visited, 0);
        }
        return classpath;
    }

    private static void resolveDependency(Dependency dep, Map<String, String> props, Path localRepo, List<String> classpath, Set<String> visited, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }
        String group = resolve(props, dep.getGroupId());
        String artifact = resolve(props, dep.getArtifactId());
        String version = resolve(props, dep.getVersion());
        if (isBlank(group) || isBlank(artifact) || isBlank(version)) {
            return;
        }
        if (!visited.add(group + ":" + artifact + ":" + version)) {
            return;
        }
        String groupPath = group.replace('.', '/');
        Path base = localRepo.resolve(groupPath).resolve(artifact).resolve(version);
        Path jar = base.resolve(artifact + "-" + version + ".jar");
        if (Files.isRegularFile(jar)) {
            classpath.add(jar.toString());
        }
        Model depModel = readModel(base.resolve(artifact + "-" + version + ".pom"));
        if (depModel == null) {
            return;
        }
        for (Dependency transitive : depModel.getDependencies()) {
            resolveDependency(transitive, propertiesFor(depModel), localRepo, classpath, visited, depth + 1);
        }
    }

    private static Map<String, String> propertiesFor(Model model) {
        Map<String, String> props = new HashMap<>();
        model.getProperties().stringPropertyNames().forEach(k -> props.put(k, model.getProperties().getProperty(k)));
        props.put("project.version", resolve(props, model.getVersion()));
        props.put("project.groupId", model.getGroupId());
        props.put("project.artifactId", model.getArtifactId());
        return props;
    }

    private static Model readModel(Path pom) {
        try (FileReader reader = new FileReader(pom.toFile())) {
            return new MavenXpp3Reader().read(reader);
        } catch (Exception e) {
            return null;
        }
    }

    private static Path findNearestPom(Path start) {
        Path dir = start.toAbsolutePath().getParent();
        while (dir != null) {
            Path pom = dir.resolve("pom.xml");
            if (Files.isRegularFile(pom)) {
                return pom;
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static Path localRepository() {
        Path settings = Path.of(System.getProperty("user.home"), ".m2", "settings.xml");
        if (Files.isRegularFile(settings)) {
            try {
                Matcher matcher = LOCAL_REPO.matcher(Files.readString(settings));
                if (matcher.find()) {
                    String repo = matcher.group(1).replace("${user.home}", System.getProperty("user.home"));
                    return Path.of(repo);
                }
            } catch (IOException ignored) {
                // fall through to the default
            }
        }
        return Path.of(System.getProperty("user.home"), ".m2", "repository");
    }

    private static String resolve(Map<String, String> props, String value) {
        if (value == null) {
            return null;
        }
        String result = value;
        for (int i = 0; i < 10 && result.contains("${"); i++) {
            int start = result.indexOf("${");
            int end = result.indexOf('}', start);
            if (end < 0) {
                break;
            }
            String key = result.substring(start + 2, end);
            String replacement = props.getOrDefault(key, "");
            result = result.substring(0, start) + replacement + result.substring(end + 1);
        }
        return result;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
