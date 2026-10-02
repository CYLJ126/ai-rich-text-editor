package com.arte.app.security.bridge;

import com.arte.app.pojo.rbac.JwtUserDto;
import com.arte.base.model.security.CommonResourceAction;
import com.arte.core.enums.StatusEnum;
import com.arte.core.pojo.UserContext;
import com.arte.core.pojo.UserOnlineInfo;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class IdentityAndContextIntegrationTest extends SecurityBridgeFixture {
    @Test
    void mapsVerifiedSessionToStableIdAndPersistsTaskWithoutOldThreadLocal() {
        UserContext.setUserOnlineInfo(new UserOnlineInfo().setId(2).setUserName("bob"));
        var context = context(CommonResourceAction.READ);
        assertEquals(ALICE, context.scope().principal());
        assertEquals(Set.of("resource.read"), context.authorizationScopes());
        assertTrue(repository.task(context).orElseThrow().enabled());
        assertEquals(NOW.plusSeconds(1800), context.deadline());
        assertTrue(repository.taskAction(context, ALICE, "resource.read").orElseThrow().enabled());
    }

    @Test
    void rejectsAnonymousInvalidExpiredAndMismatchedSessions() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("test", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        assertThrows(AccessDeniedException.class, () -> identity.requireAccount(http));
        login("alice", 1);
        tokens.valid = false;
        assertThrows(AccessDeniedException.class, () -> identity.requireAccount(http));
        tokens.valid = true;
        tokens.details = null;
        assertThrows(AccessDeniedException.class, () -> identity.requireAccount(http));
        login("alice", 2);
        assertThrows(AccessDeniedException.class, () -> identity.requireAccount(http));
        tokens.details = new JwtUserDto(new UserOnlineInfo().setId(1).setUserName("alice").setStatus(StatusEnum.CLOSED));
        assertThrows(AccessDeniedException.class, () -> identity.requireAccount(http));
        login("bob", 2);
        tokens.details = new JwtUserDto(new UserOnlineInfo().setId(1).setUserName("alice").setStatus(StatusEnum.DOING));
        assertThrows(AccessDeniedException.class, () -> identity.requireAccount(http));
    }

    @Test
    void immediatelyRejectsDisabledDatabaseAccountDespiteCachedNormalSession() {
        jdbc.update("UPDATE arte_rbac_user SET status = '3' WHERE id = 1");
        assertThrows(AccessDeniedException.class, () -> identity.requireAccount(http));
    }

    @Test
    void rejectsUnregisteredOrRevokedMembershipAndDoesNotBootstrapIt() {
        policy("resource.read");
        assertThrows(AccessDeniedException.class, () -> contexts.create(http, "personal-2", "workspace-2", "new-ai", "chat", Set.of("resource.read")));
        jdbc.update("UPDATE arte_security_member SET enabled = FALSE, revision = 2");
        assertThrows(AccessDeniedException.class, () -> context(CommonResourceAction.READ));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_task", Integer.class));
    }

    @Test
    void taskCreationRequiresEachApplicationAndBindingAction() {
        assertThrows(AccessDeniedException.class, () -> contexts.create(http, TENANT, WORKSPACE, "new-ai", "chat", Set.of("resource.read")));
        policy("resource.read");
        jdbc.update("UPDATE arte_security_application_policy SET binding_enabled = FALSE");
        assertThrows(AccessDeniedException.class, () -> contexts.create(http, TENANT, WORKSPACE, "new-ai", "chat", Set.of("resource.read")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_security_task", Integer.class));
    }
}
