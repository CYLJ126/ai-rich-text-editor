package com.arte.ainew.spi.assistant;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.pojo.context.AssistantSource;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.core.pojo.reference.DefinitionRef;
import reactor.core.publisher.Mono;

/**
 * 助手执行接入接口
 * <p>
 * 向上下文组装提供服务端确认的指令和来源，并在建会话、受理、派发及外发前复核当前使用资格。
 * 发布来源按会话绑定的精确版本解析，试用来源按试用记录及其原始输入快照核对。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/7 00:45 ✾
 */
public interface AssistantContextAccess {

    /**
     * 供上下文组装使用的助手来源及指令。
     *
     * @param source      助手的精确发布版本或草稿试用来源，随输入快照持久化。
     * @param instruction 由服务端写入系统消息的助手指令，包含工作指令和预期输出说明。
     */
    record Prompt(AssistantSource source, String instruction) {
    }

    /**
     * 异步解析会话绑定的精确发布版本，校验助手状态和当前主体的使用资格。
     *
     * @param context 包含可信主体、租户和工作空间的执行上下文，用于核对身份、助手归属和使用权限。
     * @param profile 类型为 {@code chat-profile} 的精确发布版本引用，可指向已有会话固定的历史版本。
     * @return 用于组装输入的助手来源和指令。配置不可用或无使用资格时发出错误信号。
     */
    Mono<Prompt> resolve(ExecutionContext context, DefinitionRef profile);

    /**
     * 在创建会话的原写事务中校验助手使用资格，并确认引用仍指向当前发布版本。校验失败时抛出异常。
     *
     * @param context 新会话的可信执行上下文，提供当前主体和空间归属。
     * @param profile 新会话拟绑定的精确聊天配置引用。
     */
    void requireNewConversation(ExecutionContext context, DefinitionRef profile);

    /**
     * 在调用方的当前写事务中复核助手资格与输入快照的一致性。
     * <p>
     * 发布来源校验精确版本及系统指令，试用来源校验草稿试用权限、发起人、执行标识和原始快照。校验失败时抛出异常。
     * 用于受理和派发写入前的最终检查，使资格复核与业务写入处于同一事务。
     *
     * @param context 本次执行的可信上下文，提供主体、空间及执行标识。
     * @param snapshot 已冻结的执行输入快照，必须包含助手来源。
     */
    void requireCurrent(ExecutionContext context, ContextSnapshot snapshot);

    /**
     * 以异步方式在可写事务内复核执行资格和快照来源，供派发及外发前的检查使用。
     * <p>
     * 校验内容与 {@link #requireCurrent(ExecutionContext, ContextSnapshot)} 一致，由实现负责安排事务及持久化调用。
     *
     * @param context 本次执行的可信上下文，提供主体、空间及执行标识。
     * @param snapshot 已冻结的执行输入快照，必须包含助手来源。
     * @return 校验通过时正常完成，校验失败时发出错误信号。
     */
    Mono<Void> requireEligible(ExecutionContext context, ContextSnapshot snapshot);
}
