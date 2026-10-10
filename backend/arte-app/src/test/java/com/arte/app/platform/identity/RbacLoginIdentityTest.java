package com.arte.app.platform.identity;

import com.arte.app.mapper.rbac.UserMapper;
import com.arte.app.pojo.rbac.JwtUserDto;
import com.arte.app.pojo.rbac.UserDto;
import com.arte.app.service.rbac.UserServiceImpl;
import com.arte.core.enums.StatusEnum;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RbacLoginIdentityTest {
    private UserMapper mapper() {
        return mock(UserMapper.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS));
    }

    private UserServiceImpl service(UserMapper users) {
        var service = mock(UserServiceImpl.class, withSettings().mockMaker(org.mockito.MockMakers.SUBCLASS).defaultAnswer(CALLS_REAL_METHODS));
        ReflectionTestUtils.setField(service, "baseMapper", users);
        return service;
    }

    private UserDto current() {
        var current = new UserDto();
        current.setId(1L);
        current.setUserName("alice");
        current.setStatus(StatusEnum.DOING);
        current.setPassword("current-password");
        current.setCreateTime(LocalDateTime.of(2026, 10, 10, 10, 0));
        current.setMobile("current-mobile");
        current.setEmail("current-email");
        return current;
    }

    @Test
    void disabledAccountCannotLoginUsingOldEnabledUserCache() {
        var users = mapper();
        var current = current();
        current.setStatus(StatusEnum.CLOSED);
        when(users.selectLoginByName("alice")).thenReturn(List.of(current));
        var service = service(users);
        var cached = current();
        cached.setStatus(StatusEnum.DOING);
        doReturn(cached).when(service).getByName("alice");
        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername("alice"));
        verify(service, never()).getByName(any());
    }

    @Test
    void loginUsesCurrentPasswordAndRetainsLegacyAuthorities() {
        var users = mapper();
        var current = current();
        when(users.selectLoginByName("alice")).thenReturn(List.of(current));
        var service = service(users);
        var cached = new UserDto();
        cached.setId(1L);
        cached.setPassword("old-password");
        cached.setStatus(StatusEnum.CLOSED);
        doReturn(cached).when(service).getByName("alice");
        doReturn(List.of("USER")).when(service).getRoles(any());
        doReturn(List.of("HomePage")).when(service).getMenus(any());
        doReturn(List.of("tag:list")).when(service).getAuthorities(any());
        doReturn(List.of()).when(service).getDepartments(any());
        doReturn(List.of()).when(service).getGroups(any());
        var details = (JwtUserDto) service.loadUserByUsername("alice");
        assertTrue(details.isEnabled());
        assertEquals("current-password", details.getPassword());
        assertEquals(1L, details.userOnlineInfo().getId());
        assertEquals(current.getCreateTime(), details.userOnlineInfo().getAccountCreatedAt());
        assertEquals("current-mobile", details.userOnlineInfo().getMobile());
        assertEquals("current-email", details.userOnlineInfo().getEmail());
        assertEquals("tag:list", details.getAuthorities().iterator().next().getAuthority());
        verify(users, times(1)).selectLoginByName("alice");
        verifyNoMoreInteractions(users);
        verify(service, never()).getById(any());
    }

    @Test
    void sameNameCacheForAnotherAccountCannotSupplyPermissions() {
        var users = mapper();
        when(users.selectLoginByName("alice")).thenReturn(List.of(current()));
        var service = service(users);
        var cached = current();
        cached.setId(2L);
        doReturn(cached).when(service).getByName("alice");
        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername("alice"));
        verify(service, never()).getAuthorities(any());
    }

    @Test
    void missingOrAmbiguousAccountsCannotLogin() {
        var users = mapper();
        var service = service(users);
        when(users.selectLoginByName("alice")).thenReturn(List.of());
        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername("alice"));
        when(users.selectLoginByName("alice")).thenReturn(List.of(current(), current()));
        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername("alice"));
        verify(service, never()).getByName(any());
    }

    @Test
    void incompleteOrInactiveAccountsCannotLogin() {
        var users = mapper();
        var service = service(users);
        var missingId = current();
        missingId.setId(null);
        var missingBirth = current();
        missingBirth.setCreateTime(null);
        var missingPassword = current();
        missingPassword.setPassword(null);
        var missingStatus = current();
        missingStatus.setStatus(null);
        var initial = current();
        initial.setStatus(StatusEnum.INITIAL);
        var done = current();
        done.setStatus(StatusEnum.DONE);
        for (var account : List.of(missingId, missingBirth, missingPassword, missingStatus, initial, done)) {
            when(users.selectLoginByName("alice")).thenReturn(List.of(account));
            assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername("alice"));
        }
        verify(service, never()).getByName(any());
    }

    @Test
    void blankUsernameDoesNotQueryAccounts() {
        var users = mapper();
        var service = service(users);
        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername(" "));
        verifyNoInteractions(users);
    }
}
