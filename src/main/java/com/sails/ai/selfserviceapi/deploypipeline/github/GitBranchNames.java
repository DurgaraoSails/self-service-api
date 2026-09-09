package com.sails.ai.selfserviceapi.deploypipeline.github;

/**
 * Whether a string is safe to use as a branch name in this pipeline. A branch name reaches GitHub
 * as part of a URI path ({@code GitHubService.getBranchHeadSha} concatenates it into the template
 * so a slashed name survives instead of being encoded to {@code %2F}), so "is this a branch git
 * would accept" and "is this safe in that position" have to be the same question.
 *
 * <p>A subset of {@code git check-ref-format}, plus one rule of our own: braces are rejected
 * because the concatenated name sits inside a Spring URI template, where {@code {x}} would be read
 * as a variable. Git itself allows a bare brace in a ref name; nobody uses one, and permitting it
 * here would trade a real hazard for nothing.
 *
 * <p>Validated wherever a branch name enters the system — {@code PipelineProperties} at startup for
 * the platform-wide pin, and the POC create/update path for a POC's own branch — rather than at the
 * point of use, so a bad value is refused by whoever supplied it.
 */
public final class GitBranchNames {

    private static final String FORBIDDEN_CHARS = "~^:?*[\\{} ";

    private GitBranchNames() {
    }

    public static boolean isValid(String branch) {
        if (branch == null || branch.isBlank()) {
            return false;
        }
        for (int i = 0; i < branch.length(); i++) {
            char c = branch.charAt(i);
            if (c <= 0x20 || c == 0x7F || FORBIDDEN_CHARS.indexOf(c) >= 0) {
                return false;
            }
        }
        // ".." would climb out of /repos/{owner}/{repo} in the URI it is concatenated into; the
        // rest are git's own rules, which a real branch could never break anyway.
        return !branch.contains("..")
                && !branch.contains("//")
                && !branch.contains("@{")
                && !branch.startsWith("/") && !branch.endsWith("/")
                && !branch.startsWith(".") && !branch.endsWith(".")
                && !branch.endsWith(".lock");
    }

    /**
     * @param setting what to name in the message — the property or field the value came from, so
     *                the reader knows where to go and fix it.
     */
    public static void requireValid(String branch, String setting) {
        if (!isValid(branch)) {
            throw new IllegalArgumentException(setting + " is not a usable git branch name: '" + branch
                    + "'. Expected something like 'main' or 'release/2024' — no spaces, no '..', and none of "
                    + "the characters git forbids in a ref name.");
        }
    }
}
