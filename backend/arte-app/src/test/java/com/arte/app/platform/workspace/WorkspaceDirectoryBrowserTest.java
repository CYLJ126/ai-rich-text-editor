package com.arte.app.platform.workspace;

import com.arte.app.platform.persistence.WorkspaceCursorStore;
import com.arte.core.api.workspace.WorkspaceService;
import com.arte.core.enums.platform.PrincipalKindEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.pojo.platform.Principal;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.WorkspaceBrowseQuery;
import com.arte.core.pojo.workspace.WorkspaceBrowseResult;
import com.arte.core.pojo.workspace.WorkspaceCandidateKey;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkspaceDirectoryBrowserTest {
    private final WorkspaceService service = mock(WorkspaceService.class, withSettings().mockMaker(org.mockito.MockMakers.PROXY));
    private final Map<String, WorkspaceCursorStore.Cursor> tokens = new HashMap<>();
    private final WorkspaceCursorStore cursors = new WorkspaceCursorStore() {
        public String save(Cursor cursor) {
            String token = UUID.randomUUID().toString();
            tokens.put(token, cursor);
            return token;
        }

        public Cursor load(String token) {
            return Objects.requireNonNull(tokens.get(token));
        }
    };
    private final WorkspaceDirectoryBrowser browser = new WorkspaceDirectoryBrowser(service, cursors);
    private final TrustedActor alice = actor("alice");
    private final WorkspaceCandidateKey boundary = new WorkspaceCandidateKey(Instant.EPOCH, new Scope("tenant", "space"));

    @Test
    void tokenBindsSubjectCanonicalSearchAndPageSizeBeforeCallingCore() {
        var query = new WorkspaceBrowseQuery(20, " Example ");
        when(service.browseAccessible(alice, query, null)).thenReturn(new WorkspaceBrowseResult(List.of(), boundary, false));
        var first = browser.browse(alice, null, query);
        assertFalse(first.exhausted());
        assertTrue(first.records().isEmpty());
        assertNotNull(first.nextCursor());
        clearInvocations(service);
        assertThrows(IllegalArgumentException.class, () -> browser.browse(actor("bob"), first.nextCursor(), query));
        assertThrows(IllegalArgumentException.class, () -> browser.browse(alice, first.nextCursor(), new WorkspaceBrowseQuery(10, "example")));
        assertThrows(IllegalArgumentException.class, () -> browser.browse(alice, first.nextCursor(), new WorkspaceBrowseQuery(20, "different")));
        verifyNoInteractions(service);
        when(service.browseAccessible(alice, query, boundary)).thenReturn(new WorkspaceBrowseResult(List.of(), null, true));
        assertTrue(browser.browse(alice, first.nextCursor(), new WorkspaceBrowseQuery(20, " EXAMPLE ")).exhausted());
        verify(service).browseAccessible(alice, query, boundary);
    }

    @Test
    void terminalPageDoesNotCreateNavigationState() {
        var query = new WorkspaceBrowseQuery(20, "");
        when(service.browseAccessible(alice, query, null)).thenReturn(new WorkspaceBrowseResult(List.of(), null, true));
        var page = browser.browse(alice, null, query);
        assertNull(page.nextCursor());
        assertTrue(tokens.isEmpty());
    }

    @Test
    void unicodeCaseExpansionDoesNotInvalidateTheStoredSearchContext() {
        var query = new WorkspaceBrowseQuery(20, "İ".repeat(100));
        assertEquals(200, query.search().length());
        when(service.browseAccessible(alice, query, null)).thenReturn(new WorkspaceBrowseResult(List.of(), boundary, false));
        String token = browser.browse(alice, null, query).nextCursor();
        when(service.browseAccessible(alice, query, boundary)).thenReturn(new WorkspaceBrowseResult(List.of(), null, true));
        assertTrue(browser.browse(alice, token, new WorkspaceBrowseQuery(20, "İ".repeat(100))).exhausted());
    }

    private static TrustedActor actor(String subject) {
        return new TrustedActor(new Principal(subject, PrincipalKindEnum.USER, PrincipalStatusEnum.ACTIVE), Instant.EPOCH);
    }
}
