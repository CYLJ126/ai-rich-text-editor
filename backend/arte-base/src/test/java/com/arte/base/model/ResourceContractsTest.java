package com.arte.base.model;

import com.arte.base.model.resource.ResourceRef;
import com.arte.base.model.resource.SourceRef;
import com.arte.base.model.schema.SchemaRef;
import com.arte.base.model.security.SecretRef;
import org.junit.Test;

import static org.junit.Assert.*;

public class ResourceContractsTest {

    @Test
    public void liveResourceCanBeQueriedButCannotBecomeARecordedSource() {
        ResourceRef live = ResourceRef.current("article", "article-1");
        assertFalse(live.isPinned());
        assertThrows(IllegalArgumentException.class, () -> new SourceRef(live, null));
        assertThrows(IllegalArgumentException.class, () -> live.withRange("selection-1"));
    }

    @Test
    public void savedSourcePreservesOpaqueVersionAndCanBeAssignedCitationLater() {
        ResourceRef saved = ResourceRef.saved("article", "article-1", "rev:opaque-v3").withRange("selection-1");
        SourceRef source = new SourceRef(saved, null);
        assertTrue(saved.isPinned());
        assertFalse(saved.isDraft());
        assertEquals("rev:opaque-v3", source.resource().version());
        assertEquals("selection-1", source.resource().rangeRef());
        assertNull(source.citationId());
    }

    @Test
    public void draftRequiresDigestAndKeepsItsSavedBaseVersion() {
        assertThrows(IllegalArgumentException.class, () -> ResourceRef.draft("article", "article-1", "rev-1", "draft-1", null));
        ResourceRef draft = ResourceRef.draft("article", "article-1", "rev-1", "draft-1", "sha256:body");
        assertTrue(draft.isDraft());
        assertTrue(draft.isPinned());
        assertEquals("rev-1", draft.version());
        assertEquals("sha256:body", new SourceRef(draft, "source-1").resource().contentDigest());
    }

    @Test
    public void newDraftDoesNotPretendToHaveASavedVersion() {
        ResourceRef draft = ResourceRef.draft("article", "temporary-1", null, "draft-1", "sha256:body");
        assertNull(draft.version());
        assertTrue(draft.withRange("selection-1").isPinned());
    }

    @Test
    public void optionalIdentifiersRejectEmptyValuesRatherThanBecomingAbsent() {
        assertThrows(IllegalArgumentException.class, () -> new ResourceRef("article", "article-1", "", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> ResourceRef.saved("article", "article-1", null));
        assertThrows(IllegalArgumentException.class, () -> new SourceRef(ResourceRef.saved("article", "article-1", "rev-1"), ""));
        assertThrows(IllegalArgumentException.class, () -> new SchemaRef("schema-1", null));
        assertThrows(IllegalArgumentException.class, () -> new SecretRef("secret-1", ""));
        assertNull(new SecretRef("secret-1", null).version());
    }
}
