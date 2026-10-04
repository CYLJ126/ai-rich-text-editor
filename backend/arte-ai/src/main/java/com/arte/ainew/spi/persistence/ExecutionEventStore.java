package com.arte.ainew.spi.persistence;

/**
 * 执行事件存储服务
 * <p>
 * 主要操作：保存输出批次、检查点、游标与终态事件等。
 * 边界：AI 定义重放契约，存储实现可替换，不作为文章正文存储。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:41 ✾
 **/
public interface ExecutionEventStore {
}
