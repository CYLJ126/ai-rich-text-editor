package com.arte.core.annotations;

import java.lang.annotation.*;

/**
 * MyBatis 审计与创建人条件策略，支持实体、Mapper 接口和方法
 * 优先级：方法 > Mapper > 实体
 * queryFields 同时约束 SELECT、UPDATE 和 DELETE；这不是完整的权限或租户隔离策略。
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
     * 要添加到 SELECT、UPDATE、DELETE 条件中的字段，默认为 [创建人]；空数组表示共享表策略。
     *
     * @return 字段名称列表
     */
    String[] queryFields() default {CREATE_BY};

    /**
     * 要自动插入的字段，默认为 [创建人，创建时间，更新人，更新时间]；同名显式值也由可信上下文覆盖。
     *
     * @return 字段名称列表
     */
    String[] insertFields() default {CREATE_BY, CREATE_TIME, UPDATE_BY, UPDATE_TIME};

    /**
     * 要自动更新的字段，默认为 [更新人，更新时间]；同名显式值也由可信上下文覆盖。
     *
     * @return 字段名称列表
     */
    String[] updateFields() default {UPDATE_BY, UPDATE_TIME};

}
