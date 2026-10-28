package com.arte.ainew.pojo.remote;

import com.arte.ainew.common.execution.RemoteTaskRef;
import com.arte.ainew.common.reference.ArtifactRef;
import com.arte.ainew.common.reference.SourceRef;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.pojo.execution.Usage;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/**
 * 远程应用或 Agent 服务的类型化返回契约
 * <p>
 * 供 RemoteApplicationGateway 的调用及任务查询使用。
 * Completed 承载已完成输出，Pending 承载尚未完成的远端任务及可选会话，不混用平台执行 ID 与远端任务身份。
 * 已完成输出与远端受理任务分开；结果未知或失败使用执行错误契约，不伪装为普通模型结果。
 * 返回对象不代表平台已完成持久化、费用结算或业务采纳，后续读取、继续会话及任务控制仍须重新授权。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:22 ✾
 */
public sealed interface RemoteApplicationResult extends Serializable permits RemoteApplicationResult.Completed,
        RemoteApplicationResult.Pending {

    /**
     * 远程应用已完成的输出及关联资料，可封装为 InvocationResult.RemoteApplication 保存。
     * 结构化内容及产物仍须由执行链路核对 Schema、来源、大小和当前访问资格，不能仅凭构造成功认定平台调用已成功提交。
     *
     * @param output    可选的结构化完成输出；为空时 artifacts 必须至少有一项，只有来源引用不能构成完成输出。
     * @param session   可选的远端会话引用，用于后续继续交流；非空时由可信边界核对其连接及执行归属，不表示平台 Conversation 或当前访问许可。
     * @param sources   输出关联的来源引用列表，不能为空，最多 ContractChecks.MAX_ITEMS 项且 citationId 不重复，保存为不可变列表；无来源时使用空列表，展示或重用仍须授权。
     * @param artifacts 已转存为本系统产物的引用列表，不能为空，最多 ContractChecks.MAX_PARTS 项，保存为不可变列表；无产物时使用空列表，不以供应商临时 URL 代替，读取时仍须授权。
     * @param usage     本次远端应用调用的用量记录，不能为空，允许明确标记未知或估算；完成输出不表示费用已知或已结算。
     */
    record Completed(StructuredValue output, RemoteApplicationRequest.SessionRef session,
                     List<SourceRef> sources, List<ArtifactRef> artifacts,
                     Usage usage) implements RemoteApplicationResult {

        public Completed {
            sources = ContractChecks.list(sources, "sources", 0, ContractChecks.MAX_ITEMS);
            ContractChecks.unique(sources.stream().map(SourceRef::citationId).toList(), "source IDs");
            artifacts = ContractChecks.list(artifacts, "artifacts", 0, ContractChecks.MAX_PARTS);
            ContractChecks.require(output != null || !artifacts.isEmpty(), "Completed remote application requires output");
            Objects.requireNonNull(usage, "usage");
        }
    }

    /**
     * 远程应用尚未返回完成输出时的任务引用，供后续查询及受支持的任务控制流程使用。
     * 保留远端任务自身的状态和控制能力，不将远端受理等同于平台业务完成；持有任务或会话引用不授予控制权限。
     *
     * @param task    远端任务引用，不能为空且状态必须为非终态，包含远端任务 ID、固定连接、执行归属及支持的控制操作；后续查询或控制须重新核对当前资格。
     * @param session 可选的远端会话引用，用于关联后续交流；非空时 connection 和 owner 必须与 task 完全一致，不能跨连接或跨执行归属复用。
     */
    record Pending(RemoteTaskRef task, RemoteApplicationRequest.SessionRef session) implements RemoteApplicationResult {

        public Pending {
            Objects.requireNonNull(task, "task");
            ContractChecks.require(!task.state().terminal(), "Pending remote task cannot already be terminal");
            ContractChecks.require(session == null || task.connection().equals(session.connection())
                    && task.owner().equals(session.owner()), "Remote task and session belong to different connection or owner");
        }
    }
}
