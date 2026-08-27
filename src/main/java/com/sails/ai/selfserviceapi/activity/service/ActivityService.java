package com.sails.ai.selfserviceapi.activity.service;

import com.sails.ai.selfserviceapi.activity.entity.ActivitySession;
import com.sails.ai.selfserviceapi.activity.repository.ActivitySessionRepository;
import com.sails.ai.selfserviceapi.activity.repository.DailyUsageProjection;
import com.sails.ai.selfserviceapi.activity.repository.OpenSessionProjection;
import com.sails.ai.selfserviceapi.activity.repository.PocUsageProjection;
import com.sails.ai.selfserviceapi.activity.repository.SessionProjection;
import com.sails.ai.selfserviceapi.activity.repository.UserUsageProjection;
import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.poc.service.PocService;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.service.UserService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ActivityService {

    /**
     * How long after the last heartbeat a session is still considered open. Must comfortably
     * exceed the portal's heartbeat interval so a single dropped or delayed ping doesn't split
     * one continuous session in two. Package-private (not private) so
     * {@link ActivityResponseMapper} can use the same threshold to compute a session's display
     * status without duplicating the constant.
     */
    static final Duration GRACE_PERIOD = Duration.ofSeconds(90);

    /**
     * Upper bound on the time a single heartbeat can credit. Without it, a laptop suspended
     * mid-session (or a clock jump) would bank the entire gap as active usage the moment the
     * next ping lands within the grace period.
     */
    private static final long MAX_TICK_SECONDS = 120;

    private final ActivitySessionRepository activitySessionRepository;
    private final PocService pocService;
    private final UserService userService;

    public ActivityService(ActivitySessionRepository activitySessionRepository, PocService pocService, UserService userService) {
        this.activitySessionRepository = activitySessionRepository;
        this.pocService = pocService;
        this.userService = userService;
    }

    /**
     * Extends the caller's open session for this POC, or starts a new one. A gap longer than
     * {@link #GRACE_PERIOD} closes the previous session at its last confirmed heartbeat rather
     * than counting the whole gap as usage — so an abandoned tab stops accruing time on its own,
     * with no explicit "session end" call and no reconciliation job.
     */
    @Transactional
    public void recordHeartbeat(String userId, Long pocId) {
        pocService.getById(pocId);

        Instant now = Instant.now();
        Optional<ActivitySession> openSession =
                activitySessionRepository.findFirstByUserIdAndPocIdAndEndedAtIsNullOrderByLastSeenAtDesc(userId, pocId);

        if (openSession.isPresent()) {
            ActivitySession session = openSession.get();
            long gapSeconds = Duration.between(session.getLastSeenAt(), now).getSeconds();

            if (gapSeconds <= GRACE_PERIOD.getSeconds()) {
                session.setTotalSeconds(session.getTotalSeconds() + Math.clamp(gapSeconds, 0, MAX_TICK_SECONDS));
                session.setLastSeenAt(now);
                activitySessionRepository.save(session);
                return;
            }

            session.setEndedAt(session.getLastSeenAt());
            activitySessionRepository.save(session);
        }

        ActivitySession session = new ActivitySession();
        session.setUserId(userId);
        session.setPocId(pocId);
        session.setStartedAt(now);
        session.setLastSeenAt(now);
        activitySessionRepository.save(session);
    }

    public List<PocUsageProjection> getPocLeaderboard() {
        return activitySessionRepository.findPocUsage();
    }

    public List<UserUsageProjection> getUserLeaderboard() {
        return activitySessionRepository.findUserUsage();
    }

    public User getUser(String userId) {
        return userService.getById(userId);
    }

    public List<PocUsageProjection> getPocUsageForUser(String userId) {
        return activitySessionRepository.findPocUsageByUserId(userId);
    }

    public List<SessionProjection> getSessionsForUser(String userId) {
        return activitySessionRepository.findSessionsByUserId(userId);
    }

    public List<DailyUsageProjection> getDailyUsage(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE", "'from' must not be after 'to'.");
        }
        return activitySessionRepository.findDailyUsage(from, to);
    }

    /**
     * {@link ActivitySessionRepository#findOpenSessions()} returns every session with no
     * {@code endedAt}, which includes sessions nobody has extended past the grace period —
     * abandoned tabs that just haven't been observed as closed yet (see the {@code status} logic
     * in {@link ActivityResponseMapper}). Filtering here is what makes this genuinely "active
     * now" rather than "never explicitly closed".
     */
    public List<OpenSessionProjection> getActiveSessions() {
        Instant now = Instant.now();
        return activitySessionRepository.findOpenSessions().stream()
                .filter(s -> Duration.between(s.getLastSeenAt(), now).compareTo(GRACE_PERIOD) <= 0)
                .toList();
    }
}
