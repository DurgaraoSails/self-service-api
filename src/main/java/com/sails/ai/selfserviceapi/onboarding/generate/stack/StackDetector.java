package com.sails.ai.selfserviceapi.onboarding.generate.stack;

import com.sails.ai.selfserviceapi.onboarding.generate.RepoFileReader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Recognizes one application stack per directory, from the basenames {@link com.sails.ai.selfserviceapi.onboarding.generate.RepoLayout}
 * already found there plus a bounded number of reads through {@link RepoFileReader} for the marker
 * files that decide it (package.json, pom.xml, go.mod, ...).
 *
 * <p>Every detection is deterministic and template-driven, never a model call — {@code UNKNOWN} is
 * the honest answer for a directory this class has no rule for, and {@code DockerfilePlanner} falls
 * back to a model only then. Priority order within Node and Python is deliberate: a framework built
 * on another framework (Next.js on React, FastAPI-with-a-package.json-sibling) must not be
 * misdetected as the more generic one beneath it.
 */
@Component
public class StackDetector {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final Set<String> SERVER_FRAMEWORK_DEPENDENCIES =
            Set.of("express", "fastify", "koa", "@nestjs/core", "hono");

    public DetectedStack detect(RepoFileReader fileReader, String directory, Set<String> basenames) {
        boolean hasPackageJson = basenames.contains("package.json");
        boolean hasPythonMarker = basenames.contains("requirements.txt") || basenames.contains("pyproject.toml");

        // Node and Python markers in the same directory is a conflict this class refuses to guess
        // at, rather than picking one ecosystem arbitrarily.
        if (hasPackageJson && hasPythonMarker) {
            return DetectedStack.unknown(directory);
        }
        if (hasPackageJson) {
            return detectNode(fileReader, directory, basenames);
        }
        if (hasPythonMarker) {
            return detectPython(fileReader, directory, basenames);
        }
        if (basenames.contains("pom.xml")) {
            return detectMaven(fileReader, directory, basenames);
        }
        if (basenames.contains("build.gradle") || basenames.contains("build.gradle.kts")) {
            return detectGradle(fileReader, directory, basenames);
        }
        if (basenames.contains("go.mod")) {
            return detectGo(fileReader, directory, basenames);
        }
        Optional<String> csproj = basenames.stream().filter(b -> b.toLowerCase().endsWith(".csproj")).findFirst();
        if (csproj.isPresent() && basenames.stream().filter(b -> b.toLowerCase().endsWith(".csproj")).count() == 1) {
            return detectDotnet(fileReader, directory, csproj.get());
        }
        if (basenames.contains("index.html")) {
            return new DetectedStack(StackKind.STATIC_SITE, directory, Map.of(), List.of(path(directory, "index.html")));
        }
        return DetectedStack.unknown(directory);
    }

    // --- Node ---------------------------------------------------------------------------------

    private DetectedStack detectNode(RepoFileReader fileReader, String directory, Set<String> basenames) {
        String packageJsonPath = path(directory, "package.json");
        String content = fileReader.read(packageJsonPath).orElse(null);
        JsonNode root = parseJson(content);
        Set<String> deps = allDependencyNames(root);
        JsonNode scripts = root == null ? null : root.path("scripts");

        Map<String, String> params = new LinkedHashMap<>();
        params.put("nodeVersion", nodeVersion(fileReader, directory, root));
        params.put("installCmd", nodeInstallCommand(basenames));

        if (deps.contains("next")) {
            return new DetectedStack(StackKind.NEXTJS, directory, params, List.of(packageJsonPath));
        }
        if (deps.contains("@angular/core")) {
            params.put("distDir", angularDistDir(fileReader, directory, basenames));
            return new DetectedStack(StackKind.ANGULAR_SPA, directory, params, evidenceFor(directory, packageJsonPath, basenames, "angular.json"));
        }
        boolean hasServerFramework = deps.stream().anyMatch(SERVER_FRAMEWORK_DEPENDENCIES::contains);
        if (deps.contains("vite") && !hasServerFramework) {
            params.put("distDir", "dist");
            return new DetectedStack(StackKind.VITE_SPA, directory, params, List.of(packageJsonPath));
        }
        if (deps.contains("react-scripts") && !hasServerFramework) {
            params.put("distDir", "build");
            return new DetectedStack(StackKind.CRA_SPA, directory, params, List.of(packageJsonPath));
        }
        // Anything else with a package.json and either a start script or a main entry is treated
        // as a plain Node server — the common shape for an API with no recognized framework name.
        String main = root == null ? null : textOrNull(root.path("main"));
        boolean hasStartScript = scripts != null && scripts.has("start");
        if (hasServerFramework || hasStartScript || main != null) {
            params.put("hasBuild", String.valueOf(scripts != null && scripts.has("build")));
            params.put("startCmd", main != null ? "node " + main : "npm start");
            return new DetectedStack(StackKind.NODE_SERVER, directory, params, List.of(packageJsonPath));
        }
        return DetectedStack.unknown(directory);
    }

    private String nodeInstallCommand(Set<String> basenames) {
        if (basenames.contains("pnpm-lock.yaml")) {
            return "corepack enable && pnpm install --frozen-lockfile";
        }
        if (basenames.contains("yarn.lock")) {
            return "corepack enable && yarn install --frozen-lockfile";
        }
        if (basenames.contains("package-lock.json")) {
            return "npm ci";
        }
        return "npm install";
    }

    private String nodeVersion(RepoFileReader fileReader, String directory, JsonNode packageJson) {
        if (packageJson != null) {
            String engineVersion = textOrNull(packageJson.path("engines").path("node"));
            if (engineVersion != null) {
                Matcher m = Pattern.compile("(\\d+)").matcher(engineVersion);
                if (m.find()) {
                    return m.group(1);
                }
            }
        }
        String nvmrc = fileReader.read(path(directory, ".nvmrc")).orElse(null);
        if (nvmrc != null) {
            Matcher m = Pattern.compile("(\\d+)").matcher(nvmrc.trim());
            if (m.find()) {
                return m.group(1);
            }
        }
        return "22";
    }

    /**
     * Reads {@code angular.json}'s first project's build {@code outputPath}, appending
     * {@code /browser} for the newer application builder ({@code @angular/build:application} or
     * {@code @angular-devkit/build-angular:application}), which nests the served output one
     * directory deeper than the legacy {@code browser} builder did.
     */
    private String angularDistDir(RepoFileReader fileReader, String directory, Set<String> basenames) {
        if (!basenames.contains("angular.json")) {
            return "dist";
        }
        String content = fileReader.read(path(directory, "angular.json")).orElse(null);
        JsonNode root = parseJson(content);
        if (root == null) {
            return "dist";
        }
        JsonNode projects = root.path("projects");
        String outputPath = null;
        boolean applicationBuilder = false;
        var fields = projects.propertyNames().iterator();
        if (fields.hasNext()) {
            JsonNode project = projects.path(fields.next());
            JsonNode build = project.path("architect").path("build");
            if (build.isMissingNode()) {
                build = project.path("targets").path("build");
            }
            String builder = textOrNull(build.path("builder"));
            applicationBuilder = builder != null && builder.endsWith(":application");
            outputPath = textOrNull(build.path("options").path("outputPath"));
        }
        if (outputPath == null) {
            outputPath = "dist/" + directory.replace('/', '-');
        }
        return applicationBuilder ? outputPath + "/browser" : outputPath;
    }

    // --- Python ---------------------------------------------------------------------------------

    private DetectedStack detectPython(RepoFileReader fileReader, String directory, Set<String> basenames) {
        String depsFile = basenames.contains("requirements.txt") ? "requirements.txt" : "pyproject.toml";
        String depsPath = path(directory, depsFile);
        String depsContent = fileReader.read(depsPath).orElse("").toLowerCase();

        Map<String, String> params = new LinkedHashMap<>();
        params.put("pythonVersion", pythonVersion(fileReader, directory));
        params.put("installCmd", basenames.contains("requirements.txt") ? "-r requirements.txt" : ".");

        if (depsContent.contains("streamlit")) {
            params.put("entryScript", guessPythonEntry(fileReader, directory, basenames, "streamlit"));
            return new DetectedStack(StackKind.PYTHON_STREAMLIT, directory, params, List.of(depsPath));
        }
        if (depsContent.contains("gradio")) {
            params.put("entryScript", guessPythonEntry(fileReader, directory, basenames, "gradio"));
            return new DetectedStack(StackKind.PYTHON_GRADIO, directory, params, List.of(depsPath));
        }
        if (depsContent.contains("fastapi")) {
            params.put("appModule", guessPythonAppModule(fileReader, directory, basenames, "FastAPI("));
            return new DetectedStack(StackKind.PYTHON_FASTAPI, directory, params, List.of(depsPath));
        }
        if (depsContent.contains("django") && basenames.contains("manage.py")) {
            String managePy = fileReader.read(path(directory, "manage.py")).orElse("");
            params.put("appModule", djangoWsgiModule(managePy));
            return new DetectedStack(StackKind.PYTHON_DJANGO, directory, params, List.of(depsPath, path(directory, "manage.py")));
        }
        if (depsContent.contains("flask")) {
            params.put("appModule", guessPythonAppModule(fileReader, directory, basenames, "Flask("));
            return new DetectedStack(StackKind.PYTHON_FLASK, directory, params, List.of(depsPath));
        }
        return DetectedStack.unknown(directory);
    }

    /** {@code manage.py} sets DJANGO_SETTINGS_MODULE to {@code <project>.settings}; the wsgi module lives alongside it. */
    private String djangoWsgiModule(String managePy) {
        Matcher m = Pattern.compile("DJANGO_SETTINGS_MODULE['\"]?\\s*,\\s*['\"]([\\w.]+)\\.settings['\"]").matcher(managePy);
        String project = m.find() ? m.group(1) : "app";
        return project + ".wsgi:application";
    }

    private String pythonVersion(RepoFileReader fileReader, String directory) {
        String pinned = fileReader.read(path(directory, ".python-version")).orElse(null);
        if (pinned != null && !pinned.isBlank()) {
            Matcher m = Pattern.compile("(\\d+\\.\\d+)").matcher(pinned.trim());
            if (m.find()) {
                return m.group(1);
            }
        }
        return "3.12";
    }

    /** {@code module:app} for an ASGI/WSGI server — the file containing the framework's constructor call, or {@code main} as a guess. */
    private String guessPythonAppModule(RepoFileReader fileReader, String directory, Set<String> basenames, String constructorCall) {
        for (String basename : preferredPythonEntrypoints(basenames)) {
            String content = fileReader.read(path(directory, basename)).orElse("");
            if (content.contains(constructorCall)) {
                return stripPyExtension(basename) + ":app";
            }
        }
        return "main:app";
    }

    private String guessPythonEntry(RepoFileReader fileReader, String directory, Set<String> basenames, String importName) {
        for (String basename : preferredPythonEntrypoints(basenames)) {
            String content = fileReader.read(path(directory, basename)).orElse("");
            if (content.contains(importName)) {
                return basename;
            }
        }
        return "main.py";
    }

    private List<String> preferredPythonEntrypoints(Set<String> basenames) {
        return List.of("main.py", "app.py", "server.py", "wsgi.py", "asgi.py").stream()
                .filter(basenames::contains).toList();
    }

    private String stripPyExtension(String basename) {
        return basename.endsWith(".py") ? basename.substring(0, basename.length() - 3) : basename;
    }

    // --- JVM ---------------------------------------------------------------------------------

    private DetectedStack detectMaven(RepoFileReader fileReader, String directory, Set<String> basenames) {
        String pomPath = path(directory, "pom.xml");
        String pom = fileReader.read(pomPath).orElse("");
        Map<String, String> params = new LinkedHashMap<>();
        String javaVersion = firstGroupOr(pom, "<java\\.version>\\s*(\\d+)", null);
        if (javaVersion == null) {
            javaVersion = firstGroupOr(pom, "<maven\\.compiler\\.release>\\s*(\\d+)", "21");
        }
        params.put("javaVersion", javaVersion);
        params.put("buildCmd", (basenames.contains("mvnw") ? "./mvnw" : "mvn") + " -B -DskipTests package");
        params.put("runArgs", springBootRunArgs(pom.contains("spring-boot")));
        return new DetectedStack(StackKind.JAVA_MAVEN, directory, params, List.of(pomPath));
    }

    private DetectedStack detectGradle(RepoFileReader fileReader, String directory, Set<String> basenames) {
        String buildFile = basenames.contains("build.gradle.kts") ? "build.gradle.kts" : "build.gradle";
        String buildPath = path(directory, buildFile);
        String build = fileReader.read(buildPath).orElse("");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("javaVersion", firstGroupOr(build, "JavaLanguageVersion\\.of\\((\\d+)\\)", "21"));
        params.put("buildCmd", (basenames.contains("gradlew") ? "./gradlew" : "gradle") + " build -x test");
        params.put("runArgs", springBootRunArgs(build.contains("spring-boot")));
        return new DetectedStack(StackKind.JAVA_GRADLE, directory, params, List.of(buildPath));
    }

    /**
     * Spring Boot reads its port/bind-address from these flags; a plain {@code main(String[])} jar
     * would instead receive them as ordinary positional args, which could break its own argument
     * parsing — so they are added only when {@code spring-boot} actually appears in the build file.
     */
    private String springBootRunArgs(boolean springBoot) {
        return springBoot ? " --server.port=${PORT:-8080} --server.address=0.0.0.0" : "";
    }

    // --- Go / .NET ---------------------------------------------------------------------------------

    private DetectedStack detectGo(RepoFileReader fileReader, String directory, Set<String> basenames) {
        String goModPath = path(directory, "go.mod");
        String goMod = fileReader.read(goModPath).orElse("");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("goVersion", firstGroupOr(goMod, "(?m)^go\\s+(\\d+\\.\\d+)", "1.23"));
        params.put("mainPackage", ".");
        return new DetectedStack(StackKind.GO, directory, params, List.of(goModPath));
    }

    private DetectedStack detectDotnet(RepoFileReader fileReader, String directory, String csprojBasename) {
        String csprojPath = path(directory, csprojBasename);
        String csproj = fileReader.read(csprojPath).orElse("");
        Map<String, String> params = new LinkedHashMap<>();
        params.put("dotnetVersion", firstGroupOr(csproj, "<TargetFramework>net(\\d+\\.\\d+)", "8.0"));
        params.put("assemblyName", stripExtension(csprojBasename));
        return new DetectedStack(StackKind.DOTNET, directory, params, List.of(csprojPath));
    }

    // --- shared helpers ---------------------------------------------------------------------------------

    private String path(String directory, String basename) {
        return directory.isEmpty() ? basename : directory + "/" + basename;
    }

    private List<String> evidenceFor(String directory, String packageJsonPath, Set<String> basenames, String extra) {
        return basenames.contains(extra) ? List.of(packageJsonPath, path(directory, extra)) : List.of(packageJsonPath);
    }

    private String stripExtension(String basename) {
        int dot = basename.lastIndexOf('.');
        return dot < 0 ? basename : basename.substring(0, dot);
    }

    private String firstGroupOr(String text, String pattern, String fallback) {
        Matcher m = Pattern.compile(pattern).matcher(text);
        return m.find() ? m.group(1) : fallback;
    }

    private JsonNode parseJson(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            return JSON.readTree(content);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String textOrNull(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull() ? null : node.asString();
    }

    private Set<String> allDependencyNames(JsonNode packageJson) {
        if (packageJson == null) {
            return Set.of();
        }
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<>();
        names.addAll(packageJson.path("dependencies").propertyNames());
        names.addAll(packageJson.path("devDependencies").propertyNames());
        return names;
    }
}
