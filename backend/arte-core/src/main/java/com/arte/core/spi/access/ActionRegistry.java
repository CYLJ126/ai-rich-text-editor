package com.arte.core.spi.access;

import com.arte.core.pojo.access.Action;
import com.arte.core.pojo.access.ActionDefinition;

import java.util.Optional;

/**
 * 受信动作登记
 * <p>
 * 未知动作默认拒绝，不从 HTTP 动态注册，不解释通配符。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface ActionRegistry {

    /**
     * 查找动作定义
     *
     * @param action 动作
     * @return 动作定义
     */
    Optional<ActionDefinition> find(Action action);
}
