package com.arte.app.platform.persistence.mapper;

import com.arte.core.pojo.platform.Scope;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 租户模型策略和目标空间索引的专用 SQL。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/01 19:25 ✾
 */
public interface PlatformModelSharingMapper {

    record Row(String resourceKey, String tenantId, long version, String payload) {
    }

    Row find(@Param("key") String key, @Param("current") boolean current);

    List<Scope> scopes(@Param("key") String key, @Param("current") boolean current);

    List<Row> tenantRows(@Param("tenant") String tenant, @Param("current") boolean current);

    int insert(@Param("row") Row row);

    int replace(@Param("row") Row row, @Param("expected") long expected);

    int clearScopes(@Param("key") String key);

    int insertScope(@Param("key") String key, @Param("tenant") String tenant, @Param("workspace") String workspace);
}
