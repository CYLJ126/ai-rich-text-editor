package com.arte.ainew.spi.gateway;

import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.execution.GatewayCall;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.pojo.generation.GenerationSignal;
import reactor.core.publisher.Flux;

/**
 * 模型网关服务
 * <p>
 * 主要操作：generate、生成事件与最终 ModelResult
 * 边界：接收类型化生成请求，不直接执行业务工具
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 16:58 ✾
 **/
public interface ModelGateway {

    /**
     * 本地元数据判断：该网关是否支持此绑定／连接的纯文本流式聊天。
     * 不读取凭据、不发请求、不授予权限；自定义网关须显式声明支持，否则不进入聊天发现列表。
     */
    default boolean supportsTextChat(ResolvedBinding binding, ConnectionDefinition connection) {
        return false;
    }

    /**
     * 每次订阅至多发送一次模型请求；同一流返回增量及最终 Result／Failure，不拆成两次远端调用。
     * 冷 Publisher，仅 Coordinator 订阅；不内部 subscribe、retry 或推进 Invocation 状态。
     * 遵守期限、取消与输出上限；无结束信号的流结束不能当成成功。
     * 业务失败转换为 Failure；订阅前校验／基础设施异常可 onError，协调器不得据此假定未执行。
     */
    Flux<GenerationSignal> generate(GatewayCall<GenerationRequest> call);
}
