package com.sails.ai.selfserviceapi.activity.repository;

import com.sails.ai.selfserviceapi.activity.entity.ActivitySession;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivitySessionRepository extends JpaRepository<ActivitySession, Long> {

    Optional<ActivitySession> findFirstByUserIdAndPocIdAndEndedAtIsNullOrderByLastSeenAtDesc(String userId, Long pocId);

    @Query("""
            SELECT s.pocId AS pocId, p.name AS pocName,
                   SUM(s.totalSeconds) AS totalSeconds, COUNT(DISTINCT s.userId) AS userCount
            FROM ActivitySession s JOIN Poc p ON p.id = s.pocId
            GROUP BY s.pocId, p.name
            ORDER BY SUM(s.totalSeconds) DESC
            """)
    List<PocUsageProjection> findPocUsage();

    @Query("""
            SELECT s.pocId AS pocId, p.name AS pocName,
                   SUM(s.totalSeconds) AS totalSeconds, COUNT(DISTINCT s.userId) AS userCount
            FROM ActivitySession s JOIN Poc p ON p.id = s.pocId
            WHERE s.userId = :userId
            GROUP BY s.pocId, p.name
            ORDER BY SUM(s.totalSeconds) DESC
            """)
    List<PocUsageProjection> findPocUsageByUserId(@Param("userId") String userId);

    @Query("""
            SELECT s.userId AS userId, u.firstName AS firstName, u.lastName AS lastName,
                   u.email AS email, SUM(s.totalSeconds) AS totalSeconds
            FROM ActivitySession s JOIN User u ON u.id = s.userId
            GROUP BY s.userId, u.firstName, u.lastName, u.email
            ORDER BY SUM(s.totalSeconds) DESC
            """)
    List<UserUsageProjection> findUserUsage();

    @Query("""
            SELECT s.pocId AS pocId, p.name AS pocName, s.startedAt AS startedAt,
                   s.lastSeenAt AS lastSeenAt, s.endedAt AS endedAt, s.totalSeconds AS totalSeconds
            FROM ActivitySession s JOIN Poc p ON p.id = s.pocId
            WHERE s.userId = :userId
            ORDER BY s.startedAt DESC
            """)
    List<SessionProjection> findSessionsByUserId(@Param("userId") String userId);

    /**
     * One row per day in [from, to], zero-filled — a LEFT JOIN against a generated date series
     * rather than grouping the sessions directly, so a day with no usage still produces a point
     * instead of a gap the caller would have to fill in before charting. Bucketed by the UTC
     * calendar day of each session's startedAt; a session that happens to straddle midnight is
     * counted entirely on its start day rather than split, matching how the rest of this feature
     * treats a session as one indivisible unit.
     */
    @Query(value = """
            SELECT gs.day::date AS day, COALESCE(SUM(s.total_seconds), 0) AS totalSeconds
            FROM generate_series(CAST(:from AS date), CAST(:to AS date), INTERVAL '1 day') AS gs(day)
            LEFT JOIN activity_sessions s ON (s.started_at AT TIME ZONE 'UTC')::date = gs.day::date
            GROUP BY gs.day
            ORDER BY gs.day
            """, nativeQuery = true)
    List<DailyUsageProjection> findDailyUsage(@Param("from") LocalDate from, @Param("to") LocalDate to);

    @Query("""
            SELECT s.userId AS userId, u.firstName AS firstName, u.lastName AS lastName,
                   s.pocId AS pocId, p.name AS pocName, s.startedAt AS startedAt, s.lastSeenAt AS lastSeenAt
            FROM ActivitySession s JOIN Poc p ON p.id = s.pocId JOIN User u ON u.id = s.userId
            WHERE s.endedAt IS NULL
            ORDER BY s.startedAt DESC
            """)
    List<OpenSessionProjection> findOpenSessions();
}
