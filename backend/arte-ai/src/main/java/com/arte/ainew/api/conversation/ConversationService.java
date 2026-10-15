package com.arte.ainew.api.conversation;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.reference.ResourceRef;
import com.arte.ainew.pojo.context.ContextRequest;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.conversation.ConversationList;
import com.arte.ainew.pojo.conversation.ConversationPage;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.core.pojo.PageParam;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 会话服务
 * <p>
 * 创建／查询／命名／删除会话，关联资料，管理历史等。
 * 边界：资料关联使用通用引用，不自动授予资源访问权。
 * <p>Conversation 与 Turn 管理归属、版本、历史路径和回答候选；引用 Invocation 查询执行状态，
 * 不维护第二份终态权威。普通轮次按会话版本受理，候选引用与当前主体归属须校验。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:25 ✾
 **/
public interface ConversationService {

    /**
     * 创建会话，由服务端分配会话 ID，并从可信执行上下文确定归属。
     * 同一归属下相同幂等键与请求摘要重放首次创建的会话，摘要不一致时拒绝；资料关联不授予后续读取权限。
     * 当前实现仅支持无聊天配置、无关联资料的会话。
     *
     * @param title       会话标题，不能为空或空白，最多 256 个 UTF-16 单元
     * @param chatProfile 可选聊天配置引用，类型为 chat-profile；当前实现要求为 null，否则返回配置不支持错误
     * @param resources   关联资料引用列表，不能为 null；当前实现要求为空列表，否则返回配置不支持错误
     * @param context     可信执行上下文，不能为 null；须包含 ai:conversation 权限、有效截止时间及创建操作幂等键，
     *                    写入前重新校验当前授权
     * @return 新建会话或幂等重放会话的当前快照；授权失败、期限超时或幂等冲突时通过错误信号失败
     */
    Mono<Conversation> create(String title, DefinitionRef chatProfile, List<ResourceRef> resources, ExecutionContext context);

    /**
     * 创建默认会话，标题按 Asia/Shanghai 本地日期生成，格式为“新会话yyyyMMdd”。
     * 不关联聊天配置或资料；请求摘要不含日期，同键跨午夜重试仍返回首次创建的会话。
     *
     * @param context 可信执行上下文，不能为 null；须包含 ai:conversation 权限、有效截止时间及创建操作幂等键，
     *                写入前重新校验当前授权
     * @return 新建默认会话或幂等重放会话的当前快照；授权失败、期限超时或幂等冲突时通过错误信号失败
     */
    Mono<Conversation> createDefault(ExecutionContext context);

    /**
     * 轻量读取当前归属下指定活跃会话的列表元数据，不刷新目录或改变会话选择。
     *
     * @param conversationId 待查询的会话标识，不能为空或空白，最多 256 个 UTF-16 单元
     * @param context        可信执行上下文，不能为 null；须包含 ai:conversation 权限及有效截止时间，查询范围由其归属确定
     * @return 包含标题、置顶状态、元数据版本及消息统计的列表项；记录不存在、会话非活跃或归属不匹配时，
     * 返回 AI_CONVERSATION_NOT_FOUND 错误
     */
    Mono<ConversationList.Item> metadata(String conversationId, ExecutionContext context);

    /**
     * 按置顶分区以游标方式查询当前归属下的活跃会话，按活动时间及会话标识降序排列。
     *
     * @param query   查询条件，不能为 null；指定置顶分区、每页数量（1～20）、可选游标及是否返回总数；
     *                首次查询的游标为 null，后续游标必须来自同一归属与置顶分区
     * @param context 可信执行上下文，不能为 null；须包含 ai:conversation 权限及有效截止时间，查询范围由其归属确定
     * @return 当前页记录、下一页游标及是否有更多记录；仅 includeTotal 为 true 时返回总数，无匹配记录时 records 为空
     */
    Mono<ConversationList.Page> listCursor(ConversationList.Query query, ExecutionContext context);

    /**
     * 在同一事务快照中读取当前归属的置顶区与普通区窗口，支持从普通会话锚点（含锚点）开始读取。
     * 指定锚点不存在、不可见或已置顶时，普通区回退至首屏并标记 anchorFallback。
     *
     * @param query   窗口条件，不能为 null；普通区请求数量为 1～100，锚点会话标识为 null 时从首屏读取
     * @param context 可信执行上下文，不能为 null；须包含 ai:conversation 权限及有效截止时间，查询范围由其归属确定
     * @return 包含两区分页结果、实际锚点及回退标记的窗口；置顶区最多 7 条，两区合计最多 100 条
     */
    Mono<ConversationList.Window> listWindow(ConversationList.WindowQuery query, ExecutionContext context);

    /**
     * 按预期元数据版本修改活跃会话的标题或置顶状态，并返回持久化回执；不推进会话执行版本。
     * 两种操作共用归属范围内的幂等键空间，相同请求重放首次成功回执或拒绝结果。
     *
     * @param command 修改命令，不能为 null；包含会话标识、非负预期元数据版本及幂等键，
     *                标题与置顶状态必须且只能指定一项；标题去除首尾空白后须非空且最多 256 个 UTF-16 单元
     * @param context 可信执行上下文，不能为 null；须包含 ai:conversation 权限及有效截止时间，
     *                其幂等键必须与 command 的幂等键一致，写入前重新校验当前授权
     * @return 本次提交或首次成功提交的回执，包含列表项、置顶数及是否发生修改；会话不可见、版本冲突、
     * 幂等冲突或超过同一归属 7 个置顶名额时通过错误信号失败
     */
    Mono<ConversationList.Receipt> changeMetadata(ConversationList.Command command, ExecutionContext context);

    /**
     * 查询当前归属下已持久化的元数据修改命令结果，包括成功回执与拒绝结果。
     *
     * @param commandId 命令标识，即修改元数据时使用的幂等键，不能为空或空白，最多 256 个 UTF-16 单元
     * @param context   可信执行上下文，不能为 null；须包含 ai:conversation 权限及有效截止时间，查询范围由其归属确定
     * @return 首次提交的命令结果；该归属下不存在命令记录时返回 AI_NOT_FOUND 错误
     */
    Mono<ConversationList.CommandResult> metadataReceipt(String commandId, ExecutionContext context);

    /**
     * 在当前授权及归属范围内查询会话快照；不存在或不可见时明确失败。
     *
     * @param conversationId 待查询的会话标识，不能为空或空白，最多 256 个 UTF-16 单元
     * @param context        可信执行上下文，不能为 null；须包含 ai:conversation 权限及有效截止时间，查询范围由其归属确定
     * @return 会话快照；会话不存在或归属不匹配时返回 AI_CONVERSATION_NOT_FOUND 错误
     */
    Mono<Conversation> find(String conversationId, ExecutionContext context);

    /**
     * 按页码查询当前归属的会话快照列表，使用数据库分页并按会话键稳定排序；不修改请求分页对象。
     *
     * @param page    分页条件，不能为 null；current 从 1 开始，size 为 1～100，
     *                页码与页大小计算得到的偏移量不能溢出 long
     * @param context 可信执行上下文，不能为 null；须包含 ai:conversation 权限及有效截止时间，查询范围由其归属确定
     * @return 包含页码、页大小、总数及当前页会话记录的分页结果；无记录或超出结果范围时 records 为空
     */
    Mono<ConversationPage<Conversation>> list(PageParam page, ExecutionContext context);

    /**
     * 按轮次序号升序分页展示会话历史，在同一事务中检查归属、会话版本并读取总数与本页记录。
     * 固定会话执行版本，与生成时的历史路径选择独立。
     *
     * @param conversationId  待查询轮次所属的会话标识，不能为空或空白，最多 256 个 UTF-16 单元
     * @param expectedVersion 预期会话执行版本，必须非负，与列表项的 metadataVersion 不同
     * @param page            分页条件，不能为 null；current 从 1 开始，size 为 1～100，
     *                        页码与页大小计算得到的偏移量不能溢出 long
     * @param context         可信执行上下文，不能为 null；须包含 ai:conversation 权限及有效截止时间，查询范围由其归属确定
     * @return 轮次分页结果，无本页记录时 records 为空；会话不存在或归属不匹配返回 AI_CONVERSATION_NOT_FOUND，
     * 会话版本冲突时通过错误信号失败
     */
    Mono<ConversationPage<Turn>> turns(String conversationId, long expectedVersion, PageParam page, ExecutionContext context);

    /**
     * 查询单个轮次快照，校验轮次属于目标会话且会话归属于当前主体；不复制 Invocation 执行状态。
     *
     * @param conversationId 轮次所属的会话标识，不能为空或空白，最多 256 个 UTF-16 单元
     * @param turnId         待查询的轮次标识，不能为空或空白，最多 256 个 UTF-16 单元
     * @param context        可信执行上下文，不能为 null；须包含 ai:conversation 权限及有效截止时间，查询范围由其归属确定
     * @return 轮次快照；会话或轮次不存在、归属不匹配或轮次不属于该会话时返回 AI_TURN_NOT_FOUND 错误
     */
    Mono<Turn> turn(String conversationId, String turnId, ExecutionContext context);

    /**
     * 按固定版本和历史路径选取有界轮次；版本冲突明确拒绝，不自动读取之后新增的历史。
     * 当前实现尚不支持历史选择，调用时直接返回 AI_HISTORY_NOT_SUPPORTED 错误。
     *
     * @param selection 历史选择条件，不能为 null；包含会话标识、非负会话执行版本、显式轮次标识列表及轮次上限（1～256）；
     *                  轮次标识不得重复且数量不得超过上限，空列表表示选择该版本路径上的最近轮次
     * @param context   可信执行上下文，用于确定查询归属与授权范围；当前实现直接拒绝历史选择，不执行授权或历史读取
     * @return 契约为选中的轮次列表；当前实现返回携带 AI_HISTORY_NOT_SUPPORTED 错误的 Mono
     */
    Mono<List<Turn>> history(ContextRequest.HistorySelection selection, ExecutionContext context);
}
