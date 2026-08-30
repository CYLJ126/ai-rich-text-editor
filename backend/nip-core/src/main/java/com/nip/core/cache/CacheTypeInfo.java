package com.nip.core.cache;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface CacheTypeInfo {
    CacheTypeEnum cacheType();

    Class<?> cacheKeyType();

    Class<?> cacheValueType();
}
