package com.arte.ainew.spi.model;

import com.arte.ainew.pojo.model.ModelSelection;
import com.arte.core.pojo.platform.Scope;

import java.util.List;

/**
 * 空间实际模型启用的权威读取端口
 * <p>
 * 未装配时没有已启用模型，不能自动采用发布。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/03 14:20 ✾
 */
@FunctionalInterface
public interface ModelSelectionSource {

    /**
     * 在调用方已有事务中读取目标执行空间；current 为 true 时须读取当前事实。
     */
    List<ModelSelection> list(Scope scope, boolean current);
}
