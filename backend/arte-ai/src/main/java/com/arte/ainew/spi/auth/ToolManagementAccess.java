package com.arte.ainew.spi.auth;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * TOOL 管理的宿主授权端口：捕获真实登录会话，复用当前账户、RBAC、空间及 AI 资格。
 * 不提供默认允许实现；HTTP 不接受主体、权限集合或此接口的 Permit。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 12:52 ✾
 */
public interface ToolManagementAccess {
    /**
     * 中央映射的操作类别；平台目录治理与个人绑定管理分开授权。
     */
    enum Operation {LIST, BINDING_WRITE, BINDING_DISABLE, MANAGE}

    /**
     * 必须在 MVC 请求线程捕获不可变会话证据，异步代码不再读取 ThreadLocal。
     */
    Session capture(Authentication authentication);

    interface Session {
        /**
         * 每次订阅都重新检查当前资格，不把第一次允许缓存为跨请求授权。
         */
        Mono<Permit> authorize(Scope scope, Operation operation);
    }

    interface Permit {
        TrustedActor actor();

        ExecutionOwner owner();

        Operation operation();

        /**
         * 可信执行权限上限，仅用于过滤产品声明，不是客户端可提交的 scopes。
         */
        Set<String> executionScopes();

        /**
         * 在实际写事务／同一物理数据源重新检查并持有治理锁；重放也必须执行。
         * requiredScopes 来自服务端读取的固定工具契约，不能由请求体传入。
         */
        void requireInCurrentTransaction(Set<String> requiredScopes);
    }
}
