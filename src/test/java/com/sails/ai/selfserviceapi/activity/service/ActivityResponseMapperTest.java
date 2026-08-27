package com.sails.ai.selfserviceapi.activity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.activity.repository.DailyUsageProjection;
import com.sails.ai.selfserviceapi.activity.repository.OpenSessionProjection;
import com.sails.ai.selfserviceapi.activity.repository.SessionProjection;
import com.sails.ai.selfserviceapi.generated.model.ActiveSessionResponse;
import com.sails.ai.selfserviceapi.generated.model.ActivitySessionResponse;
import com.sails.ai.selfserviceapi.generated.model.DailyUsagePoint;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ActivityResponseMapperTest {

    private static SessionProjection projection(Instant lastSeenAt, Instant endedAt) {
        SessionProjection projection = mock(SessionProjection.class);
        when(projection.getPocId()).thenReturn(1L);
        when(projection.getPocName()).thenReturn("Contract Agent");
        when(projection.getStartedAt()).thenReturn(lastSeenAt.minus(Duration.ofMinutes(5)));
        when(projection.getLastSeenAt()).thenReturn(lastSeenAt);
        when(projection.getEndedAt()).thenReturn(endedAt);
        when(projection.getTotalSeconds()).thenReturn(300L);
        return projection;
    }

    @Test
    void recentHeartbeatWithNoEndedAtIsStillActive() {
        SessionProjection projection = projection(Instant.now().minusSeconds(30), null);

        ActivitySessionResponse response = ActivityResponseMapper.toSessionResponse(projection);

        assertThat(response.getStatus()).isEqualTo(ActivitySessionResponse.StatusEnum.ACTIVE);
    }

    @Test
    void explicitlyClosedSessionIsEnded() {
        Instant lastSeen = Instant.now().minusSeconds(30);
        SessionProjection projection = projection(lastSeen, lastSeen);

        ActivitySessionResponse response = ActivityResponseMapper.toSessionResponse(projection);

        assertThat(response.getStatus()).isEqualTo(ActivitySessionResponse.StatusEnum.ENDED);
    }

    @Test
    void openSessionPastTheGracePeriodReadsAsEndedEvenThoughNoHeartbeatEverClosedIt() {
        // Nothing in the database ever marks this row closed — no further heartbeat is coming —
        // so without this computed status it would read as "in progress" forever.
        SessionProjection projection = projection(Instant.now().minus(Duration.ofHours(3)), null);

        ActivitySessionResponse response = ActivityResponseMapper.toSessionResponse(projection);

        assertThat(response.getStatus()).isEqualTo(ActivitySessionResponse.StatusEnum.ENDED);
    }

    @Test
    void dailyUsagePointCarriesTheDayAndTotalStraightThrough() {
        DailyUsageProjection projection = mock(DailyUsageProjection.class);
        when(projection.getDay()).thenReturn(LocalDate.of(2026, 8, 20));
        when(projection.getTotalSeconds()).thenReturn(340L);

        DailyUsagePoint point = ActivityResponseMapper.toDailyUsagePoint(projection);

        assertThat(point.getDate()).isEqualTo(LocalDate.of(2026, 8, 20));
        assertThat(point.getTotalSeconds()).isEqualTo(340L);
    }

    @Test
    void activeSessionResponseCarriesUserAndPocDetailsStraightThrough() {
        Instant startedAt = Instant.now().minusSeconds(30);
        OpenSessionProjection projection = mock(OpenSessionProjection.class);
        when(projection.getUserId()).thenReturn("u1");
        when(projection.getFirstName()).thenReturn("Ava");
        when(projection.getLastName()).thenReturn("Patel");
        when(projection.getPocId()).thenReturn(1L);
        when(projection.getPocName()).thenReturn("Sails Process Assistant");
        when(projection.getStartedAt()).thenReturn(startedAt);

        ActiveSessionResponse response = ActivityResponseMapper.toActiveSessionResponse(projection);

        assertThat(response.getUserId()).isEqualTo("u1");
        assertThat(response.getFirstName()).isEqualTo("Ava");
        assertThat(response.getLastName()).isEqualTo("Patel");
        assertThat(response.getPocId()).isEqualTo(1L);
        assertThat(response.getPocName()).isEqualTo("Sails Process Assistant");
        assertThat(response.getStartedAt().toInstant()).isEqualTo(startedAt);
    }
}
