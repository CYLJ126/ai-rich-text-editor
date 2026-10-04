package com.arte.ainew.spi.persistence;

/**
 * 执行事件存储服务
 * <p>
 * 主要操作：保存输出批次、检查点、游标与终态事件等。
 * 边界：AI 定义重放契约，存储实现可替换，不作为文章正文存储。
 * <p>sequence 在 executionId 内跨 Attempt 单调递增；游标为排他读取位置，不含访问凭据。
 * 输出批次保存后才可发布，编解码仅接受已登记负载类型及版本，重放不执行供应商调用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:41 ✾
 **/
public interface ExecutionEventStore {
}
