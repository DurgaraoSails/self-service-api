package com.sails.ai.selfserviceapi.activity.controller;

import com.sails.ai.selfserviceapi.activity.service.ActivityResponseMapper;
import com.sails.ai.selfserviceapi.activity.service.ActivityService;
import com.sails.ai.selfserviceapi.generated.api.ActivityApi;
import com.sails.ai.selfserviceapi.generated.model.ActiveSessionResponse;
import com.sails.ai.selfserviceapi.generated.model.DailyUsagePoint;
import com.sails.ai.selfserviceapi.generated.model.HeartbeatRequest;
import com.sails.ai.selfserviceapi.generated.model.PocUsageSummary;
import com.sails.ai.selfserviceapi.generated.model.UserActivityDetail;
import com.sails.ai.selfserviceapi.generated.model.UserUsageSummary;
import com.sails.ai.selfserviceapi.security.CurrentUser;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ActivityController implements ActivityApi {

    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @Override
    public ResponseEntity<Void> recordHeartbeat(HeartbeatRequest heartbeatRequest) {
        // Usage is always recorded against the bearer token's subject, never a caller-supplied
        // id — otherwise any user could inflate or attribute usage to someone else.
        activityService.recordHeartbeat(CurrentUser.id(), heartbeatRequest.getPocId());
        return ResponseEntity.noContent().build();
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<PocUsageSummary>> getPocLeaderboard() {
        return ResponseEntity.ok(activityService.getPocLeaderboard().stream()
                .map(ActivityResponseMapper::toPocUsageSummary)
                .toList());
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<UserUsageSummary>> getUserLeaderboard() {
        return ResponseEntity.ok(activityService.getUserLeaderboard().stream()
                .map(ActivityResponseMapper::toUserUsageSummary)
                .toList());
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<UserActivityDetail> getUserActivity(String userId) {
        return ResponseEntity.ok(ActivityResponseMapper.toUserActivityDetail(
                activityService.getUser(userId),
                activityService.getPocUsageForUser(userId),
                activityService.getSessionsForUser(userId)));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<DailyUsagePoint>> getDailyUsage(LocalDate from, LocalDate to) {
        return ResponseEntity.ok(activityService.getDailyUsage(from, to).stream()
                .map(ActivityResponseMapper::toDailyUsagePoint)
                .toList());
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<ActiveSessionResponse>> getActiveSessions() {
        return ResponseEntity.ok(activityService.getActiveSessions().stream()
                .map(ActivityResponseMapper::toActiveSessionResponse)
                .toList());
    }
}
