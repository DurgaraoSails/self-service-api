package com.sails.ai.selfserviceapi.deploypipeline.manifest;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves {@code ${...}} placeholders inside a manifest's {@code env:} values, so a POC receives
 * platform-injected values under the env var names its own code already reads.
 *
 * <p>This exists to remove the last reason a working POC has to be edited before it can be hosted.
 * Every value the platform supplies arrives under a name the platform chose — {@code PORT},
 * {@code SVC_<NAME>_URL}, {@code POC_SLUG} — so adopting them meant a source change whose only
 * purpose was launchability. A placeholder lets the author write their own name and have the
 * platform fill it in: {@code SERVER_PORT: ${self.port}}.
 *
 * <p>Placeholders <em>read</em> platform values; they never set them. The reserved names and
 * prefixes {@link ManifestValidator} enforces are unchanged, and {@code SVC_<NAME>_URL} and a
 * sidecar's injected {@code PORT} are still emitted exactly as before — an alias is an addition,
 * never a replacement.
 *
 * <p>Two rules make this safe to add to a schema real repos are already written against:
 *
 * <ul>
 *   <li><b>Only a known root is a reference.</b> {@code ${amount}} or {@code ${HOME}} names no root
 *       this class knows, so it is left exactly as written — an existing manifest whose env value
 *       happens to contain {@code ${...}} keeps deploying with that text intact. Only the five roots
 *       in {@link #KNOWN_ROOTS} claim a placeholder, which is what makes an unresolvable one a
 *       reportable mistake rather than a guess about the author's intent.
 *   <li><b>A known root that does not resolve is left alone here and rejected there.</b>
 *       {@link ManifestValidator} reports it before anything is cloned, so a typo like
 *       {@code ${services.backedn.url}} never reaches a container to fail later as a connection
 *       error naming a host nobody wrote. This class leaves it verbatim rather than throwing,
 *       because it also runs on the redeploy path, where a stored manifest is re-parsed without
 *       revalidation and must deploy exactly as it did when it was built.
 * </ul>
 */
public final class EnvPlaceholders {

    /**
     * The roots that claim a {@code ${...}}. Anything else is ordinary text — see the class javadoc
     * for why that distinction is what keeps this backward compatible.
     */
    private static final Set<String> KNOWN_ROOTS = Set.of("self", "services", "poc", "platform", "portal");

    private EnvPlaceholders() {
    }

    /** Substitutes every resolvable reference; everything else is returned untouched. */
    public static String resolve(String value, PlatformEnvContext context) {
        return scan(value, context, null);
    }

    /**
     * The references in this value that name a known root but resolve to nothing — a misspelled
     * container, an unknown property, a root used with the wrong number of segments. Reported by
     * {@link ManifestValidator} alongside every other violation rather than thrown, so an author
     * fixing three things learns about all three in one pass.
     */
    public static List<String> unresolvableReferences(String value, PlatformEnvContext context) {
        List<String> unresolvable = new ArrayList<>();
        scan(value, context, unresolvable);
        return unresolvable;
    }

    /**
     * One scanner for both entry points, so "what counts as a reference" cannot mean two things.
     *
     * <p>Hand-scanned rather than regex-replaced because of the escape: <code>$${</code> must
     * consume its first dollar sign and emit a literal <code>${</code> without the rest being
     * re-examined, which a single pattern replacement cannot express without also matching the text
     * it just produced. The doubled-dollar spelling matches the convention
     * {@code BuildService.cloneStep} already uses for Cloud Build's own substitutions.
     *
     * @param unresolvable collects known-root references that resolve to nothing, or null when only
     *                     the substituted result is wanted
     */
    private static String scan(String value, PlatformEnvContext context, List<String> unresolvable) {
        if (value == null || value.indexOf('$') < 0) {
            return value;
        }

        StringBuilder out = new StringBuilder(value.length());
        int i = 0;
        while (i < value.length()) {
            char c = value.charAt(i);
            if (c != '$' || i + 1 >= value.length()) {
                out.append(c);
                i++;
                continue;
            }
            // $${ is an escaped literal ${ — the author wants the text, not a substitution.
            if (value.charAt(i + 1) == '$' && i + 2 < value.length() && value.charAt(i + 2) == '{') {
                out.append("${");
                i += 3;
                continue;
            }
            if (value.charAt(i + 1) != '{') {
                out.append(c);
                i++;
                continue;
            }
            int close = value.indexOf('}', i + 2);
            if (close < 0) {
                // Unterminated: ordinary text, not a half-written placeholder to complain about.
                out.append(c);
                i++;
                continue;
            }

            String expression = value.substring(i + 2, close);
            if (!isKnownRoot(expression)) {
                out.append(value, i, close + 1);
                i = close + 1;
                continue;
            }

            Optional<String> resolved = context.lookup(expression);
            if (resolved.isPresent()) {
                out.append(resolved.get());
            } else {
                out.append(value, i, close + 1);
                if (unresolvable != null) {
                    unresolvable.add(expression);
                }
            }
            i = close + 1;
        }
        return out.toString();
    }

    private static boolean isKnownRoot(String expression) {
        int dot = expression.indexOf('.');
        return KNOWN_ROOTS.contains(dot < 0 ? expression : expression.substring(0, dot));
    }
}
