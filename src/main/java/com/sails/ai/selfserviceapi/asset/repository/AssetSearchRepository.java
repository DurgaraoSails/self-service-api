package com.sails.ai.selfserviceapi.asset.repository;

import com.sails.ai.selfserviceapi.asset.entity.AssetSearchDocument;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Native full-text search over approved assets. Filters are bound as comma-separated strings and
 * split server-side with {@code string_to_array} rather than as Java arrays/lists — Hibernate's
 * native-query binding for {@code = ANY(?)} against a true array parameter is unreliable across
 * driver/version combinations; a single string parameter has no such ambiguity.
 *
 * <p>Ranking and pagination are hand-rolled (LIMIT/OFFSET plus a separate count query) rather than
 * a Spring Data {@code Page<UUID>} native-query return, since scalar (non-entity) projections from
 * native queries paired with {@code countQuery} are a known rough edge in Spring Data JPA.
 *
 * <p>Every {@code (:param is null or ...)} guard casts the parameter explicitly on the
 * {@code is null} side (e.g. {@code cast(:q as text) is null}): Postgres resolves each bind
 * placeholder's type independently at prepare time, and a placeholder whose only occurrence is a
 * bare {@code IS NULL} gives it nothing to infer from, even when the same named parameter has
 * clear type context (e.g. inside a function call) at a different occurrence in the same query.
 */
public interface AssetSearchRepository extends JpaRepository<AssetSearchDocument, UUID> {

    @Query(value = """
            select a.id
            from assets a
            join asset_revisions r on r.id = a.approved_revision_id
            left join asset_search_documents sd on sd.asset_id = a.id
            where a.archived_at is null
              and a.approved_revision_id is not null
              and (cast(:typesCsv as text) is null or a.asset_type = any(string_to_array(:typesCsv, ',')))
              and (cast(:ownerId as text) is null or a.owner_user_id = :ownerId)
              and (cast(:tagsCsv as text) is null or exists (
                    select 1 from asset_revision_tags art
                    join tags t on t.id = art.tag_id
                    where art.revision_id = r.id and t.normalized_name = any(string_to_array(:tagsCsv, ','))))
              and (:launchableOnly = false or (a.poc_id is not null and exists (
                    select 1 from pocs p where p.id = a.poc_id and p.deleted_at is null
                      and p.visibility_status = 'ACTIVE' and p.app_url is not null and p.app_url <> '')))
              and (cast(:q as text) is null or sd.search_vector @@ websearch_to_tsquery('english', :q))
            order by
              case when cast(:q as text) is null then 0.0 else ts_rank_cd(sd.search_vector, websearch_to_tsquery('english', :q)) end desc,
              r.updated_at desc, a.id asc
            limit :limit offset :offset
            """, nativeQuery = true)
    List<UUID> findRankedAssetIds(@Param("q") String q,
                                   @Param("typesCsv") String typesCsv,
                                   @Param("tagsCsv") String tagsCsv,
                                   @Param("ownerId") String ownerId,
                                   @Param("launchableOnly") boolean launchableOnly,
                                   @Param("limit") int limit,
                                   @Param("offset") int offset);

    @Query(value = """
            select count(*)
            from assets a
            join asset_revisions r on r.id = a.approved_revision_id
            left join asset_search_documents sd on sd.asset_id = a.id
            where a.archived_at is null
              and a.approved_revision_id is not null
              and (cast(:typesCsv as text) is null or a.asset_type = any(string_to_array(:typesCsv, ',')))
              and (cast(:ownerId as text) is null or a.owner_user_id = :ownerId)
              and (cast(:tagsCsv as text) is null or exists (
                    select 1 from asset_revision_tags art
                    join tags t on t.id = art.tag_id
                    where art.revision_id = r.id and t.normalized_name = any(string_to_array(:tagsCsv, ','))))
              and (:launchableOnly = false or (a.poc_id is not null and exists (
                    select 1 from pocs p where p.id = a.poc_id and p.deleted_at is null
                      and p.visibility_status = 'ACTIVE' and p.app_url is not null and p.app_url <> '')))
              and (cast(:q as text) is null or sd.search_vector @@ websearch_to_tsquery('english', :q))
            """, nativeQuery = true)
    long countRankedAssets(@Param("q") String q,
                            @Param("typesCsv") String typesCsv,
                            @Param("tagsCsv") String tagsCsv,
                            @Param("ownerId") String ownerId,
                            @Param("launchableOnly") boolean launchableOnly);

    @Modifying
    @Query(value = """
            insert into asset_search_documents (asset_id, revision_id, search_text, search_vector, indexed_at)
            values (:assetId, :revisionId, :searchText,
                    setweight(to_tsvector('english', :titleAndTags), 'A') ||
                    setweight(to_tsvector('english', :summary), 'B') ||
                    setweight(to_tsvector('english', :problemImpactSolution), 'C') ||
                    setweight(to_tsvector('english', :ownerName), 'D'),
                    now())
            on conflict (asset_id) do update set
                revision_id = excluded.revision_id,
                search_text = excluded.search_text,
                search_vector = excluded.search_vector,
                indexed_at = excluded.indexed_at
            """, nativeQuery = true)
    void upsertSearchDocument(@Param("assetId") UUID assetId,
                               @Param("revisionId") UUID revisionId,
                               @Param("searchText") String searchText,
                               @Param("titleAndTags") String titleAndTags,
                               @Param("summary") String summary,
                               @Param("problemImpactSolution") String problemImpactSolution,
                               @Param("ownerName") String ownerName);

    /** Facet counts share the same WHERE clause as {@link #findRankedAssetIds}, minus ranking/paging. */
    String FACET_BASE = """
            from assets a
            join asset_revisions r on r.id = a.approved_revision_id
            left join asset_search_documents sd on sd.asset_id = a.id
            where a.archived_at is null
              and a.approved_revision_id is not null
              and (cast(:typesCsv as text) is null or a.asset_type = any(string_to_array(:typesCsv, ',')))
              and (cast(:ownerId as text) is null or a.owner_user_id = :ownerId)
              and (cast(:tagsCsv as text) is null or exists (
                    select 1 from asset_revision_tags art
                    join tags t on t.id = art.tag_id
                    where art.revision_id = r.id and t.normalized_name = any(string_to_array(:tagsCsv, ','))))
              and (:launchableOnly = false or (a.poc_id is not null and exists (
                    select 1 from pocs p where p.id = a.poc_id and p.deleted_at is null
                      and p.visibility_status = 'ACTIVE' and p.app_url is not null and p.app_url <> '')))
              and (cast(:q as text) is null or sd.search_vector @@ websearch_to_tsquery('english', :q))
            """;

    @Query(value = "select a.asset_type, count(*) " + FACET_BASE + " group by a.asset_type", nativeQuery = true)
    List<Object[]> countByType(@Param("q") String q, @Param("typesCsv") String typesCsv,
                                @Param("tagsCsv") String tagsCsv, @Param("ownerId") String ownerId,
                                @Param("launchableOnly") boolean launchableOnly);

    /**
     * Not built from {@link #FACET_BASE}: that string's tag join lives inside an {@code EXISTS}
     * subquery for filtering, but grouping by tag needs the join in the outer FROM clause instead
     * — appending a JOIN after FACET_BASE's WHERE clause (as an earlier version of this method did)
     * is invalid SQL (a JOIN cannot follow WHERE), so this query is fully self-contained.
     */
    @Query(value = """
            select t.normalized_name, count(distinct a.id)
            from assets a
            join asset_revisions r on r.id = a.approved_revision_id
            left join asset_search_documents sd on sd.asset_id = a.id
            join asset_revision_tags art on art.revision_id = r.id
            join tags t on t.id = art.tag_id
            where a.archived_at is null
              and a.approved_revision_id is not null
              and (cast(:typesCsv as text) is null or a.asset_type = any(string_to_array(:typesCsv, ',')))
              and (cast(:ownerId as text) is null or a.owner_user_id = :ownerId)
              and (cast(:tagsCsv as text) is null or exists (
                    select 1 from asset_revision_tags art2
                    join tags t2 on t2.id = art2.tag_id
                    where art2.revision_id = r.id and t2.normalized_name = any(string_to_array(:tagsCsv, ','))))
              and (:launchableOnly = false or (a.poc_id is not null and exists (
                    select 1 from pocs p where p.id = a.poc_id and p.deleted_at is null
                      and p.visibility_status = 'ACTIVE' and p.app_url is not null and p.app_url <> '')))
              and (cast(:q as text) is null or sd.search_vector @@ websearch_to_tsquery('english', :q))
            group by t.normalized_name
            """, nativeQuery = true)
    List<Object[]> countByTag(@Param("q") String q, @Param("typesCsv") String typesCsv,
                               @Param("tagsCsv") String tagsCsv, @Param("ownerId") String ownerId,
                               @Param("launchableOnly") boolean launchableOnly);

    @Query(value = "select a.owner_user_id, count(*) " + FACET_BASE + " group by a.owner_user_id", nativeQuery = true)
    List<Object[]> countByOwner(@Param("q") String q, @Param("typesCsv") String typesCsv,
                                 @Param("tagsCsv") String tagsCsv, @Param("ownerId") String ownerId,
                                 @Param("launchableOnly") boolean launchableOnly);

    @Query(value = "select count(*) " + FACET_BASE
            + " and a.poc_id is not null and exists (select 1 from pocs p where p.id = a.poc_id"
            + " and p.deleted_at is null and p.visibility_status = 'ACTIVE' and p.app_url is not null and p.app_url <> '')",
            nativeQuery = true)
    long countLaunchable(@Param("q") String q, @Param("typesCsv") String typesCsv,
                          @Param("tagsCsv") String tagsCsv, @Param("ownerId") String ownerId,
                          @Param("launchableOnly") boolean launchableOnly);
}
