package com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small, deliberately non-executing shell tokenizer — the only safe way to learn what a
 * cloudbuild.yaml {@code script:}/{@code bash -c "..."} step does is to statically parse it, never
 * to run untrusted repository content.
 *
 * <p>Covers exactly what a build script realistically contains: quoting, backslash-newline
 * continuation, {@code ;}/{@code &&}/{@code ||}/newline as statement separators, a trailing
 * {@code |…} pipeline dropped down to its first command, {@code set}/{@code echo} skipped as noise,
 * and simple {@code NAME=value}/{@code export NAME=value} assignments tracked so a later
 * {@code $NAME} in the same script resolves. It is not a shell — control flow ({@code if}, loops,
 * command substitution beyond simple {@code $VAR}/{@code ${VAR}}) is left as literal text, which is
 * the same "unresolved token" treatment Cloud Build's own unresolved substitutions get.
 */
public final class ShellWords {

    private ShellWords() {
    }

    /**
     * @param commands    every non-assignment, non-noise statement, tokenized into words with
     *                    quoting resolved and known variables substituted.
     * @param assignments every {@code NAME=value}/{@code export NAME=value} assignment seen, in
     *                    order — the running variable environment by the end of the script.
     */
    public record ParsedScript(List<List<String>> commands, Map<String, String> assignments) {
    }

    public static ParsedScript parse(String script, Map<String, String> initialEnv) {
        Map<String, String> env = new LinkedHashMap<>(initialEnv == null ? Map.of() : initialEnv);
        List<List<String>> commands = new ArrayList<>();

        for (String statement : splitStatements(joinContinuations(script))) {
            String trimmed = statement.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String beforePipe = truncateAtUnquotedPipe(trimmed);
            List<String> words = tokenize(beforePipe, env);
            if (words.isEmpty()) {
                continue;
            }

            String assignment = asAssignment(words);
            if (assignment != null) {
                int eq = assignment.indexOf('=');
                env.put(assignment.substring(0, eq), assignment.substring(eq + 1));
                continue;
            }

            String first = words.get(0);
            if (first.equals("set") || first.equals("echo")) {
                continue;
            }
            commands.add(words);
        }
        return new ParsedScript(commands, env);
    }

    /** {@code word} alone is an assignment; {@code export word} is too, with the keyword stripped. */
    private static String asAssignment(List<String> words) {
        if (words.size() == 1 && isAssignmentShaped(words.get(0))) {
            return words.get(0);
        }
        if (words.size() == 2 && words.get(0).equals("export") && isAssignmentShaped(words.get(1))) {
            return words.get(1);
        }
        return null;
    }

    private static boolean isAssignmentShaped(String word) {
        int eq = word.indexOf('=');
        if (eq <= 0) {
            return false;
        }
        String name = word.substring(0, eq);
        return name.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_') && Character.isJavaIdentifierStart(name.charAt(0));
    }

    /** A line ending in {@code \} continues onto the next; the backslash and newline are removed. */
    private static String joinContinuations(String script) {
        String[] lines = script.replace("\r\n", "\n").split("\n", -1);
        StringBuilder out = new StringBuilder();
        StringBuilder buffer = new StringBuilder();
        for (String line : lines) {
            String stripped = stripTrailingWhitespace(line);
            if (stripped.endsWith("\\") && !stripped.endsWith("\\\\")) {
                buffer.append(stripped, 0, stripped.length() - 1).append(' ');
            } else {
                buffer.append(stripped);
                out.append(buffer).append('\n');
                buffer.setLength(0);
            }
        }
        if (!buffer.isEmpty()) {
            out.append(buffer);
        }
        return out.toString();
    }

    private static String stripTrailingWhitespace(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }

    /** Splits on top-level (unquoted) {@code ;}, {@code &&}, {@code ||} and newline. */
    private static List<String> splitStatements(String text) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == quote && !escapedAt(text, i)) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                current.append(c);
                continue;
            }
            if (c == '\\' && i + 1 < text.length()) {
                current.append(c).append(text.charAt(++i));
                continue;
            }
            if (c == '\n' || c == ';') {
                statements.add(current.toString());
                current.setLength(0);
                continue;
            }
            if (c == '&' && i + 1 < text.length() && text.charAt(i + 1) == '&') {
                statements.add(current.toString());
                current.setLength(0);
                i++;
                continue;
            }
            if (c == '|' && i + 1 < text.length() && text.charAt(i + 1) == '|') {
                statements.add(current.toString());
                current.setLength(0);
                i++;
                continue;
            }
            current.append(c);
        }
        if (!current.isEmpty()) {
            statements.add(current.toString());
        }
        return statements;
    }

    /** A single {@code |} (not {@code ||}, already split above) starts a pipeline — only its first command matters here. */
    private static String truncateAtUnquotedPipe(String statement) {
        char quote = 0;
        for (int i = 0; i < statement.length(); i++) {
            char c = statement.charAt(i);
            if (quote != 0) {
                if (c == quote && !escapedAt(statement, i)) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '\\' && i + 1 < statement.length()) {
                i++;
            } else if (c == '|') {
                return statement.substring(0, i);
            }
        }
        return statement;
    }

    private static boolean escapedAt(String text, int index) {
        int backslashes = 0;
        for (int i = index - 1; i >= 0 && text.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    private static List<String> tokenize(String statement, Map<String, String> env) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean inWord = false;
        int i = 0;
        int n = statement.length();
        while (i < n) {
            char c = statement.charAt(i);
            if (Character.isWhitespace(c)) {
                if (inWord) {
                    words.add(word.toString());
                    word.setLength(0);
                    inWord = false;
                }
                i++;
                continue;
            }
            inWord = true;
            if (c == '\'') {
                int end = statement.indexOf('\'', i + 1);
                if (end < 0) {
                    end = n;
                }
                word.append(statement, i + 1, end);
                i = end + 1;
            } else if (c == '"') {
                i = consumeDoubleQuoted(statement, i + 1, word, env);
            } else if (c == '\\' && i + 1 < n) {
                word.append(statement.charAt(i + 1));
                i += 2;
            } else if (c == '$') {
                i = consumeVariable(statement, i, word, env);
            } else {
                word.append(c);
                i++;
            }
        }
        if (inWord) {
            words.add(word.toString());
        }
        return words;
    }

    private static int consumeDoubleQuoted(String s, int start, StringBuilder out, Map<String, String> env) {
        int i = start;
        while (i < s.length() && s.charAt(i) != '"') {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && "\"\\$`".indexOf(s.charAt(i + 1)) >= 0) {
                out.append(s.charAt(i + 1));
                i += 2;
            } else if (c == '$') {
                i = consumeVariable(s, i, out, env);
            } else {
                out.append(c);
                i++;
            }
        }
        return i + 1;
    }

    /** {@code $NAME} or {@code ${NAME}} resolved against known assignments; left literal (an unresolved token) otherwise. */
    private static int consumeVariable(String s, int dollarIndex, StringBuilder out, Map<String, String> env) {
        int i = dollarIndex + 1;
        if (i < s.length() && s.charAt(i) == '{') {
            int end = s.indexOf('}', i);
            if (end < 0) {
                out.append(s, dollarIndex, s.length());
                return s.length();
            }
            String name = s.substring(i + 1, end);
            appendResolved(out, name, s.substring(dollarIndex, end + 1), env);
            return end + 1;
        }
        int start = i;
        while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_')) {
            i++;
        }
        if (i == start) {
            out.append('$');
            return dollarIndex + 1;
        }
        String name = s.substring(start, i);
        appendResolved(out, name, s.substring(dollarIndex, i), env);
        return i;
    }

    private static void appendResolved(StringBuilder out, String name, String literalForm, Map<String, String> env) {
        String value = env.get(name);
        out.append(value != null ? value : literalForm);
    }
}
