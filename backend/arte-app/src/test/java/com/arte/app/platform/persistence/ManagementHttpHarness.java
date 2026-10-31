package com.arte.app.platform.persistence;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.arte.app.pojo.rbac.JwtUserDto;
import com.arte.app.web.advice.GlobalExceptionHandler;
import com.arte.app.web.advice.PlatformExceptionHandler;
import com.arte.app.web.aspect.WebAspect;
import com.arte.core.pojo.UserOnlineInfo;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 真实 MVC 绑定、Bean Validation、旧 Web 切面与公共错误契约，供模型和 ACL 兼容验收复用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 15:00 ✾
 */
final class ManagementHttpHarness implements AutoCloseable {

    final MockMvc mvc;
    final LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();

    ManagementHttpHarness(Object... controllers) {
        Object[] proxies = new Object[controllers.length];
        for (int i = 0; i < controllers.length; i++) {
            AspectJProxyFactory factory = new AspectJProxyFactory(controllers[i]);
            factory.setProxyTargetClass(true);
            factory.addAspect(new WebAspect());
            proxies[i] = factory.getProxy();
        }
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(proxies).setControllerAdvice(new PlatformExceptionHandler(), new GlobalExceptionHandler())
                .setValidator(validator).setAsyncRequestTimeout(15000).build();
    }

    static Authentication login(JdbcTemplate jdbc, long id, String name) {
        UserOnlineInfo online = new UserOnlineInfo().setId(id).setUserName(name).setAccountCreatedAt(jdbc.queryForObject(
                "SELECT create_time FROM arte_rbac_user WHERE id=?", (row, index) -> row.getObject(1, LocalDateTime.class), id));
        return UsernamePasswordAuthenticationToken.authenticated(new JwtUserDto(online), null, List.of());
    }

    MvcResult execute(String path, String body, String key, Authentication auth) {
        org.springframework.security.core.context.SecurityContext previous = SecurityContextHolder.getContext();
        org.springframework.security.core.context.SecurityContext current = SecurityContextHolder.createEmptyContext();
        current.setAuthentication(auth);
        SecurityContextHolder.setContext(current);
        try {
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request = post(path).contentType(MediaType.APPLICATION_JSON);
            if (body != null) {
                request.content(body);
            }
            if (key != null) {
                request.header("Idempotency-Key", key);
            }
            if (auth != null) {
                request.principal(auth);
            }
            MvcResult result = mvc.perform(request).andReturn();
            if (result.getRequest().isAsyncStarted()) {
                result.getAsyncResult(15000);
                result = mvc.perform(asyncDispatch(result)).andReturn();
            }
            return result;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    JSONObject ok(String path, String body, String key, Authentication auth) {
        MvcResult result = execute(path, body, key, auth);
        String contents = contents(result);
        assertEquals(200, result.getResponse().getStatus(), contents);
        JSONObject value = JSONUtil.parseObj(contents);
        assertTrue(value.getBool("success"), contents);
        return value;
    }

    void rejected(String path, String body, String key, Authentication auth, int status, String code) {
        MvcResult result = execute(path, body, key, auth);
        String contents = contents(result);
        assertEquals(status, result.getResponse().getStatus(), contents);
        JSONObject value = JSONUtil.parseObj(contents);
        assertFalse(value.getBool("success"), contents);
        assertEquals("PLATFORM_" + code, value.getStr("code"), contents);
        assertNotNull(result.getResponse().getHeader("X-Trace-Id"));
    }

    static String contents(MvcResult result) {
        try {
            return result.getResponse().getContentAsString();
        } catch (java.io.UnsupportedEncodingException failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Override
    public void close() {
        validator.close();
    }
}
