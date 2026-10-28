package com.arte.ainew.spi.adapter;

import com.arte.ainew.pojo.tool.ToolExecutionSnapshot;
import com.arte.ainew.pojo.tool.ToolOutcomeCheck;
import com.arte.ainew.pojo.tool.ToolOutcomeEvidence;
import com.arte.core.pojo.reference.DefinitionRef;
import reactor.core.publisher.Mono;

/**
 * 工具业务结果的只读查询适配端口，用于核实结果未知（UNKNOWN）的原工具调用是否已产生业务结果。
 * 实现方根据原执行快照和原操作标识读取权威回执，为后台自动核对和人工证据核验提供依据。
 * 本端口独立于工具执行适配器，不重新执行、重发或取消业务操作，也不负责预算结算。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 10:48 ✾
 */
public interface ToolOutcomeQueryAdapter {

    /**
     * 返回本适配器对应的工具定义引用，供查询适配器注册表匹配工具契约。
     */
    DefinitionRef tool();

    /**
     * 判断是否支持原执行快照中的工具版本、配置和调用上下文。
     * 支持查询仅表示能够核验原操作，不代表授予当前用户执行工具的权限。
     */
    boolean supports(ToolExecutionSnapshot snapshot);

    /**
     * 根据原执行快照还原领域或远端操作标识，用于将证据绑定到唯一的原业务操作。
     * 同一原操作的标识必须稳定，不能因查询次数或执行查询的 Worker 改变。
     */
    String operationKey(ToolExecutionSnapshot snapshot);

    /**
     * 在人工核对事务内同步读取原操作的权威证据，由调用方负责当前授权、原调用锁和核对资格校验。
     * 实现方必须复用当前物理事务，不得切换线程或另开事务；查无回执不能作为未执行的证明。
     * 默认拒绝调用，只有已实现可信证据读取的适配器才能支持人工核验。
     */
    default ToolOutcomeEvidence readEvidenceInCurrentTransaction(ToolOutcomeCheck.Claim claim) {
        throw new UnsupportedOperationException("Manual domain evidence verification is not installed");
    }

    /**
     * 根据后台已领取的核对任务异步查询原操作结果，查询时须通过存储端口校验当前租约和查询时限。
     * 无法证明结果时返回 STILL_UNKNOWN 证据，不得补发原操作或将缺失回执推断为执行失败。
     */
    Mono<ToolOutcomeEvidence> query(ToolOutcomeCheck.Claim claim);
}
