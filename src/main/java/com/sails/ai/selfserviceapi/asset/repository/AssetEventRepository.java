package com.sails.ai.selfserviceapi.asset.repository;

import com.sails.ai.selfserviceapi.asset.entity.AssetEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AssetEventRepository extends JpaRepository<AssetEvent, UUID> {

    /**
     * One row: {@code (totalSearches, successfulSearches, medianSeconds, p90Seconds)} per the
     * Metrics Contract's successful-search-rate/time-to-useful-result definitions. A search is
     * "successful" if its session has a DETAIL_VIEW/SOURCE_OPEN/POC_LAUNCH event within 30 minutes
     * after it; {@code medianSeconds}/{@code p90Seconds} are computed only over successful
     * sessions, using the first such follow-on event. Both percentile columns are {@code null}
     * when there are zero successful sessions (nothing for {@code percentile_cont} to aggregate).
     */
    @Query(value = """
            with searches as (
                select id, search_session_id, occurred_at
                from asset_events
                where event_type = 'SEARCH' and search_session_id is not null
            ),
            followups as (
                select s.id as search_id, min(e.occurred_at) as first_followup_at
                from searches s
                join asset_events e
                  on e.search_session_id = s.search_session_id
                 and e.event_type in ('DETAIL_VIEW', 'SOURCE_OPEN', 'POC_LAUNCH')
                 and e.occurred_at > s.occurred_at
                 and e.occurred_at <= s.occurred_at + interval '30 minutes'
                group by s.id
            )
            select
              count(distinct s.id),
              count(distinct f.search_id),
              percentile_cont(0.5) within group (order by extract(epoch from (f.first_followup_at - s.occurred_at))),
              percentile_cont(0.9) within group (order by extract(epoch from (f.first_followup_at - s.occurred_at)))
            from searches s
            left join followups f on f.search_id = s.id
            """, nativeQuery = true)
    List<Object[]> findSearchSuccessStats();
}
