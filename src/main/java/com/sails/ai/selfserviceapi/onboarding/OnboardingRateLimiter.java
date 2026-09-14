package com.sails.ai.selfserviceapi.onboarding;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Caps how often one user can run each onboarding operation.
 *
 * <p>A check makes several GitHub calls — the repository, the branch head, poc.yaml, and one per
 * declared Dockerfile — all against the single shared platform token. A generation call makes all
 * of those plus a model call, which is far more expensive again. Left uncapped, one person holding
 * down a button can spend the whole installation's GitHub rate limit, or its LLM budget, which is a
 * much worse failure than being asked to wait — so each {@link Purpose} gets its own limit, rather
 * than one shared bucket a cheap check and an expensive generation would draw from equally.
 *
 * <p>Deliberately in memory, and therefore per instance: with several instances running, the
 * effective limit is this number times the instance count. That is a real weakness and is accepted
 * here rather than hidden — it still bounds a single client by a small constant, and the
 * alternative is a shared store this application does not otherwise need. Move it to the database
 * or a cache alongside the first other endpoint that needs limiting, not before.
 */
@Component
public class OnboardingRateLimiter {

    static final int MAX_CHECKS_PER_WINDOW = 10;
    static final Duration CHECK_WINDOW = Duration.ofMinutes(1);

    /** An LLM call is far more expensive than a GitHub read — a much stricter budget. */
    static final int MAX_GENERATIONS_PER_WINDOW = 3;
    static final Duration GENERATE_WINDOW = Duration.ofMinutes(5);

    public enum Purpose {
        CHECK, GENERATE;

        Duration window() {
            return this == CHECK ? CHECK_WINDOW : GENERATE_WINDOW;
        }

        int maxPerWindow() {
            return this == CHECK ? MAX_CHECKS_PER_WINDOW : MAX_GENERATIONS_PER_WINDOW;
        }
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /** True when this call is allowed. Records the attempt as a side effect when it is. */
    public boolean tryAcquire(String userId, Purpose purpose) {
        Instant now = Instant.now();
        Duration window = purpose.window();
        evictExpired(now);
        String key = purpose.name() + ":" + userId;
        Window updated = windows.compute(key, (k, existing) -> {
            if (existing == null || existing.startedBefore(now.minus(window))) {
                return new Window(now, 1);
            }
            return new Window(existing.startedAt(), existing.count() + 1);
        });
        return updated.count() <= purpose.maxPerWindow();
    }

    /**
     * Drops windows that have fully expired. Called on each acquire rather than on a schedule: the
     * map is keyed by purpose and user, so it only grows with distinct (purpose, user) pairs that
     * were actually used, and sweeping it inline avoids a scheduled task for a map that is normally
     * tiny. The longest window across every purpose is used as the cutoff, since a per-purpose
     * cutoff would need the purpose back out of the key — simpler to check against the widest one
     * and let a slightly-early sweep of a shorter window cost nothing but a re-added entry.
     */
    void evictExpired(Instant now) {
        Duration widest = CHECK_WINDOW.compareTo(GENERATE_WINDOW) > 0 ? CHECK_WINDOW : GENERATE_WINDOW;
        windows.entrySet().removeIf(entry -> entry.getValue().startedBefore(now.minus(widest)));
    }

    private record Window(Instant startedAt, int count) {

        boolean startedBefore(Instant cutoff) {
            return startedAt.isBefore(cutoff);
        }
    }
}
