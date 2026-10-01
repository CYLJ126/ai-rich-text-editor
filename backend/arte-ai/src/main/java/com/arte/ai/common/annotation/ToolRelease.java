package com.arte.ai.common.annotation;

import java.lang.annotation.*;

/**
 * 本地 @Tool 方法的独立版本；未声明时继承提供者的默认版本。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/01 ✾
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ToolRelease {
    String version();

    /**
     * 当前执行器明确支持的旧版本契约；服务端还会逐个校验持久化快照。
     */
    String[] compatibleWith() default {};
}
