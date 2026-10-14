package com.arte.app.platform.persistence.mapper;

import com.arte.app.pojo.platform.PlatformAiControlRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 与公共治理事务使用同一物理数据源，不注册到旧业务 Mapper 工厂。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/13 17:22 ✾
 */
public interface PlatformAiControlMapper {

    Long head(@Param("lock") boolean lock);

    /**
     * 当前读发布版本，MySQL 使用共享锁避免不同主体的复核互相排斥。
     */
    Long headCurrent();

    String revision(@Param("version") long version);

    int insertRevision(@Param("version") long version, @Param("payload") String payload, @Param("actor") String actor);

    int advance(@Param("expected") long expected, @Param("version") long version);

    PlatformAiControlRow workspace(@Param("tenant") String tenant, @Param("workspace") String workspace);

    PlatformAiControlRow workspaceRead(@Param("tenant") String tenant, @Param("workspace") String workspace);

    List<PlatformAiControlRow> budgetsRead(@Param("tenant") String tenant, @Param("workspace") String workspace, @Param("subject") String subject);

    int insertWorkspace(@Param("row") PlatformAiControlRow row);

    int replaceWorkspace(@Param("row") PlatformAiControlRow row, @Param("expected") long expected);

    List<PlatformAiControlRow> budgets(@Param("tenant") String tenant, @Param("workspace") String workspace, @Param("subject") String subject);

    List<PlatformAiControlRow> workspaceBudgets(@Param("tenant") String tenant, @Param("workspace") String workspace);

    int insertBudget(@Param("row") PlatformAiControlRow row);

    int replaceBudget(@Param("row") PlatformAiControlRow row, @Param("expected") long expected);

    String account(@Param("key") String key, @Param("owner") String owner);

    int insertAccount(@Param("key") String key, @Param("owner") String owner, @Param("payload") String payload);

    int replaceAccount(@Param("key") String key, @Param("owner") String owner, @Param("payload") String payload);
}
