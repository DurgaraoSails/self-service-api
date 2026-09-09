package com.sails.ai.selfserviceapi.onboarding;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Caps how often one user can run the onboarding checker.
 *
 * <p>Each check makes several GitHub calls — the repository, the branch head, poc.yaml, and one
 * per declared Dockerfile — all against the single shared platform token. Left uncapped, one
 * person holding down a button can spend the whole installation's GitHub rate limit and break
 * every deploy, which is a much worse failure than being asked to wait.
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
    static final Duration WINDOW = Duration.ofMinutes(1);

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    /** True when this check is allowed. Records the attempt as a side effect when it is. */
    public boolean tryAcquire(String userId) {
        Instant now = Instant.now();
        evictExpired(now);
        Window updated = windows.compute(userId, (key, existing) -> {
            if (existing == null || existing.startedBefore(now.minus(WINDOW))) {
                return new Window(now, 1);
            }
            return new Window(existing.startedAt(), existing.count() + 1);
        });
        return updated.count() <= MAX_CHECKS_PER_WINDOW;
    }

    /**
     * Drops windows that have fully expired. Called on each acquire rather than on a schedule: the
     * map is keyed by user, so it only grows with distinct users who ran a check, and sweeping it
     * inline avoids a scheduled task for a map that is normally tiny.
     */
    void evictExpired(Instant now) {
        windows.entrySet().removeIf(entry -> entry.getValue().startedBefore(now.minus(WINDOW)));
    }

    private record Window(Instant startedAt, int count) {

        boolean startedBefore(Instant cutoff) {
            return startedAt.isBefore(cutoff);
        }
    }
}
