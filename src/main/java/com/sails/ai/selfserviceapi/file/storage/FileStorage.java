package com.sails.ai.selfserviceapi.file.storage;

import java.io.InputStream;

/**
 * Object storage, narrowed to the four things this feature does with it. Deliberately knows
 * nothing about users, POCs or quotas — callers hand it a path built by {@link ObjectPaths}.
 *
 * <p>The interface is also what keeps the transport decision reversible. Bytes proxy through this
 * service today because signed URLs need a project-level IAM grant this project is blocked on
 * (see the spec); if that grant ever lands, only an implementation changes.
 */
public interface FileStorage {

    /**
     * Writes an object, failing if one already exists at that path. Object names are generated
     * ids, so a collision means something is wrong rather than being a case to overwrite through.
     */
    void store(String objectName, String contentType, InputStream content);

    /** Opens an object for reading. The caller closes the stream. */
    InputStream open(String objectName);

    /** @return false if there was nothing there, which is not an error — delete is idempotent. */
    boolean delete(String objectName);

    /**
     * Deletes everything under a prefix. Used by trial-expiry purge, which is why it reports a
     * count: a purge job that silently stops running is indistinguishable from one with nothing
     * to do unless it says how much it removed.
     */
    int deleteByPrefix(String prefix);
}
