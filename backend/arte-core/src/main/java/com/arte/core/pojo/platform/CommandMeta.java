package com.arte.core.pojo.platform;

import com.arte.core.utils.platform.PlatformChecks;

/**
 * 写命令的幂等与追踪元数据
 * <p>
 * 所有写命令必填。
 * 主体、命令类型及操作范围由应用服务生成，不接受客户端授权声明。
 *
 * @param operationKey 操作幂等键，不能为空；同一业务操作重试时保持不变，与可信主体、命令类型及操作范围共同定位回执，同键业务内容不同则发生幂等冲突。
 * @param traceId      本次请求的追踪标识，不能为空，用于关联异常与诊断信息；不参与命令幂等身份或业务字段摘要，重试时可变化。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record CommandMeta(String operationKey, String traceId) {

    public CommandMeta {
        PlatformChecks.id(operationKey, "operationKey");
        PlatformChecks.id(traceId, "traceId");
    }
}
