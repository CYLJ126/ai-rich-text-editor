package com.arte.app.pojo;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.Accessors;

import java.io.Serial;

/**
 * 沿用 Integer 主键的旧业务参数；公共查询字段见 {@link BaseQueryParam}。
 * 账号参数单独声明 Long 主键，不改变其他模块的 ID 类型。
 */
@Getter
@Setter
@ToString(callSuper = true)
@Accessors(chain = true)
@JsonTypeInfo(use = JsonTypeInfo.Id.NONE)
public class BaseParam<T> extends BaseQueryParam<T> {
    @Serial
    private static final long serialVersionUID = -1974913303781148610L;

    private Integer id;
}
