package com.arte.ainew.spi.gateway;

import com.arte.ainew.common.execution.RemoteTaskRef;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.pojo.execution.GatewayCall;
import com.arte.ainew.pojo.remote.RemoteApplicationRequest;
import com.arte.ainew.pojo.remote.RemoteApplicationResult;
import reactor.core.publisher.Mono;

/**
 * 远端服务调用网关
 * <p>
 * 主要操作：调用远程应用／Agent，继续远端会话，查询任务
 * 边界：保留远端身份、生命周期与结果，隔离不同主体的会话
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:00 ✾
 **/
public interface RemoteApplicationGateway {

    /**
     * START／CONTINUE 共用受治理入口；继续时重新核对远端会话的主体与实际连接。
     */
    Mono<RemoteApplicationResult> invoke(GatewayCall<RemoteApplicationRequest> call);

    /**
     * 只核对原任务；不能把查询失败变为新建远端会话或重发应用操作。
     */
    Mono<RemoteApplicationResult> query(RemoteTaskRef task, ExecutionRuntimeContext runtime);

    /**
     * 显式检查远端控制能力，取消请求不等于任务终止。
     */
    Mono<RemoteTaskRef> cancel(RemoteTaskRef task, ExecutionRuntimeContext runtime);
}
