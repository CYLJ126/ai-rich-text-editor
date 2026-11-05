package com.arte.app.platform.persistence;

import com.arte.core.enums.workspace.TenantTypeEnum;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.WorkspaceCandidateKey;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DirectoryCursorStorageTest {

    @Test
    void serializedNavigationBindsTenantAndPreservesLegacyUnfilteredCursor() {
        var json = new PlatformJson();
        var scope = new Scope("tenant", "workspace");
        var boundary = new WorkspaceCandidateKey(Instant.parse("2026-10-09T10:00:00Z"), scope);
        var old = new WorkspaceCursorStore.Cursor("alice", 3, 20, "query", boundary);
        String serialized = json.write(old);
        assertEquals(old, json.read(serialized, WorkspaceCursorStore.Cursor.class));
        // A pre-C03 reader can still consume an unfiltered cursor minted by C03.
        assertEquals(new LegacyCursor("alice", 3, 20, "query", boundary), json.read(serialized, LegacyCursor.class));

        var restricted = new WorkspaceCursorStore.Cursor("alice", 3, 20, "query", boundary, "tenant");
        assertEquals(restricted, json.read(json.write(restricted), WorkspaceCursorStore.Cursor.class));
        var tenant = new WorkspaceCursorStore.TenantCursor("alice", 1, 20, "query", TenantTypeEnum.ORGANIZATION, "tenant");
        assertEquals(tenant, json.read(json.write(tenant), WorkspaceCursorStore.TenantCursor.class));
    }

    record LegacyCursor(String subjectId, int version, int size, String search, WorkspaceCandidateKey key) {
    }
}
