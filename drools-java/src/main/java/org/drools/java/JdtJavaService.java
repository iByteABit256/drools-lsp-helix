package org.drools.java;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jdt.core.compiler.CategorizedProblem;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.Compiler;
import org.eclipse.jdt.internal.compiler.ICompilerRequestor;
import org.eclipse.jdt.internal.compiler.IErrorHandlingPolicy;
import org.eclipse.jdt.internal.compiler.batch.CompilationUnit;
import org.eclipse.jdt.internal.compiler.batch.FileSystem;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;

/**
 * Runs the Eclipse JDT batch compiler over the embedded Java islands of a DRL document and maps the
 * resulting problems back onto the original DRL text.
 *
 * <p>Each island is wrapped in a synthetic class/method whose signature mirrors the island's real
 * context (a function body keeps the function's return type and formal parameters; a rule RHS keeps
 * the DRL {@code global} variables). Problem offsets, which are relative to the wrapped unit, are
 * translated back to absolute offsets in the DRL document.
 */
public final class JdtJavaService {

    private static final String CLASS_NAME = "__LspIsland";
    private static final String METHOD_NAME = "__lspMethod";
    private static final String FILE_NAME = "__LspIsland.java";
    private static final String ENCODING = "UTF-8";
    private static final String JAVA_LEVEL = "17";

    // The classpath name environment is expensive to build (it indexes every jar on the classpath), so
    // it is cached per classpath and reused across keystrokes. Compiler options are constant.
    private static final Map<String, FileSystem> NAME_ENVIRONMENT_CACHE = new ConcurrentHashMap<>();
    private static final CompilerOptions OPTIONS = new CompilerOptions(compilerOptions());

    private JdtJavaService() {
    }

    /**
     * @param drl       the full DRL document text
     * @param classpath classpath entries (jars/dirs) for resolving types; the running JDK is always added
     * @return diagnostics positioned in the DRL document, empty when there are no islands or no problems
     */
    public static List<JavaDiagnostic> diagnostics(String drl, List<String> classpath) {
        return diagnostics(JavaIslandExtractor.extract(drl), JavaIslandExtractor.extractImports(drl), classpath);
    }

    /**
     * Compiles the given Java islands (already extracted from a parsed DRL document) and maps the
     * resulting problems back onto the original DRL text. The classpath name environment and compiler
     * options are reused across calls, so repeated keystrokes do not re-scan the classpath.
     *
     * @param islands   the extracted Java islands
     * @param imports   the DRL import statements to prepend to each synthetic compilation unit
     * @param classpath classpath entries for resolving types; the running JDK is always added
     */
    public static List<JavaDiagnostic> diagnostics(List<JavaIsland> islands, String imports, List<String> classpath) {
        if (islands.isEmpty()) {
            return List.of();
        }
        int drlLength = islands.stream().mapToInt(i -> i.startOffset() + i.code().length()).max().orElse(0);
        FileSystem nameEnvironment = nameEnvironment(classpath);
        IErrorHandlingPolicy policy = nonStoppingPolicy();
        List<JavaDiagnostic> result = new ArrayList<>();
        for (JavaIsland island : islands) {
            String prefix = imports + methodPrefix(island);
            String unit = prefix + island.code() + "\n    }\n}";
            for (IProblem problem : compile(unit, nameEnvironment, OPTIONS, policy)) {
                if (suppress(problem, island)) {
                    continue;
                }
                result.add(toDiagnostic(problem, prefix.length(), island.startOffset(), drlLength));
            }
        }
        return result;
    }

    private static FileSystem nameEnvironment(List<String> classpath) {
        String[] effective = withJdk(classpath);
        String key = String.join("|", effective);
        return NAME_ENVIRONMENT_CACHE.computeIfAbsent(key, k -> new FileSystem(effective, new String[0], ENCODING));
    }

    private static IErrorHandlingPolicy nonStoppingPolicy() {
        return new IErrorHandlingPolicy() {
            @Override
            public boolean proceedOnErrors() {
                return true;
            }

            @Override
            public boolean stopOnFirstError() {
                return false;
            }

            @Override
            public boolean ignoreAllErrors() {
                return false;
            }
        };
    }

    private static String methodPrefix(JavaIsland island) {
        StringBuilder sb = new StringBuilder();
        sb.append("class ").append(CLASS_NAME).append(" {\n    ");
        sb.append(island.returnType()).append(' ').append(METHOD_NAME).append('(');
        for (int i = 0; i < island.params().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            JavaIsland.Param param = island.params().get(i);
            sb.append(param.type()).append(' ').append(param.name());
        }
        sb.append(") {\n");
        return sb.toString();
    }

    private static List<IProblem> compile(String unit, FileSystem nameEnvironment, CompilerOptions options, IErrorHandlingPolicy policy) {
        List<IProblem> problems = new ArrayList<>();
        ICompilerRequestor requestor = new ICompilerRequestor() {
            @Override
            public void acceptResult(CompilationResult result) {
                CategorizedProblem[] unitProblems = result.getProblems();
                if (unitProblems != null) {
                    for (CategorizedProblem problem : unitProblems) {
                        problems.add(problem);
                    }
                }
            }
        };
        Compiler compiler = new Compiler(nameEnvironment, policy, options, requestor, new DefaultProblemFactory());
        CompilationUnit cu = new CompilationUnit(unit.toCharArray(), FILE_NAME, ENCODING);
        compiler.compile(new CompilationUnit[] { cu });
        return problems;
    }

    private static Map<String, String> compilerOptions() {
        Map<String, String> options = new HashMap<>();
        options.put(CompilerOptions.OPTION_Source, JAVA_LEVEL);
        options.put(CompilerOptions.OPTION_Compliance, JAVA_LEVEL);
        options.put(CompilerOptions.OPTION_TargetPlatform, JAVA_LEVEL);
        return options;
    }

    private static String[] withJdk(List<String> classpath) {
        List<String> effective = new ArrayList<>(classpath);
        Path jdkClasses = JdkClasspathProvider.jdkClassesDir();
        if (jdkClasses != null) {
            effective.add(jdkClasses.toString());
        }
        return effective.toArray(new String[0]);
    }

    private static JavaDiagnostic toDiagnostic(IProblem problem, int prefixLength, int islandStart, int drlLength) {
        int unitStart = problem.getSourceStart();
        int unitEnd = problem.getSourceEnd();
        int drlStart;
        int drlEnd;
        if (unitStart < 0 || unitEnd < unitStart) {
            drlStart = islandStart;
            drlEnd = islandStart;
        } else {
            drlStart = islandStart + (unitStart - prefixLength);
            drlEnd = islandStart + (unitEnd - prefixLength);
        }
        drlStart = clamp(drlStart, 0, drlLength);
        drlEnd = clamp(drlEnd, drlStart, drlLength);
        return new JavaDiagnostic(drlStart, drlEnd, severity(problem), problem.getMessage());
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private static int severity(IProblem problem) {
        if (problem.isError()) {
            return 1;
        }
        if (problem.isWarning()) {
            return 2;
        }
        if (problem.isInfo()) {
            return 3;
        }
        return 4;
    }

    /**
     * Decides whether an ecj problem is expected noise from the DRL context rather than a real Java
     * error.
     *
     * <p>A genuinely missing <em>type</em> is always a real error and is never suppressed. Everything
     * else that stems from the gap between a static Java compiler and DRL's dynamic semantics is
     * treated as noise:
     * <ul>
     *   <li>cross-island DRL function calls surface as "method undefined for the type {@code __LspIsland}";</li>
     *   <li>the wrapper carries every DRL import, so most are "never used" within a single island;</li>
     *   <li>DRL globals are declared raw, triggering "raw type" warnings;</li>
     *   <li>a rule RHS can reference DRL LHS pattern variables and other dynamic context a static
     *       compiler cannot see, so unresolved names/variables there are expected (for a function body
     *       the scope is well defined, so only {@code $}-prefixed LHS variables are suppressed).</li>
     * </ul>
     */
    private static boolean suppress(IProblem problem, JavaIsland island) {
        String message = problem.getMessage();
        if (problem.getID() == IProblem.UndefinedType || message.contains("cannot be resolved to a type")) {
            return false;
        }
        if (message.contains("is undefined for the type " + CLASS_NAME)
                || message.contains("is never used")
                || message.contains("is a raw type")
                || (message.startsWith("The import") && message.contains("cannot be resolved"))) {
            return true;
        }
        int id = problem.getID();
        boolean nameOrVariable = id == IProblem.UndefinedName || id == IProblem.UnresolvedVariable
                || message.contains("cannot be resolved to a variable") || message.contains("is undefined");
        if (!nameOrVariable) {
            return false;
        }
        if (island.kind() == JavaIsland.Kind.RHS) {
            return true;
        }
        String[] arguments = problem.getArguments();
        return arguments.length > 0 && arguments[0] != null && arguments[0].startsWith("$");
    }
}
