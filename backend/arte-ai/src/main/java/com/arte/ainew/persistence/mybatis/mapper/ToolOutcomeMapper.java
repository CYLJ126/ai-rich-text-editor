package com.arte.ainew.persistence.mybatis.mapper;

import com.arte.ainew.pojo.tool.ToolOutcomeCheck;
import com.arte.ainew.pojo.tool.ToolReconciliation;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 自动核对工作项与证据索引；只在新 AI 独立工厂登记。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 10:48 ✾
 */
public interface ToolOutcomeMapper {

    ToolReconciliation.Review review(@Param("key") String key);

    int insertReview(@Param("r") ToolReconciliation.Review review);

    int saveReview(@Param("r") ToolReconciliation.Review review);

    List<ToolOutcomeCheck.Case> page(@Param("space") String space, @Param("after") String after, @Param("limit") int limit);

    ToolReconciliation.Audit command(@Param("key") String key);

    int insertCommand(@Param("r") ToolReconciliation.Audit row);

    List<ToolReconciliation.Audit> history(@Param("key") String key);

    List<ToolOutcomeCheck.Case> alerts(@Param("now") long now, @Param("before") long before);

    record ScanRow(String idKey) {
    }

    record ScanCursor(String afterKey, boolean completed) {
    }

    int lockScan();

    ScanCursor scanCursor();

    List<ScanRow> scan(@Param("after") String after);

    int advanceScan(@Param("after") String after, @Param("completed") boolean completed);

    ToolOutcomeCheck.Case find(@Param("key") String key, @Param("current") boolean current);

    List<ToolOutcomeCheck.Case> due(@Param("now") long now, @Param("limit") int limit);

    int insert(@Param("r") ToolOutcomeCheck.Case row);

    int save(@Param("r") ToolOutcomeCheck.Case row, @Param("expected") long expected);

    int evidence(@Param("invocation") String invocation, @Param("key") String key, @Param("number") int number,
                 @Param("decision") String decision, @Param("reference") String reference, @Param("digest") String digest,
                 @Param("resultDigest") String resultDigest, @Param("now") long now);
}
