package com.arte.core.pojo;

/**
 * 返回结果的公共状态接口，仅定义 code、desc、success。
 * 单值数据由 ResultContext 承载，列表及其操作由 PageView 承载。
 * <p>
 * 类型信息由序列化器配置决定，HTTP 响应不携带 Java 类名；
 * 需要恢复运行时类型的缓存使用启用类型信息的序列化器。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/2/7 10:18 ✾
 */
public interface IResult {

    String BUSINESS_SUCCESS_LABEL = "操作成功";

    String BUSINESS_FAIL_LABEL = "操作失败";

    String getCode();

    String getDesc();

    Boolean getSuccess();

    IResult setCode(String code);

    IResult setDesc(String desc);

    IResult setSuccess(Boolean success);

    default boolean isSuccess() {
        return Boolean.TRUE.equals(getSuccess());
    }

}
