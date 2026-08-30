@NonNullApi
package com.nip.app.web;

/*
 * 加此注解的原因是该包下的类会报：Not annotated parameter overrides @NonNullApi parameter
 * 因为被重写的方法的包被这个@NonNullApi注解了
 * 如果不需要此注解，也可以在具体方法参数上加javax.annotation.Nonnull注解
 */

import org.springframework.lang.NonNullApi;