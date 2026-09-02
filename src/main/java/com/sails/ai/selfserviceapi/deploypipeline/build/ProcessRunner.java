package com.sails.ai.selfserviceapi.deploypipeline.build;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs git, docker and gcloud on this machine for the local executor.
 *
 * Output streams to the log as it arrives, so a long {@code docker build} shows progress instead
 * of going silent for minutes. The tail is also kept in memory: a failure needs to carry the
 * actual error text into the deployment's errorMessage — a bare exit code tells an admin nothing.
 */
@Component
public class ProcessRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessRunner.class);

    /** Enough to carry the real error; short enough not to blow past errorMessage's 2000-char cap. */
    private static final int RETAINED_LINES = 40;

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    /** @return the command's captured output (last {@value #RETAINED_LINES} lines). */
    public String run(File workingDir, Duration timeout, String... command) {
        List<String> resolved = resolveCommand(command);
        log.info("$ {}", String.join(" ", redact(resolved)));

        Process process = start(workingDir, resolved);
        Tail tail = new Tail(RETAINED_LINES);

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("  {}", line);
                tail.add(line);
            }
        } catch (IOException e) {
            process.destroyForcibly();
            throw new LocalBuildException("Could not read output of: " + String.join(" ", redact(resolved)), e);
        }

        awaitExit(process, timeout, resolved, tail);
        return tail.joined();
    }

    private void awaitExit(Process process, Duration timeout, List<String> command, Tail tail) {
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new LocalBuildException(
                        "Timed out after %s running: %s".formatted(timeout, String.join(" ", redact(command))));
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new LocalBuildException("Interrupted while running: " + String.join(" ", redact(command)), e);
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new LocalBuildException("%s exited with %d%n%s"
                    .formatted(redact(command).get(0), exitCode, tail.joined()));
        }
    }

    private Process start(File workingDir, List<String> command) {
        try {
            return new ProcessBuilder(command)
                    .directory(workingDir)
                    // Merged so the log shows failures in the order they actually happened —
                    // docker and gcloud both write progress to stderr.
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException e) {
            throw new LocalBuildException(
                    "Could not start '%s'. Is it installed and on PATH?".formatted(command.get(0)), e);
        }
    }

    /**
     * On Windows the Google Cloud SDK ships {@code gcloud} as a batch script, which
     * ProcessBuilder cannot execute directly — it needs the {@code .cmd} it actually is. Docker
     * ships a real executable and needs no such help.
     */
    private List<String> resolveCommand(String... command) {
        List<String> resolved = new ArrayList<>(List.of(command));
        if (WINDOWS && "gcloud".equals(resolved.get(0))) {
            resolved.set(0, "gcloud.cmd");
        }
        return resolved;
    }

    /** Keeps a clone URL's embedded token out of the logs. */
    private List<String> redact(List<String> command) {
        return command.stream()
                .map(argument -> argument.replaceAll("://[^@/\\s]+@", "://***@"))
                .toList();
    }

    /** Bounded ring of the most recent output lines. */
    private static final class Tail {
        private final ArrayDeque<String> lines;
        private final int limit;

        Tail(int limit) {
            this.limit = limit;
            this.lines = new ArrayDeque<>(limit);
        }

        void add(String line) {
            if (lines.size() == limit) {
                lines.removeFirst();
            }
            lines.addLast(line);
        }

        String joined() {
            return String.join(System.lineSeparator(), lines);
        }
    }
}
