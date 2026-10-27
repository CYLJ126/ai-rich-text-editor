package com.arte.ainew.pojo.conversation;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.reference.ResourceRef;
import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * AI 会话的基本信息对象
 * <p>
 * 保存会话标识、固定归属、标题、并发版本、配置与资料引用、状态及时间。
 * AI 会话的基本信息及选入资料引用，作为轮次归属和会话并发校验的依据。
 * 用户输入及回答候选关联由 Turn 保存，执行状态由 Invocation 或 Run 保存，不在此复制。
 *
 * @param conversationId 会话唯一标识，由服务端创建，用于关联轮次、目录及执行记录。
 * @param owner          会话的固定执行归属，包含租户、工作空间和主体标识；读取及提交时须匹配当前可信执行主体。
 * @param title          会话标题，可由系统默认生成、依据用户消息自动生成或由用户修改，不作为会话身份标识。
 * @param version        非负的会话执行版本，初始为 0，用于轮次提交、回答候选变更及轮次分页读取的并发校验；独立于目录元数据版本，单独改标题不递增此版本。
 * @param chatProfile    可选的固定版本聊天配置引用，非空时类型必须为 chat-profile；当前创建及聊天入口尚不支持配置该字段。
 * @param resources      会话选入的资源引用列表，不重复，保存为不可变列表；引用不携带正文或授予读取、外发权限，当前创建及聊天入口仅支持空列表。
 * @param state          会话自身状态：ACTIVE 表示有效，DELETED 表示已删除；不表示任务执行状态、会话归档状态或工作空间生命周期。
 * @param createdAt      会话创建时间，不能为空。
 * @param updatedAt      会话最近更新时间，不能为空且不得早于 createdAt。
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public record Conversation(String conversationId, ExecutionOwner owner, String title, long version,
                           DefinitionRef chatProfile, List<ResourceRef> resources, State state,
                           Instant createdAt, Instant updatedAt) implements Serializable {

    public enum State {ACTIVE, DELETED}

    public Conversation {
        ContractChecks.id(conversationId, "conversationId");
        Objects.requireNonNull(owner, "owner");
        // 自动标题按字素簇截取，20 个 emoji 可能超过 256 个 UTF-16 单元；HTTP 人工输入仍限 256。
        ContractChecks.text(title, "title", ContractChecks.MAX_TEXT_CHARS + ContractChecks.MAX_PARTS + 2);
        ContractChecks.range(version, "version", 0, Long.MAX_VALUE);
        if (chatProfile != null) {
            chatProfile.requireType("chat-profile");
        }
        resources = ContractChecks.list(resources, "resources", 0, ContractChecks.MAX_ITEMS);
        ContractChecks.unique(resources, "resources");
        Objects.requireNonNull(state, "state");
        ContractChecks.ordered(createdAt, updatedAt, "updatedAt");
    }
}
