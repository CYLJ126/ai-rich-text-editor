package com.arte.core.annotations;

import java.lang.annotation.*;

/**
 * mybatis 查询注解拦截，注解在 Mapper 接口或其中的 Mapper 方法上
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/7/21 20:21 ✾
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface MybatisParams {
    String CREATE_BY = "createBy";
    String UPDATE_BY = "updateBy";
    String CREATE_TIME = "createTime";
    String UPDATE_TIME = "updateTime";

    /**
     * 表名
     */
    String value() default "";

    /**
     * 是否处理
     *
     * @return true-不处理；false-处理；
     */
    boolean ignore() default false;

    /**
     * 要添加到查询中的字段，默认为 [创建人]
     *
     * @return 字段名称列表
     */
    String[] queryFields() default {CREATE_BY};

    /**
     * 要自动插入的字段，默认为 [创建人，创建时间，更新人，更新时间]
     *
     * @return 字段名称列表
     */
    String[] insertFields() default {CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME};

    /**
     * 要自动更新的字段，默认为 [更新人，更新时间]
     *
     * @return 字段名称列表
     */
    String[] updateFields() default {UPDATE_BY, UPDATE_TIME};

}
