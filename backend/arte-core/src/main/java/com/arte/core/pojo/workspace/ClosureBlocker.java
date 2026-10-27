package com.arte.core.pojo.workspace;

import com.arte.core.utils.platform.PlatformChecks;

/**
 * 领域返回的最小关闭阻塞项
 * <p>
 * 不含回复正文或供应商明文证据。
 * 表示领域向空间关闭流程报告的阻塞项，例如未完成任务、待核对费用或未处理依赖。包含所属领域、工作项标识、原因码及是否支持重试。
 *
 * @param domain    报告阻塞项的稳定领域标识，用于区分并定位对应关闭参与者，须符合受限名称格式。
 * @param workKey   领域内用于定位相关关闭工作项的稳定标识；与关闭操作标识及 domain 共同关联固定目标的收尾工作。
 * @param code      阻塞原因的标识码，由领域解释，用于表达未完成任务、待核对费用或未处理依赖等原因，不承载正文或明文证据。
 * @param retryable 相关阻塞处理是否支持重试；不表示已安排自动重试，也不授予重试权限或允许直接重发结果未知的外部调用。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record ClosureBlocker(String domain, String workKey, String code, boolean retryable) {

    public ClosureBlocker {
        PlatformChecks.token(domain, "domain");
        PlatformChecks.id(workKey, "workKey");
        PlatformChecks.id(code, "code");
    }
}
