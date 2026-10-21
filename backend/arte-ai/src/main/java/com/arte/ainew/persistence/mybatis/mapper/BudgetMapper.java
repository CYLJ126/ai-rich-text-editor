package com.arte.ainew.persistence.mybatis.mapper;

import com.arte.ainew.persistence.mybatis.mapper.PersistenceRows.SettlementRow;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 仅供独立的新 AI SqlSessionFactory 使用；SQL 定义在同名 XML 中。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 23:30 ✾
 */
public interface BudgetMapper {
    java.util.List<String> reservationSnapshots(@Param("keys") java.util.List<String> keys);

    int insertAccount(@Param("idKey") String idKey, @Param("ownerKey") String ownerKey, @Param("workspaceSnapshot") String workspaceSnapshot);

    int saveAccount(@Param("workspaceSnapshot") String workspaceSnapshot, @Param("idKey") String idKey);

    int saveReservation(@Param("workspaceSnapshot") String workspaceSnapshot, @Param("idKey") String idKey);

    List<String> reservationForAttempt(@Param("attemptKey") String attemptKey, @Param("invocationKey") String invocationKey);

    int insertReservation(
            @Param("idKey") String idKey,
            @Param("accountKey") String accountKey,
            @Param("invocationKey") String invocationKey,
            @Param("attemptKey") String attemptKey,
            @Param("workspaceSnapshot") String workspaceSnapshot);

    List<SettlementRow> settlement(@Param("reservationKey") String reservationKey, @Param("settlementKey") String settlementKey);

    int insertSettlement(
            @Param("reservationKey") String reservationKey,
            @Param("settlementKey") String settlementKey,
            @Param("digest") String digest,
            @Param("workspaceSnapshot") String workspaceSnapshot,
            @Param("resultSnapshot") String resultSnapshot,
            @Param("evidenceKind") String evidenceKind,
            @Param("evidenceRef") String evidenceRef);

    long countPending(@Param("accountKey") String accountKey, @Param("ownerKey") String ownerKey);

    List<String> pendingSnapshots(@Param("accountKey") String accountKey, @Param("ownerKey") String ownerKey,
                                  @Param("limit") long limit, @Param("offset") long offset);

    String accountSnapshot(@Param("idKey") String idKey, @Param("forUpdate") boolean forUpdate);

    String reservationSnapshot(@Param("idKey") String idKey, @Param("forUpdate") boolean forUpdate);

    record ToolBill(String ownerKey, String invocationKey, String attemptKey, String digest, String snapshot) {
    }

    ToolBill toolBill(@Param("attempt") String attempt);

    int insertToolBill(@Param("r") ToolBill row);

    int ensureToolPriceGuard(@Param("rate") String rate);

    Boolean toolPriceBlocked(@Param("rate") String rate);

    int blockToolPrice(@Param("rate") String rate, @Param("evidence") String evidence);
}
