package com.arte.core.annotations;

import java.lang.annotation.*;

/**
 * 用于标记匿名访问方法
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/2/5 21:21 ✾
 */
@Inherited
@Documented
@Target({ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface AnonymousAccess {

}
