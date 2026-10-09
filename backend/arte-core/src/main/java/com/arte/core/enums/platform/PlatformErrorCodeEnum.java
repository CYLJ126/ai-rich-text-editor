package com.arte.core.enums.platform;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 公共体系错误
 * <p>
 * HTTP 映射由 app 完成。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
@Getter
public enum PlatformErrorCodeEnum implements IEnum<Integer>, MyEnum<Integer> {
    INVALID_ARGUMENT(100, "参数无效"),
    UNKNOWN_ACTION(101, "动作未登记"),
    UNAUTHENTICATED(102, "未认证"),
    ACCESS_DENIED(103, "权限不足"),
    POLICY_DENIED(104, "政策禁止"),
    NOT_FOUND(105, "不存在或不可见"),
    VERSION_CONFLICT(106, "版本冲突"),
    MEMBER_ROLE_CONFLICT(107, "成员角色冲突"),
    OWNER_TRANSFER_REQUIRED(108, "请先交接负责人"),
    IDEMPOTENCY_CONFLICT(109, "操作键冲突"),
    SPACE_READ_ONLY(110, "空间只读"),
    SPACE_CLOSING(111, "空间关闭处理中"),
    CAPABILITY_NOT_ENABLED(112, "能力尚未开放"),
    AUTHORITY_UNAVAILABLE(113, "无法确认当前授权"),
    IDENTITY_CONFLICT(114, "稳定身份关联冲突");

    private final Integer value;
    private final String description;

    PlatformErrorCodeEnum(int value, String description) {
        this.value = value;
        this.description = description;
    }

}
