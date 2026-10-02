package com.arte.base.api.security;

/**
 * 主体与资源动作授权。
 *
 * <p>验证主体、租户、空间及资源动作权限；在关键读取、外发、写入和结果查询边界重新授权。领域规则和任务授权由所属模块补充。
 *
 * <p>设计依据：ARTE 顶层需求及设计，顶层接口 §1.3。
 * 本轮声明职责与 Java 类型；操作签名及运行行为在后续详细设计中补充。
 */
public interface AuthorizationService {
}
