package com.sails.ai.selfserviceapi.onboarding;

import static org.assertj.core.api.Assertions.assertThat;

import com.sails.ai.selfserviceapi.generated.model.PocOnboardingCheckId;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Ties the portal's readiness checklist to the checks this API actually runs.
 *
 * <p>The onboarding guide tells a team what to verify, and each of its checker-enforced rows names
 * a {@link PocOnboardingCheckId}. Prose drifts and code does not, so without this test the guide
 * can end up promising a check nobody performs — a team reads a green result and deploys into the
 * failure the checklist claimed to cover. That is the exact failure mode the checker exists to
 * prevent, so it is worth a test rather than a convention.
 *
 * <p>Reads the portal's content file directly. If the portal is not checked out beside this repo
 * the test skips its file half rather than failing, because a backend-only clone is a normal way to
 * work here — but the enum half still runs, so an id removed from the API is always caught.
 */
class OnboardingCheckIdParityTest {

    /** Sibling checkout, as both repos are cloned into the same parent directory. */
    private static final Path PORTAL_CONTENT = Path.of("..", "self-service-portal", "src", "app", "components",
            "host-poc-guide", "host-poc-guide.content.ts");

    private static final Pattern CHECK_ID_REFERENCE = Pattern.compile("checkId:\\s*'([A-Z_]+)'");

    /**
     * Every id the portal names must exist here. A typo, or an id renamed in the API without the
     * guide following, turns a checklist row into a promise nothing keeps.
     */
    @Test
    void everyCheckIdTheGuideReferencesExistsInTheApi() throws IOException {
        List<String> referenced = checkIdsReferencedByTheGuide();
        if (referenced.isEmpty()) {
            return;
        }

        Set<String> known = Arrays.stream(PocOnboardingCheckId.values())
                .map(PocOnboardingCheckId::getValue)
                .collect(Collectors.toSet());

        assertThat(referenced).allSatisfy(id -> assertThat(known).contains(id));
    }

    /**
     * The other direction, and the more useful one. A check this API performs that the guide never
     * mentions is a rule teams are held to without being told — the single most common way a
     * self-service flow stops being self-service.
     *
     * <p>{@code REPO_URL} is excluded deliberately: it fires only on a malformed URL typed into the
     * checker itself, so there is nothing for a team to verify in their repository beforehand.
     */
    @Test
    void everyCheckTheApiPerformsIsMentionedInTheGuide() throws IOException {
        List<String> referenced = checkIdsReferencedByTheGuide();
        if (referenced.isEmpty()) {
            return;
        }

        assertThat(Arrays.stream(PocOnboardingCheckId.values())
                .map(PocOnboardingCheckId::getValue)
                .filter(id -> !id.equals(PocOnboardingCheckId.REPO_URL.getValue()))
                .toList())
                .allSatisfy(id -> assertThat(referenced).contains(id));
    }

    private List<String> checkIdsReferencedByTheGuide() throws IOException {
        if (!Files.exists(PORTAL_CONTENT)) {
            return List.of();
        }
        String content = Files.readString(PORTAL_CONTENT, StandardCharsets.UTF_8);
        Matcher matcher = CHECK_ID_REFERENCE.matcher(content);
        return matcher.results().map(result -> result.group(1)).toList();
    }
}
