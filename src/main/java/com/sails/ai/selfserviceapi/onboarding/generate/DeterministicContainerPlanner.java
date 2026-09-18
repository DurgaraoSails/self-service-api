package com.sails.ai.selfserviceapi.onboarding.generate;

import com.sails.ai.selfserviceapi.deploypipeline.manifest.ContainerRole;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestRequirement;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.Resources;
import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.CloudBuildImport;
import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.ImportedContainer;
import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.ImportedService;
import com.sails.ai.selfserviceapi.onboarding.generate.stack.DetectedStack;
import com.sails.ai.selfserviceapi.onboarding.generate.stack.StackKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * A container plan with no model call — the fallback {@code PocManifestGenerationService} uses
 * when the configured draft model is unavailable. Handles only the two cases it can decide
 * confidently with no guessing: a single-component repo, or a cloudbuild.yaml import that already
 * fully determined every container. Anything else returns empty rather than a guessed plan a
 * degraded UNAVAILABLE result is more honest than.
 */
@Component
public class DeterministicContainerPlanner {

    public Optional<PocManifest> plan(Map<String, DetectedStack> stacksByDirectory, CloudBuildImport cloudBuildImport) {
        Optional<PocManifest> fromImport = planFromImport(stacksByDirectory, cloudBuildImport);
        if (fromImport.isPresent()) {
            return fromImport;
        }
        return planSingleComponent(stacksByDirectory);
    }

    /** Exactly one imported service whose every container already has a resolved Dockerfile, context and port. */
    private Optional<PocManifest> planFromImport(Map<String, DetectedStack> stacksByDirectory, CloudBuildImport cloudBuildImport) {
        if (cloudBuildImport == null || cloudBuildImport.services().size() != 1) {
            return Optional.empty();
        }
        ImportedService service = cloudBuildImport.services().get(0);
        List<ImportedContainer> containers = service.containers();
        if (containers == null || containers.isEmpty() || !containers.stream().allMatch(this::resolves)) {
            return Optional.empty();
        }

        int ingressIndex = chooseIngressIndex(containers, stacksByDirectory);
        List<ManifestContainer> manifestContainers = new ArrayList<>();
        for (int i = 0; i < containers.size(); i++) {
            ImportedContainer ic = containers.get(i);
            ContainerRole role = i == ingressIndex ? ContainerRole.INGRESS : ContainerRole.SIDECAR;
            List<ManifestRequirement> requires = ic.secretEnvNames() == null ? List.of()
                    : ic.secretEnvNames().stream().map(name -> new ManifestRequirement(name, true)).toList();
            Map<String, String> env = ic.env() == null ? Map.of() : new LinkedHashMap<>(ic.env());
            manifestContainers.add(new ManifestContainer(ic.name(), role, ic.dockerfile(), ic.context(),
                    ic.port(), env, ic.health(), null, requires));
        }

        Resources resources = new Resources(containers.get(ingressIndex).cpu(), containers.get(ingressIndex).memory());
        return Optional.of(new PocManifest(manifestContainers, resources));
    }

    /**
     * Prefers a container whose detected stack serves a browser frontend directly (see
     * {@code StackKind.isBrowserFacing}) over cloudbuild.yaml's own declaration order — but only
     * when exactly one container clearly qualifies. Falls back to the first declared container
     * (cloudbuild.yaml gives no explicit role signal, and teams overwhelmingly declare their primary
     * service first) whenever the signal is ambiguous: no browser-facing candidate, or more than
     * one, is left to that existing rule rather than guessed at.
     */
    private int chooseIngressIndex(List<ImportedContainer> containers, Map<String, DetectedStack> stacksByDirectory) {
        List<Integer> browserFacing = new ArrayList<>();
        for (int i = 0; i < containers.size(); i++) {
            DetectedStack stack = stacksByDirectory == null ? null : stacksByDirectory.get(containers.get(i).context());
            if (stack != null && stack.kind().isBrowserFacing()) {
                browserFacing.add(i);
            }
        }
        return browserFacing.size() == 1 ? browserFacing.get(0) : 0;
    }

    private boolean resolves(ImportedContainer container) {
        return container.dockerfile() != null && !container.dockerfile().isBlank()
                && container.context() != null && !container.context().isBlank();
    }

    /** A single recognized (or even unrecognized, if it's the only candidate) component at the repository root, and nothing else. */
    private Optional<PocManifest> planSingleComponent(Map<String, DetectedStack> stacksByDirectory) {
        List<Map.Entry<String, DetectedStack>> realComponents = stacksByDirectory.entrySet().stream()
                .filter(e -> e.getValue().kind() != StackKind.UNKNOWN)
                .toList();
        if (realComponents.size() != 1 || !realComponents.get(0).getKey().isEmpty()) {
            return Optional.empty();
        }
        ManifestContainer app = new ManifestContainer("app", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of());
        return Optional.of(new PocManifest(List.of(app), new Resources(null, null)));
    }
}
