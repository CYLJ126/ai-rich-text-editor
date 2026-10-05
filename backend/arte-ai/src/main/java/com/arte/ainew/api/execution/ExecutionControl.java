package com.arte.ainew.api.execution;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.pojo.execution.ControlReceipt;
import com.arte.ainew.pojo.execution.ExecutionControlRequest;
import com.arte.ainew.pojo.execution.Invocation;
import reactor.core.publisher.Mono;

/**
 * 执行控制服务
 * <p>
 * 主要操作：查询状态、请求取消，支持时暂停／继续等。
 * 边界：分派到 Invocation／Job／Run 的权威，不另建状态推进者。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:39 ✾
 **/
public interface ExecutionControl {

    /**
     * 首批只支持 Invocation 权威；读取重新授权，不存在／不可见明确失败。
     */
    Mono<Invocation> status(String invocationId, ExecutionContext context);

    /**
     * 委托 Coordinator 耐久受理；请求取消不等于取消完成，也不默认释放预算。
     */
    Mono<ControlReceipt> request(ExecutionControlRequest request, ExecutionContext context);
}
