package com.sails.ai.selfserviceapi.file.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ObjectPathsTest {

    private static final UUID POC_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final UUID OTHER_POC_ID = UUID.fromString("00000000-0000-0000-0000-000000000043");

    @Test
    void anObjectPathIsUserFirstThenPoc() {
        assertThat(ObjectPaths.object("01HQ8", POC_ID, "abc"))
                .isEqualTo("users/01HQ8/pocs/" + POC_ID + "/abc");
    }

    /** Purge deletes by this prefix, so every one of that user's objects must sit beneath it. */
    @Test
    void everyObjectForAUserSitsUnderTheirPurgePrefix() {
        String prefix = ObjectPaths.userPrefix("01HQ8");
        assertThat(ObjectPaths.object("01HQ8", POC_ID, "a")).startsWith(prefix);
        assertThat(ObjectPaths.object("01HQ8", OTHER_POC_ID, "b")).startsWith(prefix);
    }

    /** And no other user's does — the prefix must not be a prefix of a different user's path. */
    @Test
    void anotherUsersObjectsDoNotSitUnderThatPrefix() {
        assertThat(ObjectPaths.object("01HQ80", POC_ID, "a"))
                .doesNotStartWith(ObjectPaths.userPrefix("01HQ8"));
    }

    @Test
    void thePairPrefixNarrowsToOnePoc() {
        assertThat(ObjectPaths.object("01HQ8", POC_ID, "a"))
                .startsWith(ObjectPaths.pairPrefix("01HQ8", POC_ID));
        assertThat(ObjectPaths.object("01HQ8", OTHER_POC_ID, "a"))
                .doesNotStartWith(ObjectPaths.pairPrefix("01HQ8", POC_ID));
    }
}
