package com.arte.core.enums;

import com.arte.core.i18n.MessageUtils;
import lombok.Getter;

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
     * 系统或模块：01-core包；02-study包；03-待定；04-wechat包；
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
}
