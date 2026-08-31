package com.sails.ai.selfserviceapi.deployment.service;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class VersionService {

    // Tags are bare semver, no "v" prefix — the same string is reused as the
    // Artifact Registry image tag later in the pipeline.
    private static final Pattern SEMVER_PATTERN = Pattern.compile("^(\\d+)\\.(\\d+)\\.(\\d+)$");

    public enum BumpType {
        MAJOR, MINOR, PATCH
    }

    public String computeNextVersion(List<String> existingTags) {
        return computeNextVersion(existingTags, BumpType.MINOR);
    }

    public String computeNextVersion(List<String> existingTags, BumpType bumpType) {
        SemVer latest = existingTags.stream()
                .map(SemVer::parse)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);

        if (latest == null) {
            return "1.0.0";
        }

        SemVer next = switch (bumpType) {
            case MAJOR -> new SemVer(latest.major() + 1, 0, 0);
            case MINOR -> new SemVer(latest.major(), latest.minor() + 1, 0);
            case PATCH -> new SemVer(latest.major(), latest.minor(), latest.patch() + 1);
        };
        return next.toString();
    }

    private record SemVer(int major, int minor, int patch) implements Comparable<SemVer> {

        static SemVer parse(String tag) {
            Matcher matcher = SEMVER_PATTERN.matcher(tag.trim());
            if (!matcher.matches()) {
                return null;
            }
            return new SemVer(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3)));
        }

        @Override
        public int compareTo(SemVer other) {
            int result = Integer.compare(major, other.major);
            if (result != 0) {
                return result;
            }
            result = Integer.compare(minor, other.minor);
            if (result != 0) {
                return result;
            }
            return Integer.compare(patch, other.patch);
        }

        @Override
        public String toString() {
            return major + "." + minor + "." + patch;
        }
    }
}
