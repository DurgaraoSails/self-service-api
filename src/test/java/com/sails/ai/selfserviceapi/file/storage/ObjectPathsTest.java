package com.sails.ai.selfserviceapi.file.storage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ObjectPathsTest {

    @Test
    void anObjectPathIsUserFirstThenPoc() {
        assertThat(ObjectPaths.object("01HQ8", 42L, "abc"))
                .isEqualTo("users/01HQ8/pocs/42/abc");
    }

    /** Purge deletes by this prefix, so every one of that user's objects must sit beneath it. */
    @Test
    void everyObjectForAUserSitsUnderTheirPurgePrefix() {
        String prefix = ObjectPaths.userPrefix("01HQ8");
        assertThat(ObjectPaths.object("01HQ8", 1L, "a")).startsWith(prefix);
        assertThat(ObjectPaths.object("01HQ8", 999L, "b")).startsWith(prefix);
    }

    /** And no other user's does — the prefix must not be a prefix of a different user's path. */
    @Test
    void anotherUsersObjectsDoNotSitUnderThatPrefix() {
        assertThat(ObjectPaths.object("01HQ80", 1L, "a"))
                .doesNotStartWith(ObjectPaths.userPrefix("01HQ8"));
    }

    @Test
    void thePairPrefixNarrowsToOnePoc() {
        assertThat(ObjectPaths.object("01HQ8", 42L, "a"))
                .startsWith(ObjectPaths.pairPrefix("01HQ8", 42L));
        assertThat(ObjectPaths.object("01HQ8", 43L, "a"))
                .doesNotStartWith(ObjectPaths.pairPrefix("01HQ8", 42L));
    }
}
