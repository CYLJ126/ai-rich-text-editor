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

    int insertRevision(@Param("version") long version, @Param("payload") String payload, @Param("actor") String actor);

    int insertRules(@Param("row") PlatformAiControlRow row);

    int insertWorkspace(@Param("row") PlatformAiControlRow row);

    int insertBudget(@Param("row") PlatformAiControlRow row);

    int insertAccount(@Param("key") String key, @Param("owner") String owner, @Param("payload") String payload);

    int replaceAccount(@Param("key") String key, @Param("owner") String owner, @Param("payload") String payload);

    int replaceBudget(@Param("row") PlatformAiControlRow row, @Param("expected") long expected);

    int replaceWorkspace(@Param("row") PlatformAiControlRow row, @Param("expected") long expected);

    int replaceRules(@Param("row") PlatformAiControlRow row, @Param("expected") long expected);

    Long head(@Param("lock") boolean lock);

    PlatformAiControlRow modelEgress(@Param("lock") boolean lock);

    int replaceModelEgress(@Param("expected") long expected, @Param("version") long version, @Param("payload") String payload);

    /**
     * 当前读发布版本，MySQL 使用共享锁避免不同主体的复核互相排斥。
     */
    Long headCurrent();

    String revision(@Param("version") long version);

    int advance(@Param("expected") long expected, @Param("version") long version);

    PlatformAiControlRow workspace(@Param("tenant") String tenant, @Param("workspace") String workspace);

    PlatformAiControlRow workspaceRead(@Param("tenant") String tenant, @Param("workspace") String workspace);

    /**
     * 独立预算规则按范围锁定，与模型启用共享公共空间治理门控。
     */
    PlatformAiControlRow rules(@Param("tenant") String tenant, @Param("workspace") String workspace, @Param("current") boolean current);

    /**
     * 启动时校验安装表存在；不执行 DDL。
     */
    int rulesSafety();

    List<PlatformAiControlRow> budgetsRead(@Param("tenant") String tenant, @Param("workspace") String workspace, @Param("subject") String subject);

    List<PlatformAiControlRow> budgets(@Param("tenant") String tenant, @Param("workspace") String workspace, @Param("subject") String subject);

    List<PlatformAiControlRow> workspaceBudgets(@Param("tenant") String tenant, @Param("workspace") String workspace);

    String account(@Param("key") String key, @Param("owner") String owner);
}
