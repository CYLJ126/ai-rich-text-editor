package com.arte.ainew.persistence.mybatis.mapper;

import org.apache.ibatis.annotations.Param;

/**
 * 本地完整刷新命令及不可变审计，复用目录锁表。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 12:52 ✾
 */
public interface ToolDiscoveryMapper {

    record Row(String commandKey, String requestDigest, String payloadDigest, String snapshot) {
    }

    Row toolDiscoveryCommand(@Param("key") String key);

    int insertToolDiscoveryCommand(@Param("r") Row row);
}
