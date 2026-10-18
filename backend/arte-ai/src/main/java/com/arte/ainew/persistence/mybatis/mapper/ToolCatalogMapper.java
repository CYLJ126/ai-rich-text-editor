package com.arte.ainew.persistence.mybatis.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * TOOL 版本存储的固定 SQL
 * <p>
 * 只注册到隔离工厂，所有写入由同一 Spring 事务协调。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 13:52 ✾
 */
public interface ToolCatalogMapper {
    /**
     * 重复键也取得排他锁，避免 INSERT IGNORE 的共享锁再升级。
     */
    int ensureCatalogLock(@Param("key") String key);

    record Row(String idKey, String identityKey, String versionKey, String providerKey, String inputSchemaKey,
               String outputSchemaKey, String configurationSchemaKey, String payloadDigest,
               String workspaceSnapshot, long createdMicros) {
    }

    Row provider(@Param("id") String id, @Param("lock") boolean lock);

    Row schema(@Param("id") String id, @Param("lock") boolean lock);

    Row tool(@Param("id") String id, @Param("lock") boolean lock);

    int insertProvider(@Param("r") Row row);

    int insertSchema(@Param("r") Row row);

    int insertTool(@Param("r") Row row);

    List<Row> providers(@Param("after") String after, @Param("limit") int limit);

    List<Row> tools(@Param("provider") String provider, @Param("after") String after, @Param("limit") int limit);

    List<Row> allTools(@Param("after") String after, @Param("limit") int limit);
}
