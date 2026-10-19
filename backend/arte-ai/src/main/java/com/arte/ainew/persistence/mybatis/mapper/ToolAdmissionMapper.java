package com.arte.ainew.persistence.mybatis.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * TOOL 执行快照的一对一存储及稳定绑定重放定位；只注册到新 AI 独立工厂。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 16:30 ✾
 */
public interface ToolAdmissionMapper {

    /**
     * 不可变快照及校验／防重投影；不保存可变执行状态，全部键使用长度前缀哈希。
     */
    record Row(String invocationKey, String ownerKey, String bindingKey, String idempotencyKey,
               String submissionDigest, String requestDigest, String payloadDigest, String snapshot) {
    }

    Row toolSnapshot(@Param("invocation") String invocationKey, @Param("owner") String ownerKey);

    /**
     * 提交内的幂等定位须当前读，避免等待同键提交后仍沿用此前授权产生的 MVCC 快照。
     */
    Row accepted(@Param("owner") String ownerKey, @Param("binding") String bindingKey,
                 @Param("key") String idempotencyKey, @Param("current") boolean currentRead);

    int insertToolSnapshot(@Param("r") Row row);
}
