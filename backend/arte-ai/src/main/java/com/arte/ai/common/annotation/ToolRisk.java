package com.arte.ai.common.annotation;

import com.arte.ai.common.enums.tool.ToolRiskLevelEnum;

import java.lang.annotation.*;

/**
 * 本地 Spring AI {@code @Tool} 方法的风险声明，由 LocalToolProvider 加载。
 * 只读标记描述方法的实际行为，不会阻止方法内部执行写操作。
 * 未标注的方法沿用默认的中风险、非只读画像。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/01 ✾
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ToolRisk {

    ToolRiskLevelEnum level() default ToolRiskLevelEnum.MEDIUM;

    boolean readOnly() default false;

    boolean destructive() default false;

    boolean reversible() default false;

    boolean idempotent() default false;

    boolean openWorld() default false;

    String[] requiredScopes() default {};

    String[] allowedNetworkTargets() default {};
}
