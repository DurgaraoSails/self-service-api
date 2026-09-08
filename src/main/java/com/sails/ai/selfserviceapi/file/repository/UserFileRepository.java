package com.sails.ai.selfserviceapi.file.repository;

import com.sails.ai.selfserviceapi.file.entity.UserFile;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserFileRepository extends JpaRepository<UserFile, UUID> {

    /** The listing query, and the reason for the partial (user_id, poc_id) index. */
    List<UserFile> findByUserIdAndPocIdAndDeletedAtIsNullOrderByUploadedAtDesc(String userId, UUID pocId);

    /**
     * Every lookup of a single file goes through this, never {@code findById}. The owning pair is
     * part of the query rather than a check afterwards, so a caller cannot read a file by id
     * alone — there is no code path that loads one without proving who it belongs to.
     */
    Optional<UserFile> findByIdAndUserIdAndPocIdAndDeletedAtIsNull(UUID id, String userId, UUID pocId);

    /** Per-(user, POC) file-count quota. */
    long countByUserIdAndPocIdAndDeletedAtIsNull(String userId, UUID pocId);

    /**
     * Per-user total-bytes quota, across every POC. Counts only live files: a soft-deleted row's
     * object is removed from the bucket at delete time, so it is no longer consuming anything.
     */
    @Query("SELECT COALESCE(SUM(f.sizeBytes), 0) FROM UserFile f WHERE f.userId = :userId AND f.deletedAt IS NULL")
    long sumLiveSizeBytesByUserId(@Param("userId") String userId);
}
