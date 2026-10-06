package com.arte.ainew.application.support;

import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.exception.CommonException;

import java.io.Serial;
import java.util.Objects;

/**
 * 新 AI 准入业务异常，统一使用 CommonException 中的 resultCode 表达错误身份。
 * getResultCode() 用于内部枚举判断，其 getCode() 用于标准响应数字码；
 * 展示文案来自国际化白名单，不包含请求全文、SQL、凭据或原始异常。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
public final class AdmissionException extends CommonException {

    @Serial
    private static final long serialVersionUID = -3174910369826263500L;

    /**
     * 使用已登记的新 AI 结果码构造异常，拒绝其他模块的结果码。
     */
    public AdmissionException(ResultCodeEnum resultCode) {
        super(requireAdmissionResultCode(resultCode));
    }

    /**
     * 将存储契约中的可预期拒绝映射为应用结果码，不依赖枚举名称拼接。
     * 成功值不可转换为异常；穷尽分支使新增存储结果必须显式决定其映射。
     * 数据库／编码故障仍按原 onError 传播，不通过此方法包装。
     */
    public static AdmissionException fromStoreRejection(StoreOutcome.Code code) {
        return new AdmissionException(switch (Objects.requireNonNull(code, "code")) {
            case NOT_FOUND -> ResultCodeEnum.AI_NOT_FOUND;
            case OWNER_MISMATCH -> ResultCodeEnum.AI_OWNER_MISMATCH;
            case VERSION_CONFLICT -> ResultCodeEnum.AI_VERSION_CONFLICT;
            case IDEMPOTENCY_CONFLICT -> ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT;
            case LEASE_LOST -> ResultCodeEnum.AI_LEASE_LOST;
            case INVALID_STATE -> ResultCodeEnum.AI_INVALID_STATE;
            case RECONCILIATION_REQUIRED -> ResultCodeEnum.AI_RECONCILIATION_REQUIRED;
            case CONVERSATION_BUSY -> ResultCodeEnum.AI_CONVERSATION_BUSY;
            case INSUFFICIENT_BUDGET -> ResultCodeEnum.AI_INSUFFICIENT_BUDGET;
            case CURRENCY_MISMATCH -> ResultCodeEnum.AI_CURRENCY_MISMATCH;
            case RATE_MISMATCH -> ResultCodeEnum.AI_RATE_MISMATCH;
            case CURSOR_EXPIRED -> ResultCodeEnum.AI_CURSOR_EXPIRED;
            case APPLIED, REPLAYED ->
                    throw new IllegalArgumentException("Successful store outcome cannot become an admission exception");
        });
    }

    /**
     * 按读取时的语言解析安全文案，避免将异步工作线程的默认语言固定到 HTTP 响应中。
     * 通用 ResultContext 异常转换通过此方法取得文案，通过 getResultCode() 取得数字码。
     */
    @Override
    public String getMessage() {
        return getResultCode().getDesc();
    }

    private static ResultCodeEnum requireAdmissionResultCode(ResultCodeEnum resultCode) {
        if (resultCode == null || !resultCode.name().startsWith("AI_")) {
            throw new IllegalArgumentException("AI admission result code required");
        }
        return resultCode;
    }
}
