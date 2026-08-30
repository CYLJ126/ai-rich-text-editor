package com.arte.core.enums;

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
    SUCCESS("100000", "成功"),
    FAIL("200000", "失败"),
    FAIL_AUTH("200001", "认证失败"),
    ADD_EXCEPTION("200002", "添加异常"),
    UPDATE_EXCEPTION("200003", "更新异常"),
    DELETE_EXCEPTION("200004", "删除异常"),
    EXCEPTION("300000", "异常"),
    DB_EXCEPTION("300001", "数据库异常"),
    RETRIEVAL_EXCEPTION("300002", "检索异常"),
    SYSTEM_EXCEPTION("301001", "系统异常"),
    CHAT_EXCEPTION("302001", "聊天异常"),
    WITHOUT_CONVERSATION("302002", "找不到会话"),
    WITHOUT_MESSAGE("302003", "找不到消息"),
    WITHOUT_FATHER_MESSAGE("302004", "找不到父消息"),
    CONNECTION_EXCEPTION("303001", "连接异常"),
    UNKNOWN("400000", "未知");

    private final String code;
    private final String desc;

    ResultCodeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
