package com.sails.ai.selfserviceapi.onboarding.generate.secret;

import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestProperties;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestRequirement;
import com.sails.ai.selfserviceapi.onboarding.generate.GenerationNotice;
import com.sails.ai.selfserviceapi.onboarding.generate.GenerationNoticeCode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Turns a raw name/value environment map (imported from cloudbuild.yaml, or drafted by a model)
 * into what a manifest is actually allowed to carry — {@code env:} literals, {@code requires:}
 * secrets, and at most one {@code PORT} value — applying the platform's own reserved-name rules
 * from {@link ManifestProperties} rather than restating them.
 *
 * <p>Rules apply in order, first match wins, matching the platform's own priority: a reserved name
 * is reserved even if its value also looks like a secret; a secret name/value is declared under
 * {@code requires:} even if it also contains an unresolved substitution.
 */
@Component
public class EnvVarClassifier {

    private static final Pattern VALID_ENV_NAME = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");
    private static final Pattern NUMERIC = Pattern.compile("^\\d+$");
    /** A Cloud Build user-defined substitution ($_X / ${_X}) or a $(...) command substitution that never resolved. */
    private static final Pattern UNRESOLVED_SUBSTITUTION = Pattern.compile("\\$\\{?_[A-Za-z0-9_]+\\}?|\\$\\([^)]*\\)");

    private final ManifestProperties manifestProperties;

    public EnvVarClassifier(ManifestProperties manifestProperties) {
        this.manifestProperties = manifestProperties;
    }

    public record Result(Map<String, String> env, List<ManifestRequirement> requires, Integer port,
                          List<GenerationNotice> notices) {
    }

    /**
     * @param hasPortAlready     whether this container already has a port — rule 2 only claims
     *                           {@code PORT}'s value when the container has none yet, so an
     *                           explicitly-declared port always wins over an imported env var.
     * @param serviceUrlsByName  other imported services' names mapped to their Cloud Run URL, for
     *                           rule 6's exact match. Pass an empty map when this is unknown (a
     *                           single-service import, or a model draft with no import behind it) —
     *                           a real Cloud Run URL is only assigned at deploy time, so this is
     *                           rarely populated.
     * @param otherServiceNames  other declared containers'/services' names and image basenames
     *                           (lowercased), mapped to the canonical container name to reference —
     *                           for rule 6's heuristic fallback: an {@code https://} value whose
     *                           host merely *contains* one of these as a substring is rewritten the
     *                           same way, but flagged as a guess rather than a confirmed match (see
     *                           {@link com.sails.ai.selfserviceapi.onboarding.generate.GenerationNoticeCode#CROSS_SERVICE_URL_GUESSED}).
     *                           Pass an empty map to skip this fallback entirely.
     */
    public Result classify(Map<String, String> rawEnv, boolean hasPortAlready, Map<String, String> serviceUrlsByName,
                            Map<String, String> otherServiceNames) {
        Map<String, String> env = new LinkedHashMap<>();
        List<ManifestRequirement> requires = new ArrayList<>();
        List<GenerationNotice> notices = new ArrayList<>();
        Integer port = null;
        boolean portClaimed = hasPortAlready;

        if (rawEnv == null) {
            return new Result(env, requires, null, notices);
        }

        for (Map.Entry<String, String> entry : rawEnv.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue() == null ? "" : entry.getValue();

            if (name == null || !VALID_ENV_NAME.matcher(name).matches()) {
                notices.add(GenerationNotice.info(GenerationNoticeCode.INVALID_ENV_NAME,
                        "'" + name + "' is not a valid environment variable name — dropped."));
                continue;
            }

            if (name.equals("PORT")) {
                if (!portClaimed && NUMERIC.matcher(value.trim()).matches()) {
                    port = Integer.parseInt(value.trim());
                    portClaimed = true;
                }
                notices.add(GenerationNotice.info(GenerationNoticeCode.RESERVED_ENV_DROPPED,
                        "'PORT' is set by the platform, not by env: — read as this container's port instead."));
                continue;
            }

            if (isReserved(name)) {
                notices.add(GenerationNotice.info(GenerationNoticeCode.RESERVED_ENV_DROPPED,
                        "'" + name + "' is reserved by the platform and cannot be set from env:."));
                continue;
            }

            if (SecretHeuristics.isSecret(name, value)) {
                requires.add(new ManifestRequirement(name, true));
                notices.add(GenerationNotice.info(GenerationNoticeCode.SECRET_TO_PROVISION,
                        "'" + name + "' looks like a credential — declared under requires: instead of env:. "
                                + "Provision its value in Secret Manager before deploying."));
                if (!value.isBlank()) {
                    notices.add(GenerationNotice.warning(GenerationNoticeCode.SECRET_VALUE_DROPPED,
                            "'" + name + "' had a literal value in the source — rotate it, since it was committed "
                                    + "or configured in clear text."));
                }
                continue;
            }

            if (UNRESOLVED_SUBSTITUTION.matcher(value).find()) {
                notices.add(GenerationNotice.info(GenerationNoticeCode.SUBSTITUTION_UNRESOLVED,
                        "'" + name + "' referenced a build substitution that could not be resolved — dropped."));
                continue;
            }

            String asServiceReference = rewriteServiceUrl(value, name, serviceUrlsByName, otherServiceNames, notices);
            if (asServiceReference != null) {
                env.put(name, asServiceReference);
                continue;
            }

            // Anything else: a literal env value. A stray ${ is escaped so it can never be misread
            // as an unresolvable poc.yaml placeholder reference once this reaches ManifestValidator.
            env.put(name, value.replace("${", "$${"));
        }

        return new Result(env, requires, port, notices);
    }

    private boolean isReserved(String name) {
        if (manifestProperties.reservedEnvNames().contains(name)) {
            return true;
        }
        return manifestProperties.reservedEnvPrefixes().stream().anyMatch(name::startsWith);
    }

    private String rewriteServiceUrl(String value, String envName, Map<String, String> serviceUrlsByName,
                                      Map<String, String> otherServiceNames, List<GenerationNotice> notices) {
        if (value == null) {
            return null;
        }
        String trimmed = stripTrailingSlash(value.trim());

        if (serviceUrlsByName != null) {
            for (Map.Entry<String, String> service : serviceUrlsByName.entrySet()) {
                if (trimmed.equals(stripTrailingSlash(service.getValue()))) {
                    return "${services." + service.getKey() + ".url}";
                }
            }
        }

        // A confirmed Cloud Run URL is essentially never known statically (it is assigned at deploy
        // time) — this heuristic fallback is what actually fires in practice: an https:// value
        // whose host merely mentions another declared container's name is very likely that
        // container's URL, but it is a guess, not a parsed fact, so it is always flagged rather than
        // applied silently the way an exact match above is.
        if (otherServiceNames != null && !otherServiceNames.isEmpty() && looksLikeHttpUrl(trimmed)) {
            String lower = trimmed.toLowerCase(Locale.ROOT);
            for (Map.Entry<String, String> candidate : otherServiceNames.entrySet()) {
                if (!candidate.getKey().isBlank() && lower.contains(candidate.getKey())) {
                    notices.add(GenerationNotice.warning(GenerationNoticeCode.CROSS_SERVICE_URL_GUESSED,
                            "'" + envName + "' looked like it points at the '" + candidate.getValue() + "' container "
                                    + "declared in the same cloudbuild.yaml (its name appears in the URL) — rewritten "
                                    + "to ${services." + candidate.getValue() + ".url}. This is a guess from the URL "
                                    + "text, not a confirmed match — verify it before deploying."));
                    return "${services." + candidate.getValue() + ".url}";
                }
            }
        }
        return null;
    }

    private boolean looksLikeHttpUrl(String value) {
        return value.toLowerCase(Locale.ROOT).startsWith("http://") || value.toLowerCase(Locale.ROOT).startsWith("https://");
    }

    private String stripTrailingSlash(String value) {
        return value != null && value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
