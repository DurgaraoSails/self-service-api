package com.sails.ai.selfserviceapi.onboarding.generate.secret;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redacts credential-shaped content out of evidence text before it ever reaches a prompt — the
 * privacy rule this whole feature exists to keep: a secret value read from the repo must never
 * appear in a prompt, a log line, a notice, an import summary or any response field.
 *
 * <p>Two passes, deliberately in this order: known token *shapes* (PEM blocks, JWTs, prefixed
 * tokens) are redacted wherever they appear, not only inside a {@code KEY=value} line — a secret
 * pasted into a comment or a curl example in a README is exactly as real a leak. Line-based
 * {@code KEY: value}/{@code KEY=value} redaction then catches everything else the heuristics judge
 * secret-shaped by name or value.
 */
public final class EvidenceRedactor {

    private EvidenceRedactor() {
    }

    private static final Pattern PEM_BLOCK = Pattern.compile(
            "-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----");

    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");

    private static final Pattern PREFIXED_TOKEN = Pattern.compile(
            "\\b(ghp_[A-Za-z0-9]+|gho_[A-Za-z0-9]+|github_pat_[A-Za-z0-9_]+|glpat-[A-Za-z0-9_-]+|"
                    + "xox[a-zA-Z]-[A-Za-z0-9-]+|sk-[A-Za-z0-9]+|sk_live_[A-Za-z0-9]+|AIza[A-Za-z0-9_-]+|"
                    + "AKIA[A-Z0-9]+|xkeysib-[A-Za-z0-9]+)\\b");

    /** A key at the start of a line, optionally quoted, then {@code :} or {@code =}, then the rest of the line as value. */
    private static final Pattern KEY_VALUE_LINE =
            Pattern.compile("^(\\s*[\"']?)([A-Za-z_][A-Za-z0-9_.-]*)([\"']?\\s*[:=]\\s*)(.*)$");

    public static String redact(String content) {
        if (content == null) {
            return null;
        }
        String text = PEM_BLOCK.matcher(content).replaceAll("<redacted:pem>");
        text = JWT.matcher(text).replaceAll("<redacted:jwt>");
        text = PREFIXED_TOKEN.matcher(text).replaceAll("<redacted:token>");

        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < lines.length; i++) {
            out.append(redactLine(lines[i]));
            if (i < lines.length - 1) {
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static String redactLine(String line) {
        Matcher m = KEY_VALUE_LINE.matcher(line);
        if (!m.matches()) {
            return line;
        }
        String key = m.group(2);
        String value = m.group(4).trim();
        if (value.startsWith("<redacted")) {
            // Already redacted by the token-shape pass above — redacting the placeholder itself
            // would just replace one marker with another.
            return line;
        }
        if (SecretHeuristics.isSecret(key, value)) {
            return m.group(1) + key + m.group(3) + "<redacted>";
        }
        return line;
    }
}
