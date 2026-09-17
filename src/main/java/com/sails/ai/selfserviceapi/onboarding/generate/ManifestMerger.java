package com.sails.ai.selfserviceapi.onboarding.generate;

import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestRequirement;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.Resources;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.Scaling;
import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.CloudBuildImport;
import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.ImportedContainer;
import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.ImportedService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/**
 * {@code ManifestDraftService}'s overlay — always applied, whether or not there is an import to
 * apply. Two jobs: normalize whatever the model drafted (rewrite a missing/blank Dockerfile path to
 * {@code <context>/Dockerfile}, cap the plan at 8 containers), and, when a {@link CloudBuildImport}
 * exists, override the model's own values with what the team's cloudbuild.yaml actually declared —
 * the deterministic facts win over the model's guess wherever both speak to the same setting.
 *
 * <p>Containers are matched by name first, then by {@code (dockerfile, context)} — a model that
 * named a container differently than the import's service/container name should still receive the
 * import's settings if it clearly drafted the same container. An imported container matching
 * neither is left unapplied rather than force-added with a guessed role; it is still visible to a
 * human through the response's separate {@code imports} field.
 */
@Component
public class ManifestMerger {

    private static final int MAX_CONTAINERS = 8;

    public record MergeResult(PocManifest manifest, List<GenerationNotice> notices) {
    }

    /** A pure function safe to call once per repair attempt — see {@code ManifestDraftService}'s overlay parameter. */
    public PocManifest applyOverlay(PocManifest draft, CloudBuildImport cloudBuildImport) {
        return merge(draft, cloudBuildImport).manifest();
    }

    public MergeResult merge(PocManifest draft, CloudBuildImport cloudBuildImport) {
        List<GenerationNotice> notices = new ArrayList<>();
        List<ImportedContainer> imported = importedContainers(cloudBuildImport);

        List<ManifestContainer> remaining = new ArrayList<>(draft.containers());
        List<ManifestContainer> merged = new ArrayList<>();
        for (ImportedContainer ic : imported) {
            ManifestContainer match = findAndRemoveMatch(remaining, ic);
            merged.add(match != null ? mergeOne(match, ic, notices) : null);
        }
        merged.removeIf(Objects::isNull);
        for (ManifestContainer leftover : remaining) {
            merged.add(normalizeDockerfilePath(leftover));
        }

        if (merged.size() > MAX_CONTAINERS) {
            notices.add(GenerationNotice.warning(GenerationNoticeCode.TOO_MANY_COMPONENTS,
                    "This repository has " + merged.size() + " containers; the platform allows at most "
                            + MAX_CONTAINERS + ". The extra ones were dropped — review which containers this "
                            + "POC actually needs."));
            merged = merged.subList(0, MAX_CONTAINERS);
        }

        Resources resources = mergedResources(draft.resources(), imported);
        Scaling scaling = mergedScaling(draft.scaling(), cloudBuildImport);
        return new MergeResult(new PocManifest(merged, resources, scaling, draft.platform()), notices);
    }

    private List<ImportedContainer> importedContainers(CloudBuildImport cloudBuildImport) {
        if (cloudBuildImport == null || cloudBuildImport.services().isEmpty()) {
            return List.of();
        }
        ImportedService service = cloudBuildImport.services().get(0);
        return service.containers() == null ? List.of() : service.containers();
    }

    private ManifestContainer findAndRemoveMatch(List<ManifestContainer> candidates, ImportedContainer imported) {
        for (ManifestContainer c : candidates) {
            if (imported.name() != null && imported.name().equals(c.name())) {
                candidates.remove(c);
                return c;
            }
        }
        if (imported.dockerfile() != null && imported.context() != null) {
            for (ManifestContainer c : candidates) {
                if (imported.dockerfile().equals(c.dockerfile()) && imported.context().equals(c.context())) {
                    candidates.remove(c);
                    return c;
                }
            }
        }
        return null;
    }

    private ManifestContainer mergeOne(ManifestContainer draftContainer, ImportedContainer imported, List<GenerationNotice> notices) {
        Integer port = overrideValue(draftContainer.port(), imported.port(), "port", draftContainer.name(), notices);
        String health = overrideValue(draftContainer.health(), imported.health(), "health", draftContainer.name(), notices);

        Map<String, String> env = new LinkedHashMap<>(draftContainer.env());
        if (imported.env() != null) {
            imported.env().forEach((key, value) -> {
                if (draftContainer.env().containsKey(key) && !Objects.equals(draftContainer.env().get(key), value)) {
                    notices.add(overriddenNotice("env." + key, draftContainer.name()));
                }
                env.put(key, value);
            });
        }

        List<ManifestRequirement> requires = new ArrayList<>(draftContainer.requires());
        if (imported.secretEnvNames() != null) {
            for (String secretName : imported.secretEnvNames()) {
                if (requires.stream().noneMatch(r -> r.name().equals(secretName))) {
                    requires.add(new ManifestRequirement(secretName, true));
                }
            }
        }

        ManifestContainer withOverrides = new ManifestContainer(draftContainer.name(), draftContainer.role(),
                draftContainer.dockerfile(), draftContainer.context(), port, env, health, null, requires);
        return normalizeDockerfilePath(withOverrides);
    }

    private <T> T overrideValue(T draftValue, T importedValue, String settingName, String containerName, List<GenerationNotice> notices) {
        if (importedValue == null) {
            return draftValue;
        }
        if (draftValue != null && !draftValue.equals(importedValue)) {
            notices.add(overriddenNotice(settingName, containerName));
        }
        return importedValue;
    }

    private GenerationNotice overriddenNotice(String setting, String container) {
        return GenerationNotice.info(GenerationNoticeCode.IMPORT_OVERRODE_DRAFT,
                "'" + setting + "' came from cloudbuild.yaml, overriding what the model drafted.")
                .withContainer(container);
    }

    private ManifestContainer normalizeDockerfilePath(ManifestContainer container) {
        if (container.dockerfile() != null && !container.dockerfile().isBlank()) {
            return container;
        }
        String context = container.context() == null || container.context().isBlank() ? "." : container.context();
        String dockerfile = context.equals(".") ? "Dockerfile" : context + "/Dockerfile";
        return new ManifestContainer(container.name(), container.role(), dockerfile, container.context(),
                container.port(), container.env(), container.health(), null, container.requires());
    }

    /** Resources apply to the whole service (the ingress container) — pulled from the ingress's imported settings, when present. */
    private Resources mergedResources(Resources draftResources, List<ImportedContainer> imported) {
        String cpu = draftResources == null ? null : draftResources.cpu();
        String memory = draftResources == null ? null : draftResources.memory();
        for (ImportedContainer ic : imported) {
            if (ic.cpu() != null) {
                cpu = ic.cpu();
            }
            if (ic.memory() != null) {
                memory = ic.memory();
            }
        }
        return new Resources(cpu, memory);
    }

    private Scaling mergedScaling(Scaling draftScaling, CloudBuildImport cloudBuildImport) {
        Integer min = draftScaling == null ? null : draftScaling.min();
        Integer max = draftScaling == null ? null : draftScaling.max();
        if (cloudBuildImport != null && !cloudBuildImport.services().isEmpty()) {
            ImportedService service = cloudBuildImport.services().get(0);
            if (service.minInstances() != null) {
                min = service.minInstances();
            }
            if (service.maxInstances() != null) {
                max = service.maxInstances();
            }
        }
        return new Scaling(min, max);
    }
}
