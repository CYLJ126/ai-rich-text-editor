package com.arte.ainew.spi.model;

import com.arte.ainew.pojo.model.ModelDrafts.Probe;
import com.arte.ainew.pojo.model.ModelDrafts.ProbeResult;
import reactor.core.publisher.Mono;

/**
 * 个人模型连接验证端口，消费经当前授权领取的服务端探测输入并返回安全结果。
 * <p>
 * 实现应限制网络目的地、并发、响应大小和处理时间，不建立聊天轮次、不执行内容生成，也不隐式重试。
 * 调用发生在数据库事务之外；结果只说明声明的检查范围，不代表模型已发布或已取得调用资格。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 15:55 ✾
 */
public interface ModelConnectionValidator {

    /**
     * 执行一次受控连接探测；当前实现检查认证后的模型列表中是否存在目标远端模型。
     *
     * @param probe 应用服务唯一领取的敏感探测输入，包含审核连接、远端模型标识和本次使用的密钥；不得来自客户端或写入日志
     * @return 订阅后执行的探测结果，只包含验证是否成功及安全结果码，不返回密钥、远端响应或异常详情
     */
    Mono<ProbeResult> validate(Probe probe);
}
