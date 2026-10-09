package com.arte.core.spi.platform;

/**
 * 生成不透明、不可重复公共 ID
 * <p>
 * 不从用户名或资源归属推算。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface PlatformIdGenerator {

    /**
     * 生成新的不透明、不可重复的公共 ID。
     */
    String nextId();
}
