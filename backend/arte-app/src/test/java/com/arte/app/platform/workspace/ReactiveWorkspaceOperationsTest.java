package com.arte.app.platform.workspace;

import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.persistence.PlatformPersistenceExecutor;
import com.arte.app.pojo.rbac.JwtUserDto;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.api.workspace.PersonalWorkspaceService;
import com.arte.core.api.workspace.ScopeResolver;
import com.arte.core.api.workspace.WorkspacePreferenceService;
import com.arte.core.api.workspace.WorkspaceService;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.platform.PrincipalKindEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.UserOnlineInfo;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.Principal;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.spi.access.WorkspaceWriteGuard;
import com.arte.core.spi.platform.PublicTransactionRunner;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ReactiveWorkspaceOperationsTest {
    private final RbacIdentityProvider identities = mock(RbacIdentityProvider.class);
    private final PersonalWorkspaceService personal = interfaceMock(PersonalWorkspaceService.class);
    private final WorkspacePreferenceService preferences = interfaceMock(WorkspacePreferenceService.class);
    private final TrustedActor actor = new TrustedActor(new Principal("subject", PrincipalKindEnum.USER,
            PrincipalStatusEnum.ACTIVE), Instant.now());

    // 与旧 RBAC 测试的 SUBCLASS maker 共存，接口使用独立的 JDK proxy handler。
    private static <T> T interfaceMock(Class<T> type) {
        return mock(type, withSettings().mockMaker(org.mockito.MockMakers.PROXY));
    }

    private Authentication login() {
        return UsernamePasswordAuthenticationToken.authenticated(new JwtUserDto(new UserOnlineInfo()
                .setId(1L).setUserName("alice").setAccountCreatedAt(LocalDateTime.parse("2026-10-10T10:00:00"))
                .setToken("secret-token").setPassword("secret-password")), null, List.of());
    }

    private ReactiveWorkspaceOperations operations(reactor.core.scheduler.Scheduler scheduler) {
        return new ReactiveWorkspaceOperations(identities,
                new PlatformPersistenceExecutor(scheduler, interfaceMock(PublicTransactionRunner.class)), personal,
                interfaceMock(WorkspaceService.class), preferences, interfaceMock(ScopeResolver.class),
                interfaceMock(AuthorizationService.class), interfaceMock(WorkspaceWriteGuard.class));
    }

    @Test
    void lazyExecutionCopiesIdentityAndDoesNotRetainCredentialsOrCallerThread() {
        var scheduler = Schedulers.newBoundedElastic(1, 4, "workspace-unit");
        try {
            var caller = Thread.currentThread();
            when(identities.actor(any())).thenAnswer(invocation -> {
                assertNotSame(caller, Thread.currentThread());
                var snapshot = ((JwtUserDto) ((Authentication) invocation.getArgument(0)).getPrincipal()).userOnlineInfo();
                assertEquals(1L, snapshot.getId());
                assertEquals("alice", snapshot.getUserName());
                assertNull(snapshot.getToken());
                assertNull(snapshot.getPassword());
                return actor;
            });
            var auth = login();
            var mono = operations(scheduler).initializePersonal(auth, new CommandMeta("key", "trace"));
            verifyNoInteractions(identities, personal);
            ((JwtUserDto) auth.getPrincipal()).userOnlineInfo().setUserName("changed").setId(2L);
            mono.block(Duration.ofSeconds(5));
            verify(personal).initialize(eq(actor), any());
        } finally {
            scheduler.dispose();
        }
    }

    @Test
    void invalidAuthenticationEmitsAnErrorWithoutSchedulingDatabaseWork() {
        var scheduler = Schedulers.newBoundedElastic(1, 4, "workspace-unit");
        try {
            var mono = operations(scheduler).getPreference(null);
            assertEquals(PlatformErrorCodeEnum.UNAUTHENTICATED,
                    assertThrows(PlatformException.class, () -> mono.block(Duration.ofSeconds(5))).getCode());
            verifyNoInteractions(identities, preferences);
        } finally {
            scheduler.dispose();
        }
    }

    @Test
    void identityFailureIsPropagatedAndNeverStartsCommand() {
        var scheduler = Schedulers.newBoundedElastic(1, 4, "workspace-unit");
        try {
            when(identities.actor(any())).thenThrow(new PlatformException(PlatformErrorCodeEnum.UNAUTHENTICATED, "trace"));
            assertThrows(PlatformException.class, () -> operations(scheduler)
                    .initializePersonal(login(), new CommandMeta("key", "trace")).block(Duration.ofSeconds(5)));
            verifyNoInteractions(personal);
        } finally {
            scheduler.dispose();
        }
    }

    @Test
    void saturatedBoundedSchedulerRejectsWithoutRunningIdentityOrGrowingQueue() throws Exception {
        var scheduler = Schedulers.newBoundedElastic(1, 1, "workspace-saturation");
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var queuedError = new AtomicReference<Throwable>();
        try {
            scheduler.schedule(() -> {
                entered.countDown();
                try {
                    release.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var api = operations(scheduler);
            var queued = api.getPreference(login()).subscribe(ignored -> {
            }, queuedError::set);
            try {
                assertEquals(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE,
                        assertThrows(PlatformException.class, () -> api.getPreference(login()).block(Duration.ofSeconds(2))).getCode());
                verifyNoInteractions(identities, preferences);
            } finally {
                queued.dispose();
            }
        } finally {
            release.countDown();
            scheduler.dispose();
        }
    }
}
