package com.sails.ai.selfserviceapi.activity.service;

import com.sails.ai.selfserviceapi.activity.repository.DailyUsageProjection;
import com.sails.ai.selfserviceapi.activity.repository.OpenSessionProjection;
import com.sails.ai.selfserviceapi.activity.repository.PocUsageProjection;
import com.sails.ai.selfserviceapi.activity.repository.SessionProjection;
import com.sails.ai.selfserviceapi.activity.repository.UserUsageProjection;
import com.sails.ai.selfserviceapi.generated.model.ActiveSessionResponse;
import com.sails.ai.selfserviceapi.generated.model.ActivitySessionResponse;
import com.sails.ai.selfserviceapi.generated.model.DailyUsagePoint;
import com.sails.ai.selfserviceapi.generated.model.PocUsageSummary;
import com.sails.ai.selfserviceapi.generated.model.UserActivityDetail;
import com.sails.ai.selfserviceapi.generated.model.UserUsageSummary;
import com.sails.ai.selfserviceapi.user.entity.User;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

public final class ActivityResponseMapper {

    private ActivityResponseMapper() {
    }

    public static PocUsageSummary toPocUsageSummary(PocUsageProjection projection) {
        return new PocUsageSummary(projection.getPocId(), projection.getPocName(), projection.getTotalSeconds())
                .userCount(projection.getUserCount());
    }

    /**
     * Per-user breakdown deliberately leaves userCount unset — scoped to one user it would
     * always be 1, which reads as data rather than a constant.
     */
    public static PocUsageSummary toUserScopedPocUsageSummary(PocUsageProjection projection) {
        return new PocUsageSummary(projection.getPocId(), projection.getPocName(), projection.getTotalSeconds());
    }

    public static UserUsageSummary toUserUsageSummary(UserUsageProjection projection) {
        return new UserUsageSummary(projection.getUserId(), projection.getFirstName(),
                projection.getLastName(), projection.getEmail(), projection.getTotalSeconds());
    }

    public static ActivitySessionResponse toSessionResponse(SessionProjection projection) {
        return new ActivitySessionResponse(projection.getPocId(), projection.getPocName(),
                toUtcOffset(projection.getStartedAt()), projection.getTotalSeconds(), status(projection))
                .endedAt(toUtcOffset(projection.getEndedAt()));
    }

    /**
     * A session with no {@code endedAt} is only genuinely still open while its last heartbeat is
     * within the grace period. Past that, nothing will ever close it in the database — the tab
     * is gone and no further heartbeat is coming — so without this check it would read as
     * "in progress" forever.
     */
    private static ActivitySessionResponse.StatusEnum status(SessionProjection projection) {
        boolean stale = projection.getEndedAt() != null
                || Duration.between(projection.getLastSeenAt(), Instant.now()).compareTo(ActivityService.GRACE_PERIOD) > 0;
        return stale ? ActivitySessionResponse.StatusEnum.ENDED : ActivitySessionResponse.StatusEnum.ACTIVE;
    }

    public static UserActivityDetail toUserActivityDetail(User user, List<PocUsageProjection> byPoc,
                                                          List<SessionProjection> sessions) {
        long totalSeconds = byPoc.stream().mapToLong(PocUsageProjection::getTotalSeconds).sum();
        return new UserActivityDetail(user.getId(), user.getFirstName(), user.getLastName(), user.getEmail(),
                totalSeconds,
                byPoc.stream().map(ActivityResponseMapper::toUserScopedPocUsageSummary).toList(),
                sessions.stream().map(ActivityResponseMapper::toSessionResponse).toList());
    }

    public static DailyUsagePoint toDailyUsagePoint(DailyUsageProjection projection) {
        return new DailyUsagePoint(projection.getDay(), projection.getTotalSeconds());
    }

    public static ActiveSessionResponse toActiveSessionResponse(OpenSessionProjection projection) {
        return new ActiveSessionResponse(projection.getUserId(), projection.getFirstName(), projection.getLastName(),
                projection.getPocId(), projection.getPocName(), toUtcOffset(projection.getStartedAt()));
    }

    private static OffsetDateTime toUtcOffset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
