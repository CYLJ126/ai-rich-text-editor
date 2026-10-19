package com.arte.ainew.persistence.mybatis.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 审批索引只用于定位；每次读取重验载荷、归属、版本及投影。锁顺序固定 Invocation → approval。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 00:05 ✾
 */
public interface ToolApprovalMapper {

    record Row(String invocationKey, String ownerKey, String approvalKey, String state, long version,
               long expiresAt, String payloadDigest, String snapshot) {
    }

    Row find(@Param("invocation") String invocation, @Param("current") boolean current);

    int insert(@Param("r") Row row);

    int save(@Param("r") Row row, @Param("expected") long expected);

    List<String> expired(@Param("now") long now, @Param("limit") int limit);
}
