package com.sails.ai.selfserviceapi.onboarding.generate.secret;

import java.util.regex.Pattern;

/**
 * Whether a name or a value looks like a credential. Heuristic and deliberately over-matching — a
 * false positive costs one extra "requires" entry or one redacted evidence line; a false negative
 * costs a leaked secret. Shared by {@code EvidenceRedactor} (free-text evidence), {@code EnvVarClassifier}
 * (cloudbuild/model env values) and the model draft's own secret-literal scan, so all three agree on
 * what counts as a secret.
 */
public final class SecretHeuristics {

    private SecretHeuristics() {
    }

    /** Env/requirement names this shape suggests are credentials, regardless of value. */
    private static final Pattern SECRET_NAME = Pattern.compile(
            "(?i)(PASSWORD|PASS|PWD|SECRET|TOKEN|API[_-]?KEY|ACCESS[_-]?KEY|PRIVATE[_-]?KEY|"
                    + "CLIENT[_-]?SECRET|CREDENTIALS?|SIGNING|SALT|PEPPER|DSN|CONNECTION[_-]?STRING)");

    /** Known provider token prefixes — GitHub, GitLab, Slack, OpenAI/Anthropic-style, Google API, AWS, Sendinblue/Brevo. */
    private static final Pattern KNOWN_TOKEN_PREFIX = Pattern.compile(
            "^(ghp_|gho_|github_pat_|glpat-|xox[a-zA-Z]-|sk-|sk_live_|AIza|AKIA|xkeysib-)");

    private static final Pattern PEM_HEADER = Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----");

    private static final Pattern JWT_SHAPE = Pattern.compile("^eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$");

    private static final Pattern URL_WITH_CREDENTIALS = Pattern.compile("://[^/\\s:@]+:[^/\\s:@]+@");

    /** A conservative floor for "random enough to be a secret": 24+ chars, no whitespace, letters and digits both present. */
    private static final Pattern HIGH_ENTROPY_SHAPE = Pattern.compile("^[A-Za-z0-9/+_.=-]{24,}$");

    /** {@code KEY: value} / {@code KEY=value} where the key or value already looks secret-shaped. */
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)(api[_-]?key|secret|password|passwd|token|access[_-]?key)\\s*[:=]\\s*['\"]?[A-Za-z0-9/+_.\\-]{12,}");

    public static boolean nameLooksSecret(String name) {
        return name != null && SECRET_NAME.matcher(name).find();
    }

    public static boolean valueLooksSecret(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        if (KNOWN_TOKEN_PREFIX.matcher(trimmed).find() || PEM_HEADER.matcher(trimmed).find()
                || JWT_SHAPE.matcher(trimmed).matches() || URL_WITH_CREDENTIALS.matcher(trimmed).find()) {
            return true;
        }
        return HIGH_ENTROPY_SHAPE.matcher(trimmed).matches() && hasLetterAndDigit(trimmed);
    }

    /** True when either the name or the value marks a variable as secret — the rule {@code EnvVarClassifier} applies. */
    public static boolean isSecret(String name, String value) {
        return nameLooksSecret(name) || valueLooksSecret(value);
    }

    /** A loose scan for a credential-shaped assignment anywhere in free text (evidence, a config file). */
    public static boolean containsSecretAssignment(String text) {
        return text != null && ASSIGNMENT.matcher(text).find();
    }

    private static boolean hasLetterAndDigit(String value) {
        boolean hasDigit = false;
        boolean hasLetter = false;
        for (int i = 0; i < value.length() && !(hasDigit && hasLetter); i++) {
            char c = value.charAt(i);
            hasDigit |= Character.isDigit(c);
            hasLetter |= Character.isLetter(c);
        }
        return hasDigit && hasLetter;
    }
}
