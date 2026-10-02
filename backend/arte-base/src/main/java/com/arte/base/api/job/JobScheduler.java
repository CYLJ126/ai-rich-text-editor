package com.arte.base.api.job;

/**
 * 通用后台工作调度。
 *
 * <p>管理 Job 提交、查询、领取、续租、取消请求、重试、完成和死信；拥有调度状态权威，业务处理器由所属模块接入。
 *
 * <p>设计依据：ARTE 顶层需求及设计，顶层接口 §1.3。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public interface JobScheduler {
}
