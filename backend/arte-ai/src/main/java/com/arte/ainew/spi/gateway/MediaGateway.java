package com.arte.ainew.spi.gateway;

import com.arte.ainew.common.execution.RemoteTaskRef;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.pojo.execution.GatewayCall;
import com.arte.ainew.pojo.media.MediaRequest;
import com.arte.ainew.pojo.media.MediaResult;
import reactor.core.publisher.Mono;

/**
 * 多媒体网关
 * <p>
 * 主要操作：生成、查询任务、获取产物、请求取消
 * 边界：同步／异步分别返回，共享供应商连接适配
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:00 ✾
 **/
public interface MediaGateway {

    /**
     * 返回 Completed 或 Pending；远端受理不等于本地 Invocation 成功。
     */
    Mono<MediaResult> generate(GatewayCall<MediaRequest> call);

    /**
     * 当前授权下核对任务连接／主体及 QUERY 能力，只查询原任务，不重发生成。
     */
    Mono<MediaResult> query(RemoteTaskRef task, ExecutionRuntimeContext runtime);

    /**
     * 按 CANCEL 能力请求远端取消；返回远端观察状态，不保证取消完成。
     */
    Mono<RemoteTaskRef> cancel(RemoteTaskRef task, ExecutionRuntimeContext runtime);
}
