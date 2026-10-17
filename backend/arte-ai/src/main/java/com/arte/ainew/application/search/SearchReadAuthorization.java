package com.arte.ainew.application.search;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.spi.auth.ExecutionAuthorizationResolver;
import com.arte.core.enums.ResultCodeEnum;
import org.springframework.security.access.AccessDeniedException;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;

/**
 * 会话搜索的当前授权校验器，重新解析主体权限并检查请求期限。
 * 用于搜索开始及结果返回前复核，避免仅依赖 HTTP 请求携带的旧授权。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public final class SearchReadAuthorization {

    private final ExecutionAuthorizationResolver resolver;
    private final Clock clock;

    public SearchReadAuthorization(ExecutionAuthorizationResolver resolver, Clock clock) {
        this.resolver = resolver;
        this.clock = clock;
    }

    /**
     * 按内容读取需求校验权限，重新解析授权并核对主体、空间、权限集合及请求期限。
     */
    public void require(ExecutionContext context, boolean messageContent, Duration remaining) {
        var old = context.authorization();
        var required = messageContent ? Set.of(AdmissionAuthorization.CONVERSATION, AdmissionAuthorization.READ) : Set.of(AdmissionAuthorization.CONVERSATION);
        requireDeadline(context);
        if (!old.scopes().containsAll(required)) throw new AccessDeniedException("Search read denied");
        var fresh = resolver.resolve(old.principal().subjectName(), old.tenantId(), old.workspaceId(), old.scopes()).block(remaining);
        requireDeadline(context);
        if (fresh == null || !fresh.principal().equals(old.principal()) || !fresh.tenantId().equals(old.tenantId()) || !fresh.workspaceId().equals(old.workspaceId())
                || !fresh.scopes().equals(old.scopes())) throw new AccessDeniedException("Search read denied");
    }

    /**
     * 请求期限已到时终止读取，避免继续执行依赖操作。
     */
    private void requireDeadline(ExecutionContext context) {
        if (!clock.instant().isBefore(context.deadline()))
            throw new AdmissionException(ResultCodeEnum.AI_DEADLINE_EXCEEDED);
    }
}
