package com.arte.app.platform.identity;

import com.arte.app.api.rbac.OnlineService;
import com.arte.app.api.rbac.UserService;
import com.arte.app.config.bean.WebSecurityProperties;
import com.arte.app.pojo.rbac.JwtUserDto;
import com.arte.app.pojo.rbac.UserDto;
import com.arte.app.service.rbac.OnlineServiceImpl;
import com.arte.app.service.rbac.TokenServiceImpl;
import com.arte.core.enums.StatusEnum;
import com.arte.core.pojo.UserOnlineInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RbacSessionIdentityTest {
    private static <T> T subclassMock(Class<T> type) {
        return mock(type, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
    }

    private OnlineServiceImpl online;
    private RbacAccountReader reader;
    private UserService users;
    private UserOnlineInfo session;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        online = new OnlineServiceImpl();
        reader = subclassMock(RbacAccountReader.class);
        users = subclassMock(UserService.class);
        var redis = subclassMock(RedissonClient.class);
        RBucket<Object> bucket = subclassMock(RBucket.class);
        session = new UserOnlineInfo().setId(1L).setUserName("alice").setStatus(StatusEnum.DOING);
        session.setAccountCreatedAt(LocalDateTime.of(2026, 10, 10, 10, 0));
        when(redis.getBucket("online:token")).thenReturn(bucket);
        when(bucket.get()).thenAnswer(ignored -> session);
        var properties = new WebSecurityProperties();
        properties.setOnlineKey("online:");
        ReflectionTestUtils.setField(online, "accountReader", reader);
        ReflectionTestUtils.setField(online, "userService", users);
        ReflectionTestUtils.setField(online, "redissonClient", redis);
        ReflectionTestUtils.setField(online, "webSecurityProperties", properties);
    }

    private RbacAccountReader.Account account(String name, StatusEnum status) {
        return new RbacAccountReader.Account(1, name, status, LocalDateTime.of(2026, 10, 10, 10, 0));
    }

    @Test
    void enabledAccountPreservesLegacyMenuPermissionsAndRefreshesStatus() {
        session.setStatus(StatusEnum.CLOSED);
        when(reader.findById(1L)).thenReturn(Optional.of(account("alice", StatusEnum.DOING)));
        var user = new UserDto();
        user.setId(1L);
        user.setMenus(List.of("HomePage"));
        user.setMenuOperations(List.of("tag:list"));
        user.setRoles(List.of());
        when(users.getByName("alice")).thenReturn(user);
        var result = online.getOnlineInfo("token");
        assertEquals(1L, result.getId());
        assertEquals(StatusEnum.DOING, result.getStatus());
        assertEquals(List.of("tag:list"), result.getMenuOperations());
    }

    @Test
    void disabledAccountRejectsOldActiveSessionBeforeReadingPermissions() {
        when(reader.findById(1L)).thenReturn(Optional.of(account("alice", StatusEnum.CLOSED)));
        assertNull(online.getOnlineInfo("token"));
        verifyNoInteractions(users);
    }

    @Test
    void deletedAccountRejectsOldSession() {
        when(reader.findById(1L)).thenReturn(Optional.empty());
        assertNull(online.getOnlineInfo("token"));
        verifyNoInteractions(users);
    }

    @Test
    void renamedAccountRequiresReloginRatherThanLookingUpAnotherUserByOldName() {
        when(reader.findById(1L)).thenReturn(Optional.of(account("renamed", StatusEnum.DOING)));
        assertNull(online.getOnlineInfo("token"));
        verifyNoInteractions(users);
    }

    @Test
    void staleUserCacheCannotTransferAnotherAccountsPermissions() {
        when(reader.findById(1L)).thenReturn(Optional.of(account("alice", StatusEnum.DOING)));
        var other = new UserDto();
        other.setId(2L);
        other.setMenuOperations(List.of("admin"));
        when(users.getByName("alice")).thenReturn(other);
        assertNull(online.getOnlineInfo("token"));
    }

    @Test
    void oldSessionsWithoutPrimaryKeyAreRejected() {
        session.setId(null);
        when(reader.findById(null)).thenReturn(Optional.empty());
        assertNull(online.getOnlineInfo("token"));
        verifyNoInteractions(users);
    }

    @Test
    void reusedAccountPrimaryKeyCannotReviveOldSession() {
        when(reader.findById(1L)).thenReturn(Optional.of(account("alice", StatusEnum.DOING)));
        session.setAccountCreatedAt(LocalDateTime.of(2026, 10, 9, 10, 0));
        assertNull(online.getOnlineInfo("token"));
        verifyNoInteractions(users);
    }

    @Test
    void preupgradeSessionRequiresReloginToEstablishAccountFingerprint() {
        when(reader.findById(1L)).thenReturn(Optional.of(account("alice", StatusEnum.DOING)));
        session.setAccountCreatedAt(null);
        assertNull(online.getOnlineInfo("token"));
        verifyNoInteractions(users);
    }

    @Test
    void tokenUserDetailsUseTheSameVerifiedOnlineIdentity() {
        var tokens = new TokenServiceImpl();
        var service = subclassMock(OnlineService.class);
        ReflectionTestUtils.setField(tokens, "onlineService", service);
        when(service.getOnlineInfo("token")).thenReturn(session);
        assertSame(session, ((JwtUserDto) tokens.getUserDetailsByToken("token")).userOnlineInfo());
        when(service.getOnlineInfo("token")).thenReturn(null);
        assertNull(tokens.getUserDetailsByToken("token"));
    }

    @Test
    void separateLoginsNeverRecreateOldTokenAndMalformedJwtIsRejected() {
        var tokens = new TokenServiceImpl();
        ReflectionTestUtils.setField(tokens, "key", new byte[32]);
        var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(new JwtUserDto(session), null, List.of());
        String first = tokens.createToken(authentication);
        String second = tokens.createToken(authentication);
        assertNotEquals(first, second);
        assertTrue(tokens.verifyToken(first));
        assertFalse(tokens.verifyToken("invalid.jwt"));
        String oldNumericToken = cn.hutool.jwt.JWT.create().setPayload("accountId", 1)
                .setKey(new byte[32]).sign();
        assertTrue(tokens.verifyToken(oldNumericToken));
    }

    @Test
    void longAccountIdSurvivesSessionAuthorizationAndTokenCreation() {
        long id = Long.MAX_VALUE;
        session.setId(id);
        var account = new RbacAccountReader.Account(id, "alice", StatusEnum.DOING, session.getAccountCreatedAt());
        when(reader.findById(id)).thenReturn(Optional.of(account));
        var cached = new UserDto();
        cached.setId(id);
        cached.setRoles(List.of());
        when(users.getByName("alice")).thenReturn(cached);
        assertEquals(id, online.getOnlineInfo("token").getId());
        var tokens = new TokenServiceImpl();
        ReflectionTestUtils.setField(tokens, "key", new byte[32]);
        var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                new JwtUserDto(session), null, List.of());
        String token = tokens.createToken(authentication);
        assertTrue(tokens.verifyToken(token));
        assertEquals(Long.toString(id), cn.hutool.jwt.JWTUtil.parseToken(token).getPayload("accountId"));
    }
}
