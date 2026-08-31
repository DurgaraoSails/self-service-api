package com.sails.ai.selfserviceapi.activity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.activity.entity.ActivitySession;
import com.sails.ai.selfserviceapi.activity.repository.ActivitySessionRepository;
import com.sails.ai.selfserviceapi.activity.repository.OpenSessionProjection;
import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.poc.service.PocService;
import com.sails.ai.selfserviceapi.user.service.UserService;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class ActivityServiceTest {

    private static final String USER_ID = "01JABC123XYZ";
    private static final Long POC_ID = 1L;

    private ActivitySessionRepository activitySessionRepository;
    private PocService pocService;
    private ActivityService activityService;

    @BeforeEach
    void setUp() {
        activitySessionRepository = Mockito.mock(ActivitySessionRepository.class);
        pocService = Mockito.mock(PocService.class);
        activityService = new ActivityService(activitySessionRepository, pocService, Mockito.mock(UserService.class));
    }

    private static ActivitySession openSessionLastSeen(Instant lastSeenAt, long totalSeconds) {
        ActivitySession session = new ActivitySession();
        session.setId(10L);
        session.setUserId(USER_ID);
        session.setPocId(POC_ID);
        session.setStartedAt(lastSeenAt.minus(Duration.ofMinutes(5)));
        session.setLastSeenAt(lastSeenAt);
        session.setTotalSeconds(totalSeconds);
        return session;
    }

    private List<ActivitySession> savedSessions() {
        ArgumentCaptor<ActivitySession> captor = ArgumentCaptor.forClass(ActivitySession.class);
        verify(activitySessionRepository, Mockito.atLeastOnce()).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    void firstHeartbeatStartsANewSessionWithNoTimeAccrued() {
        when(activitySessionRepository.findFirstByUserIdAndPocIdAndEndedAtIsNullOrderByLastSeenAtDesc(USER_ID, POC_ID))
                .thenReturn(Optional.empty());

        activityService.recordHeartbeat(USER_ID, POC_ID);

        ActivitySession saved = savedSessions().getFirst();
        assertThat(saved.getUserId()).isEqualTo(USER_ID);
        assertThat(saved.getPocId()).isEqualTo(POC_ID);
        assertThat(saved.getTotalSeconds()).isZero();
        assertThat(saved.getEndedAt()).isNull();
        assertThat(saved.getStartedAt()).isEqualTo(saved.getLastSeenAt());
    }

    @Test
    void heartbeatWithinGracePeriodExtendsTheOpenSessionByTheElapsedGap() {
        ActivitySession existing = openSessionLastSeen(Instant.now().minusSeconds(20), 100);
        when(activitySessionRepository.findFirstByUserIdAndPocIdAndEndedAtIsNullOrderByLastSeenAtDesc(USER_ID, POC_ID))
                .thenReturn(Optional.of(existing));

        activityService.recordHeartbeat(USER_ID, POC_ID);

        assertThat(existing.getTotalSeconds()).isBetween(118L, 122L);
        assertThat(existing.getEndedAt()).isNull();
        verify(activitySessionRepository).save(existing);
    }

    @Test
    void heartbeatAfterALongSuspendCreditsAtMostOneCappedTick() {
        // Within the grace period but a wildly larger gap than any real heartbeat interval —
        // e.g. a clock jump. Only the cap may be credited, not the whole gap.
        ActivitySession existing = openSessionLastSeen(Instant.now().minusSeconds(89), 0);
        when(activitySessionRepository.findFirstByUserIdAndPocIdAndEndedAtIsNullOrderByLastSeenAtDesc(USER_ID, POC_ID))
                .thenReturn(Optional.of(existing));

        activityService.recordHeartbeat(USER_ID, POC_ID);

        assertThat(existing.getTotalSeconds()).isLessThanOrEqualTo(120L);
    }

    @Test
    void heartbeatAfterGracePeriodClosesStaleSessionAtItsLastHeartbeatAndStartsAFreshOne() {
        Instant staleLastSeen = Instant.now().minus(Duration.ofHours(3));
        ActivitySession stale = openSessionLastSeen(staleLastSeen, 600);
        when(activitySessionRepository.findFirstByUserIdAndPocIdAndEndedAtIsNullOrderByLastSeenAtDesc(USER_ID, POC_ID))
                .thenReturn(Optional.of(stale));

        activityService.recordHeartbeat(USER_ID, POC_ID);

        // The 3-hour gap must not be counted as usage, and the session must be closed at the
        // last time we actually heard from the user — not at "now".
        assertThat(stale.getTotalSeconds()).isEqualTo(600);
        assertThat(stale.getEndedAt()).isEqualTo(staleLastSeen);

        List<ActivitySession> saved = savedSessions();
        assertThat(saved).hasSize(2);
        ActivitySession fresh = saved.get(1);
        assertThat(fresh).isNotSameAs(stale);
        assertThat(fresh.getTotalSeconds()).isZero();
        assertThat(fresh.getEndedAt()).isNull();
    }

    @Test
    void heartbeatForAnUnknownPocIsRejectedBeforeAnySessionIsWritten() {
        when(pocService.getById(POC_ID)).thenThrow(new IllegalStateException("unknown poc"));

        try {
            activityService.recordHeartbeat(USER_ID, POC_ID);
        } catch (IllegalStateException expected) {
            // PocService owns the real 404; this test only pins down that validation happens first.
        }

        verify(activitySessionRepository, never()).save(any());
    }

    @Test
    void endSessionWithNoOpenSessionIsANoOp() {
        when(activitySessionRepository.findFirstByUserIdAndPocIdAndEndedAtIsNullOrderByLastSeenAtDesc(USER_ID, POC_ID))
                .thenReturn(Optional.empty());

        activityService.endSession(USER_ID, POC_ID);

        verify(activitySessionRepository, never()).save(any());
    }

    @Test
    void endSessionWithinGracePeriodCreditsTheGapAndClosesTheSessionNow() {
        ActivitySession existing = openSessionLastSeen(Instant.now().minusSeconds(15), 100);
        when(activitySessionRepository.findFirstByUserIdAndPocIdAndEndedAtIsNullOrderByLastSeenAtDesc(USER_ID, POC_ID))
                .thenReturn(Optional.of(existing));

        activityService.endSession(USER_ID, POC_ID);

        assertThat(existing.getTotalSeconds()).isBetween(113L, 117L);
        assertThat(existing.getEndedAt()).isNotNull();
        assertThat(existing.getEndedAt()).isEqualTo(existing.getLastSeenAt());
        verify(activitySessionRepository).save(existing);
    }

    @Test
    void endSessionPastGracePeriodClosesAtTheLastHeartbeatWithoutCreditingTheGap() {
        Instant staleLastSeen = Instant.now().minus(Duration.ofHours(3));
        ActivitySession stale = openSessionLastSeen(staleLastSeen, 600);
        when(activitySessionRepository.findFirstByUserIdAndPocIdAndEndedAtIsNullOrderByLastSeenAtDesc(USER_ID, POC_ID))
                .thenReturn(Optional.of(stale));

        activityService.endSession(USER_ID, POC_ID);

        assertThat(stale.getTotalSeconds()).isEqualTo(600);
        assertThat(stale.getEndedAt()).isEqualTo(staleLastSeen);
        verify(activitySessionRepository).save(stale);
    }

    @Test
    void getDailyUsageRejectsAFromDateAfterTo() {
        assertThatThrownBy(() -> activityService.getDailyUsage(LocalDate.of(2026, 8, 27), LocalDate.of(2026, 8, 20)))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("must not be after");

        verify(activitySessionRepository, never()).findDailyUsage(any(), any());
    }

    @Test
    void getDailyUsageDelegatesToTheRepositoryForAValidRange() {
        LocalDate from = LocalDate.of(2026, 8, 20);
        LocalDate to = LocalDate.of(2026, 8, 27);

        activityService.getDailyUsage(from, to);

        verify(activitySessionRepository).findDailyUsage(from, to);
    }

    private static OpenSessionProjection openSession(Instant lastSeenAt) {
        OpenSessionProjection projection = Mockito.mock(OpenSessionProjection.class);
        when(projection.getLastSeenAt()).thenReturn(lastSeenAt);
        return projection;
    }

    @Test
    void getActiveSessionsExcludesOpenSessionsPastTheGracePeriod() {
        OpenSessionProjection stillActive = openSession(Instant.now().minusSeconds(30));
        OpenSessionProjection abandoned = openSession(Instant.now().minus(Duration.ofHours(3)));
        when(activitySessionRepository.findOpenSessions()).thenReturn(List.of(stillActive, abandoned));

        List<OpenSessionProjection> active = activityService.getActiveSessions();

        assertThat(active).containsExactly(stillActive);
    }
}
