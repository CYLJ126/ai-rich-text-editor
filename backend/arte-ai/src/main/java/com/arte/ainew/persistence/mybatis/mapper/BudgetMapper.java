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
    int insertAccount(@Param("idKey") String idKey, @Param("ownerKey") String ownerKey, @Param("snapshot") String snapshot);

    int saveAccount(@Param("snapshot") String snapshot, @Param("idKey") String idKey);

    int saveReservation(@Param("snapshot") String snapshot, @Param("idKey") String idKey);

    List<String> reservationForAttempt(@Param("attemptKey") String attemptKey, @Param("invocationKey") String invocationKey);

    int insertReservation(
            @Param("idKey") String idKey,
            @Param("accountKey") String accountKey,
            @Param("invocationKey") String invocationKey,
            @Param("attemptKey") String attemptKey,
            @Param("snapshot") String snapshot);

    List<SettlementRow> settlement(@Param("reservationKey") String reservationKey, @Param("settlementKey") String settlementKey);

    int insertSettlement(
            @Param("reservationKey") String reservationKey,
            @Param("settlementKey") String settlementKey,
            @Param("digest") String digest,
            @Param("snapshot") String snapshot,
            @Param("resultSnapshot") String resultSnapshot,
            @Param("evidenceKind") String evidenceKind,
            @Param("evidenceRef") String evidenceRef);

    long countPending(@Param("accountKey") String accountKey, @Param("ownerKey") String ownerKey);

    List<String> pendingSnapshots(@Param("accountKey") String accountKey, @Param("ownerKey") String ownerKey,
                                  @Param("limit") long limit, @Param("offset") long offset);

    String accountSnapshot(@Param("idKey") String idKey, @Param("forUpdate") boolean forUpdate);

    String reservationSnapshot(@Param("idKey") String idKey, @Param("forUpdate") boolean forUpdate);
}
