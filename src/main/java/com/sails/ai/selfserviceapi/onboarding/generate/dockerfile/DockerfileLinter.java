package com.sails.ai.selfserviceapi.onboarding.generate.dockerfile;

import com.sails.ai.selfserviceapi.onboarding.generate.GenerationNoticeCode;
import com.sails.ai.selfserviceapi.onboarding.generate.secret.SecretHeuristics;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Everything {@code DockerfilePlanner} needs to know before trusting a Dockerfile — one it found
 * already in the repo (KEEP vs REPLACE), or one this feature just generated (a template must clear
 * every rule with zero ERROR/WARNING findings, proven by {@code DockerfileTemplatesTest}).
 *
 * <p>Parses instructions by hand rather than pulling in a Dockerfile-parsing library — this
 * codebase's existing preference for explicit field-by-field parsing over a dependency
 * ({@code ManifestParser}, {@code ShellWords}) applies here too, and the instruction shape this
 * needs (keyword, args, which build stage) is a small, stable grammar.
 */
@Component
public class DockerfileLinter {

    private static final Pattern BUILD_OUTPUT_PATH = Pattern.compile(
            "(^|[\\s/\"'])(target|build/libs|dist|build|out|publish)/|\\.jar(\\s|\"|'|$)");

    private static final Pattern BUILD_TOOL_INVOCATION = Pattern.compile(
            "\\b(mvn|mvnw|gradle|gradlew|npm run build|yarn build|pnpm build|go build|dotnet publish|dotnet build)\\b");

    private static final Pattern FIXED_PORT = Pattern.compile("(?:--port[= ]|:)(\\d{2,5})\\b");

    private static final Pattern EXPOSE_PORT = Pattern.compile("(\\d+)");

    private static final Set<String> KNOWN_NONROOT_BASE_IMAGE_FRAGMENTS =
            Set.of("distroless", "nginx-unprivileged");

    public List<LintFinding> lint(String dockerfileContent, int containerPort) {
        List<LintFinding> findings = new ArrayList<>();
        List<Instruction> instructions = parseInstructions(dockerfileContent);

        if (instructions.stream().noneMatch(i -> i.keyword().equals("FROM"))) {
            findings.add(LintFinding.error(GenerationNoticeCode.DOCKERFILE_NO_FROM,
                    "No FROM instruction — this is not a valid Dockerfile."));
            return findings;
        }

        int lastStage = instructions.stream().mapToInt(Instruction::stageIndex).max().orElse(0);

        checkExternalArtifact(instructions, lastStage, findings);
        checkLocalhostBind(instructions, findings);
        checkFixedPort(instructions, containerPort, findings);
        checkSecretsInImage(instructions, findings);
        checkRootUser(instructions, lastStage, findings);
        checkExposeMismatch(instructions, containerPort, findings);

        return findings;
    }

    /**
     * A build output (target/, build/libs/, dist/, out/, publish/, a *.jar) copied into the final
     * stage without {@code --from=} and with no build-tool RUN anywhere in the file means the image
     * expects an artifact built outside {@code docker build} — the shape a {@code cloudbuild.yaml}
     * step that runs {@code mvn package} before {@code docker build} produces. Leads to REPLACE.
     */
    private void checkExternalArtifact(List<Instruction> instructions, int lastStage, List<LintFinding> findings) {
        boolean hasBuildToolRun = instructions.stream()
                .anyMatch(i -> i.keyword().equals("RUN") && BUILD_TOOL_INVOCATION.matcher(i.args()).find());
        if (hasBuildToolRun) {
            return;
        }
        for (Instruction i : instructions) {
            if (i.stageIndex() != lastStage || !isCopyLike(i.keyword())) {
                continue;
            }
            if (i.args().contains("--from=")) {
                continue;
            }
            if (BUILD_OUTPUT_PATH.matcher(i.args()).find()) {
                findings.add(LintFinding.error(GenerationNoticeCode.DOCKERFILE_EXTERNAL_ARTIFACT,
                        "'" + i.keyword() + " " + i.args() + "' copies what looks like a build output, but "
                                + "nothing in this Dockerfile builds it and no earlier stage supplies it with "
                                + "--from= — the image likely depends on a build step that runs before "
                                + "'docker build', which this platform does not run."));
                return;
            }
        }
    }

    private void checkLocalhostBind(List<Instruction> instructions, List<LintFinding> findings) {
        for (Instruction i : instructions) {
            if (!isRuntimeCommand(i.keyword()) && !i.keyword().equals("ENV")) {
                continue;
            }
            if (i.args().contains("127.0.0.1") || i.args().contains("localhost")) {
                findings.add(LintFinding.error(GenerationNoticeCode.DOCKERFILE_LOCALHOST_BIND,
                        "'" + i.keyword() + "' binds to 127.0.0.1/localhost — Cloud Run's health checks and "
                                + "traffic reach the container from outside it, so a loopback-only bind means "
                                + "the container will never receive a real request."));
                return;
            }
        }
    }

    private void checkFixedPort(List<Instruction> instructions, int containerPort, List<LintFinding> findings) {
        for (Instruction i : instructions) {
            if (!isRuntimeCommand(i.keyword()) || referencesPortVariable(i.args())) {
                continue;
            }
            Matcher m = FIXED_PORT.matcher(i.args());
            while (m.find()) {
                int port = Integer.parseInt(m.group(1));
                if (port != containerPort) {
                    findings.add(LintFinding.warning(GenerationNoticeCode.DOCKERFILE_FIXED_PORT,
                            "'" + i.keyword() + "' pins port " + port + " directly instead of reading $PORT — "
                                    + "the platform injects PORT=" + containerPort + " at container start, so a "
                                    + "hardcoded port here will not match it."));
                    return;
                }
            }
        }
    }

    private void checkSecretsInImage(List<Instruction> instructions, List<LintFinding> findings) {
        for (Instruction i : instructions) {
            if ((i.keyword().equals("ENV") || i.keyword().equals("ARG"))) {
                String[] parts = i.args().split("[= ]", 2);
                if (parts.length == 2 && SecretHeuristics.nameLooksSecret(parts[0]) && !parts[1].isBlank()) {
                    findings.add(LintFinding.warning(GenerationNoticeCode.DOCKERFILE_SECRET_IN_IMAGE,
                            "'" + i.keyword() + " " + parts[0] + "' looks like a credential baked into the "
                                    + "image at build time — anyone who can pull the image can read it. Declare "
                                    + "it under poc.yaml's requires: instead."));
                    return;
                }
            }
            if (isCopyLike(i.keyword())) {
                String lower = i.args().toLowerCase(Locale.ROOT);
                if (looksLikeCredentialFile(lower)) {
                    findings.add(LintFinding.warning(GenerationNoticeCode.DOCKERFILE_SECRET_IN_IMAGE,
                            "'" + i.keyword() + " " + i.args() + "' copies what looks like a credential file "
                                    + "into the image."));
                    return;
                }
            }
        }
    }

    private void checkRootUser(List<Instruction> instructions, int lastStage, List<LintFinding> findings) {
        Optional<String> baseImage = instructions.stream()
                .filter(i -> i.keyword().equals("FROM") && i.stageIndex() == lastStage)
                .map(Instruction::args)
                .findFirst();
        boolean knownNonRoot = baseImage.map(img -> {
            String lower = img.toLowerCase(Locale.ROOT);
            return KNOWN_NONROOT_BASE_IMAGE_FRAGMENTS.stream().anyMatch(lower::contains);
        }).orElse(false);
        if (knownNonRoot) {
            return;
        }
        boolean hasNonRootUser = instructions.stream()
                .filter(i -> i.stageIndex() == lastStage && i.keyword().equals("USER"))
                .map(i -> firstWord(i.args()))
                .anyMatch(u -> !u.isBlank() && !u.equals("root") && !u.equals("0"));
        if (!hasNonRootUser) {
            findings.add(LintFinding.info(GenerationNoticeCode.DOCKERFILE_ROOT_USER,
                    "The final stage declares no non-root USER, so the container runs as root by default."));
        }
    }

    private void checkExposeMismatch(List<Instruction> instructions, int containerPort, List<LintFinding> findings) {
        for (Instruction i : instructions) {
            if (!i.keyword().equals("EXPOSE")) {
                continue;
            }
            Matcher m = EXPOSE_PORT.matcher(i.args());
            if (m.find() && Integer.parseInt(m.group(1)) != containerPort) {
                findings.add(LintFinding.info(GenerationNoticeCode.DOCKERFILE_EXPOSE_MISMATCH,
                        "EXPOSE " + m.group(1) + " does not match this container's declared port ("
                                + containerPort + "). EXPOSE is documentation only — Cloud Run always routes to "
                                + "the declared port — but a mismatch here usually means one of the two is wrong."));
                return;
            }
        }
    }

    private boolean isCopyLike(String keyword) {
        return keyword.equals("COPY") || keyword.equals("ADD");
    }

    private boolean isRuntimeCommand(String keyword) {
        return keyword.equals("CMD") || keyword.equals("ENTRYPOINT");
    }

    private boolean referencesPortVariable(String args) {
        return args.contains("$PORT") || args.contains("${PORT");
    }

    private boolean looksLikeCredentialFile(String lowerArgs) {
        return lowerArgs.matches(".*(^|[\\s\"'])\\.env(\\.[^\\s\"']*)?([\\s\"']|$).*")
                || lowerArgs.contains(".pem")
                || lowerArgs.contains("credentials")
                || lowerArgs.matches(".*key[^\\s\"']*\\.json.*");
    }

    private String firstWord(String args) {
        int sp = args.indexOf(' ');
        return (sp < 0 ? args : args.substring(0, sp)).strip();
    }

    // --- parsing ---------------------------------------------------------------------------------

    private record Instruction(String keyword, String args, int stageIndex) {
    }

    private List<Instruction> parseInstructions(String content) {
        String joined = joinLineContinuations(content);
        List<Instruction> instructions = new ArrayList<>();
        int stageIndex = -1;
        for (String rawLine : joined.split("\n", -1)) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int sp = line.indexOf(' ');
            String keyword = (sp < 0 ? line : line.substring(0, sp)).toUpperCase(Locale.ROOT);
            String args = sp < 0 ? "" : line.substring(sp + 1).strip();
            if (keyword.equals("FROM")) {
                stageIndex++;
            }
            instructions.add(new Instruction(keyword, args, Math.max(stageIndex, 0)));
        }
        return instructions;
    }

    /** A line ending in {@code \} (not {@code \\}) continues onto the next, backslash-and-newline removed. */
    private String joinLineContinuations(String content) {
        String[] rawLines = content.replace("\r\n", "\n").split("\n", -1);
        StringBuilder joined = new StringBuilder();
        StringBuilder buffer = new StringBuilder();
        for (String rawLine : rawLines) {
            String line = stripTrailingWhitespace(rawLine);
            if (endsWithSingleBackslash(line)) {
                buffer.append(line, 0, line.length() - 1).append(' ');
            } else {
                buffer.append(line);
                joined.append(buffer).append('\n');
                buffer.setLength(0);
            }
        }
        if (!buffer.isEmpty()) {
            joined.append(buffer);
        }
        return joined.toString();
    }

    private boolean endsWithSingleBackslash(String line) {
        if (!line.endsWith("\\")) {
            return false;
        }
        int backslashes = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    private String stripTrailingWhitespace(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }
}
