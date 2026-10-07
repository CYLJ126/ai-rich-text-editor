package com.arte.core.enums;

import com.arte.core.i18n.MessageUtils;
import lombok.Getter;

import java.util.Locale;

/**
 * 结果码枚举，请按如下规则定义需要的结果码，纯数字，只有当数字不够表示时才用A-Z
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2024/7/12 23:25 ✾
 */
@Getter
public enum ResultCodeEnum {
    /**
     * 状态表示（1位）+系统或模块标识（2位）+下标（3位）
     * 状态：成功-1；失败-2；异常-3；未知-4；
     * 系统或模块：01-core包；02-study包；03-待定；04-wechat包；05-新 AI 准入；
     * 已发布的历史结果码保持原值，不随模块说明调整。
     */
    SUCCESS("100000", "result.success"),
    FAIL("200000", "result.fail"),
    FAIL_AUTH("200001", "result.fail.auth"),
    ADD_EXCEPTION("200002", "result.fail.add"),
    UPDATE_EXCEPTION("200003", "result.fail.update"),
    DELETE_EXCEPTION("200004", "result.fail.delete"),
    EXCEPTION("300000", "result.exception"),
    DB_EXCEPTION("300001", "result.exception.db"),
    RETRIEVAL_EXCEPTION("300002", "result.exception.retrieval"),
    SYSTEM_EXCEPTION("301001", "result.exception.system"),
    CHAT_EXCEPTION("302001", "result.exception.chat"),
    WITHOUT_CONVERSATION("302002", "result.exception.conversationNotFound"),
    WITHOUT_MESSAGE("302003", "result.exception.messageNotFound"),
    WITHOUT_FATHER_MESSAGE("302004", "result.exception.parentMessageNotFound"),
    CONNECTION_EXCEPTION("303001", "result.exception.connection"),

    // 新 AI 准入：205xxx 表示可预期拒绝，305001 为未知准入错误兜底。
    // AdmissionException 直接持有这些枚举值；已发布数字码不得重排或复用。
    /**
     * 配置不存在或当前主体无权使用。
     */
    AI_CONFIGURATION_NOT_AVAILABLE("205001", "result.ai.admission.configurationNotAvailable"),
    /**
     * 能力或连接已停用。
     */
    AI_CAPABILITY_DISABLED("205002", "result.ai.admission.capabilityDisabled"),
    /**
     * 当前不支持该能力或输入格式。
     */
    AI_UNSUPPORTED_CAPABILITY("205003", "result.ai.admission.unsupportedCapability"),
    /**
     * 请求超过执行限制。
     */
    AI_EXECUTION_LIMIT_EXCEEDED("205004", "result.ai.admission.executionLimitExceeded"),
    /**
     * 执行期限已过。
     */
    AI_DEADLINE_EXCEEDED("205005", "result.ai.admission.deadlineExceeded"),
    /**
     * 输入超过大小限制。
     */
    AI_INPUT_LIMIT_EXCEEDED("205006", "result.ai.admission.inputLimitExceeded"),
    /**
     * 当前仅支持单条用户文本。
     */
    AI_ONLY_SINGLE_USER_TEXT_SUPPORTED("205007", "result.ai.admission.onlySingleUserTextSupported"),
    /**
     * 当前不支持所选上下文配置。
     */
    AI_CONTEXT_SELECTION_NOT_SUPPORTED("205008", "result.ai.admission.contextSelectionNotSupported"),
    /**
     * 上下文容量与绑定配置不一致。
     */
    AI_CONTEXT_CAPACITY_MISMATCH("205009", "result.ai.admission.contextCapacityMismatch"),
    /**
     * 输入超过上下文容量。
     */
    AI_CONTEXT_CAPACITY_EXCEEDED("205010", "result.ai.admission.contextCapacityExceeded"),
    /**
     * 上下文快照校验失败。
     */
    AI_INVALID_CONTEXT_SNAPSHOT("205011", "result.ai.admission.invalidContextSnapshot"),
    /**
     * 上下文快照已过期或时间信息无效。
     */
    AI_CONTEXT_EXPIRED_OR_INVALID("205012", "result.ai.admission.contextExpiredOrInvalid"),
    /**
     * 上下文快照不存在或无权访问。
     */
    AI_CONTEXT_NOT_FOUND("205013", "result.ai.admission.contextNotFound"),
    /**
     * 输出 Token 上限超过上下文预留。
     */
    AI_OUTPUT_RESERVATION_EXCEEDED("205014", "result.ai.admission.outputReservationExceeded"),
    /**
     * 当前不支持所选会话配置。
     */
    AI_CONVERSATION_CONFIGURATION_NOT_SUPPORTED("205015", "result.ai.admission.conversationConfigurationNotSupported"),
    /**
     * 会话不存在或无权访问。
     */
    AI_CONVERSATION_NOT_FOUND("205016", "result.ai.admission.conversationNotFound"),
    /**
     * 会话当前不可提交调用。
     */
    AI_CONVERSATION_NOT_ACTIVE("205017", "result.ai.admission.conversationNotActive"),
    /**
     * 轮次不存在或无权访问。
     */
    AI_TURN_NOT_FOUND("205018", "result.ai.admission.turnNotFound"),
    /**
     * 当前不支持历史选择。
     */
    AI_HISTORY_NOT_SUPPORTED("205019", "result.ai.admission.historyNotSupported"),
    /**
     * 当前不支持所选聊天配置或分支。
     */
    AI_CHAT_SELECTION_NOT_SUPPORTED("205020", "result.ai.admission.chatSelectionNotSupported"),
    /**
     * 当前不支持重新生成。
     */
    AI_REGENERATION_NOT_SUPPORTED("205021", "result.ai.admission.regenerationNotSupported"),
    /**
     * 新轮次校验失败。
     */
    AI_INVALID_NEW_TURN("205022", "result.ai.admission.invalidNewTurn"),
    /**
     * 预算不存在或当前主体无权使用。
     */
    AI_BUDGET_NOT_AVAILABLE("205023", "result.ai.admission.budgetNotAvailable"),
    /**
     * 预算账户尚未初始化。
     */
    AI_BUDGET_NOT_INITIALIZED("205024", "result.ai.admission.budgetNotInitialized"),
    /**
     * 预算账户与当前配置不一致。
     */
    AI_BUDGET_CONFIGURATION_CONFLICT("205025", "result.ai.admission.budgetConfigurationConflict"),
    /**
     * 费率版本不一致。
     */
    AI_RATE_MISMATCH("205026", "result.ai.admission.rateMismatch"),
    /**
     * 幂等键已用于不同的请求内容。
     */
    AI_IDEMPOTENCY_CONFLICT("205027", "result.ai.admission.idempotencyConflict"),
    /**
     * 目标执行记录不存在或无权访问。
     */
    AI_NOT_FOUND("205028", "result.ai.admission.notFound"),
    /**
     * 执行记录归属不匹配。
     */
    AI_OWNER_MISMATCH("205029", "result.ai.admission.ownerMismatch"),
    /**
     * 执行记录版本已变化。
     */
    AI_VERSION_CONFLICT("205030", "result.ai.admission.versionConflict"),
    /**
     * 执行租约已失效。
     */
    AI_LEASE_LOST("205031", "result.ai.admission.leaseLost"),
    /**
     * 当前执行状态不允许此操作。
     */
    AI_INVALID_STATE("205032", "result.ai.admission.invalidState"),
    /**
     * 执行结果待核对，不能直接重试。
     */
    AI_RECONCILIATION_REQUIRED("205033", "result.ai.admission.reconciliationRequired"),
    /**
     * 会话已有活跃调用。
     */
    AI_CONVERSATION_BUSY("205034", "result.ai.admission.conversationBusy"),
    /**
     * 可用预算不足。
     */
    AI_INSUFFICIENT_BUDGET("205035", "result.ai.admission.insufficientBudget"),
    /**
     * 预算币种不一致。
     */
    AI_CURRENCY_MISMATCH("205036", "result.ai.admission.currencyMismatch"),
    /**
     * 事件游标已过保留范围。
     */
    AI_CURSOR_EXPIRED("205037", "result.ai.admission.cursorExpired"),
    /**
     * 模型派发尚未启用。
     */
    AI_DISPATCH_NOT_ENABLED("205038", "result.ai.admission.dispatchNotEnabled"),
    /**
     * 执行核对尚未启用。
     */
    AI_RECONCILIATION_NOT_ENABLED("205039", "result.ai.admission.reconciliationNotEnabled"),
    /**
     * 执行控制尚未启用。
     */
    AI_CONTROL_NOT_ENABLED("205040", "result.ai.admission.controlNotEnabled"),
    /**
     * 尚无可读取的权威结果（可先查询执行状态或读取已保存事件）。
     */
    AI_RESULT_NOT_AVAILABLE("205041", "result.ai.admission.resultNotAvailable"),
    /**
     * 实时事件观看尚未启用。
     */
    AI_EVENT_WATCH_NOT_ENABLED("205042", "result.ai.admission.eventWatchNotEnabled"),
    /**
     * AI 请求受理异常。
     */
    AI_ADMISSION_EXCEPTION("305001", "result.ai.admission.admissionException"),

    UNKNOWN("400000", "result.unknown");

    private final String code;
    private final String desc;

    ResultCodeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 返回按当前语言翻译后的描述文案（desc 存储的是 message key）
     */
    public String getDesc() {
        return MessageUtils.get(desc);
    }

    /**
     * 返回按指定语言翻译的标准文案，适用于跨线程响应构建。
     */
    public String getDesc(Locale locale) {
        return MessageUtils.get(locale, desc);
    }
}
