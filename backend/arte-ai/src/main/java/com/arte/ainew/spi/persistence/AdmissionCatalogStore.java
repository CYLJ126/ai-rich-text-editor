package com.arte.ainew.spi.persistence;

import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.conversation.ConversationList;
import com.arte.ainew.pojo.conversation.ConversationPage;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.core.pojo.platform.ExecutionOwner;
import reactor.core.publisher.Mono;

/**
 * 受理事务所需的会话版本／活跃轮次和预算账户初始化；由可信控制面显式调用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 23:11 ✾
 */
public interface AdmissionCatalogStore {

    /**
     * 创建会话并初始化列表元数据。
     * 已有记录不能覆盖；不替代 ConversationService 的完整编辑、历史和删除能力。
     *
     * @param conversation 待创建的会话，包含会话标识、可信归属、版本及初始配置，不能为 null
     * @return 创建完成后正常结束的 Mono；已有记录或存储失败通过错误信号传播
     */
    Mono<Void> createConversation(Conversation conversation);

    /**
     * 按归属与操作幂等键原子创建会话、列表元数据及创建记录。
     * 摘要一致时返回首次创建的会话的当前快照，后续会话变更不影响幂等重放；摘要冲突时不覆盖已有记录。
     *
     * @param conversation   待创建的初始会话，不能为 null；版本必须为 0，状态必须为 ACTIVE
     * @param idempotencyKey 创建操作的幂等键，在会话归属范围内唯一
     * @param requestDigest  创建请求的规范化摘要，用于判断同一幂等键是否对应相同请求
     * @return 首次创建返回 APPLIED，重复请求返回 REPLAYED；摘要冲突返回 IDEMPOTENCY_CONFLICT，
     * 原会话不存在或归属不匹配返回 NOT_FOUND
     */
    Mono<StoreOutcome<Conversation>> createConversationOnce(Conversation conversation, String idempotencyKey, String requestDigest);

    /**
     * 按元数据版本原子修改会话标题或置顶状态，并保存命令回执；不推进会话执行版本。
     * 标题修改与置顶操作共用归属范围内的幂等键空间；相同请求重放首次回执或拒绝结果。
     *
     * @param owner   由可信主体解析的租户、工作空间及主体归属，不能为 null
     * @param command 修改命令，不能为 null；包含会话标识、非负预期元数据版本、幂等键，
     *                且标题与置顶状态必须且只能指定一项
     * @return 首次成功返回 APPLIED，成功命令重放返回 REPLAYED；失败返回 NOT_FOUND、VERSION_CONFLICT、
     * IDEMPOTENCY_CONFLICT 或 PIN_LIMIT_REACHED；同一归属最多置顶 7 个会话
     */
    Mono<StoreOutcome<ConversationList.Receipt>> changeConversationMetadata(ExecutionOwner owner, ConversationList.Command command);

    /**
     * 按归属查询已持久化的会话元数据命令结果，包括成功回执和拒绝结果。
     *
     * @param owner     由可信主体解析的租户、工作空间及主体归属，不能为 null
     * @param commandId 命令标识，即提交元数据修改时使用的幂等键
     * @return 原命令结果；该归属下不存在命令记录时返回空 Mono
     */
    Mono<ConversationList.CommandResult> conversationMetadataReceipt(ExecutionOwner owner, String commandId);

    /**
     * 查询指定归属下活跃会话的列表元数据，包括标题、置顶状态、元数据版本和消息统计。
     *
     * @param owner          由可信主体解析的租户、工作空间及主体归属，不能为 null
     * @param conversationId 待查询的会话标识
     * @return 会话列表元数据；记录不存在、会话非活跃或归属不匹配时返回空 Mono
     */
    Mono<ConversationList.Item> conversationMetadata(ExecutionOwner owner, String conversationId);

    /**
     * 按置顶分区以游标方式读取活跃会话列表，按活动时间及会话标识降序排列。
     *
     * @param owner 由可信主体解析的租户、工作空间及主体归属，不能为 null
     * @param query 查询条件，不能为 null；包含置顶分区、每页数量（1～20）、可选游标及是否返回总数；
     *              游标必须来自同一归属与置顶分区，首次查询时为 null
     * @return 列表记录、下一页游标及是否有更多记录；仅请求总数时填充 total，无匹配记录时 records 为空
     */
    Mono<ConversationList.Page> conversationList(ExecutionOwner owner, ConversationList.Query query);

    /**
     * 在同一事务快照中读取置顶区与普通区窗口，支持从普通会话锚点（含锚点）开始读取。
     * 指定锚点不存在、归属不匹配或已置顶时，普通区回退至首屏并标记 anchorFallback。
     *
     * @param owner 由可信主体解析的租户、工作空间及主体归属，不能为 null
     * @param query 窗口查询条件，不能为 null；普通区请求数量为 1～100，锚点会话标识为 null 时从首屏读取
     * @return 包含两区分页结果、实际锚点及回退标记的窗口；置顶区最多 7 条，两区合计最多 100 条
     */
    Mono<ConversationList.Window> conversationWindow(ExecutionOwner owner, ConversationList.WindowQuery query);

    /**
     * 分批初始化旧会话的列表元数据并推进历史消息统计回填。
     * 由运维可信控制面调用，无公开 HTTP 入口；循环至 hasMore=false，可中断后续跑。
     *
     * @param limit 单次初始化会话数与推进回填任务数各自的上限，范围为 1～100
     * @return 本次初始化数与推进任务数之和，以及是否仍有待处理记录或任务；processed 最多为 limit 的两倍
     */
    Mono<ConversationList.BackfillProgress> backfillConversationMetadata(int limit);

    /**
     * 按会话标识读取指定归属的会话快照。
     *
     * @param owner          由可信主体解析的租户、工作空间及主体归属，不能为 null
     * @param conversationId 待查询的会话标识
     * @return 会话快照；会话不存在或归属不匹配时返回空 Mono
     */
    Mono<Conversation> findConversation(ExecutionOwner owner, String conversationId);

    /**
     * 按页码读取指定归属的会话快照列表，返回总数及当前页记录。
     *
     * @param owner   由可信主体解析的租户、工作空间及主体归属，不能为 null
     * @param current 当前页码，从 1 开始；与 size 计算得到的偏移量不能溢出 long
     * @param size    每页记录数，范围为 1～100
     * @return 会话分页结果；无记录或页码超出结果范围时 records 为空
     */
    Mono<ConversationPage<Conversation>> listConversations(ExecutionOwner owner, long current, long size);

    /**
     * 按轮次序号升序分页读取会话轮次。
     * 归属、版本、计数与分页读取在同一事务中检查，不存在或版本冲突使用明确结果码。
     *
     * @param owner           由可信主体解析的租户、工作空间及主体归属，不能为 null
     * @param conversationId  待查询轮次所属的会话标识
     * @param expectedVersion 预期会话执行版本，必须非负；用于保证轮次读取与会话版本一致
     * @param current         当前页码，从 1 开始；与 size 计算得到的偏移量不能溢出 long
     * @param size            每页轮次数，范围为 1～100
     * @return 成功返回包含分页结果的 APPLIED；会话不存在或归属不匹配返回 NOT_FOUND，
     * 会话版本不匹配返回 VERSION_CONFLICT；成功但无本页记录时 records 为空
     */
    Mono<StoreOutcome<ConversationPage<Turn>>> listTurns(ExecutionOwner owner, String conversationId,
                                                         long expectedVersion, long current, long size);

    /**
     * 读取会话已有轮次的最大序号并加 1，作为下一轮次的候选序号；无轮次时返回 1。
     * 不预留序号，也不作会话版本 CAS；受理事务重新验证版本和序号，不能提前破坏幂等重放。
     *
     * @param owner          由可信主体解析的租户、工作空间及主体归属，不能为 null
     * @param conversationId 待计算下一轮次序号的会话标识
     * @return 下一轮次的候选序号；会话不存在或归属不匹配时返回空 Mono
     */
    Mono<Long> nextTurnSequence(ExecutionOwner owner, String conversationId);

    /**
     * 读取指定会话中的轮次快照，同时检查会话归属和轮次所属会话。
     *
     * @param owner          由可信主体解析的租户、工作空间及主体归属，不能为 null
     * @param conversationId 轮次所属的会话标识
     * @param turnId         待查询的轮次标识
     * @return 轮次快照；会话或轮次不存在、归属不匹配或轮次不属于该会话时返回空 Mono
     */
    Mono<Turn> findTurn(ExecutionOwner owner, String conversationId, String turnId);

    /**
     * 创建预算账户，供后续受理与费用预留使用。
     * 只允许零 held、charged、version 的新账户；不提供静默重置账本的 upsert。
     *
     * @param account 待创建的预算账户，不能为 null；包含预算标识、归属、额度及费率版本，
     *                held、charged 金额及 version 必须为 0，额度与各金额的币种必须一致
     * @return 创建完成后正常结束的 Mono；已有账户或存储失败通过错误信号传播
     */
    Mono<Void> createAccount(BudgetCommands.Account account);
}
