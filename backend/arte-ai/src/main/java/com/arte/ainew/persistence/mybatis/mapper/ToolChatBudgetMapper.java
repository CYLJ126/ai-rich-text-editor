package com.arte.ainew.persistence.mybatis.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Run 预算行按币种有序锁定，Allocation 用原 Reservation 和 Attempt 唯一关联。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 20:20 ✾
 */
public interface ToolChatBudgetMapper {

    record Balance(String runKey, String currency, long version, String digest, String snapshot) {
    }

    record Allocation(String runKey, String invocationKey, String attemptKey, int attemptNumber,
                      String chargeKind, String reservationKey, String digest, String snapshot) {
    }

    List<Balance> balances(@Param("run") String run, @Param("lock") boolean lock);

    int insertBalance(@Param("b") Balance balance);

    int saveBalance(@Param("b") Balance balance, @Param("expected") long expected);

    Allocation allocation(@Param("reservation") String reservation, @Param("lock") boolean lock);

    int insertAllocation(@Param("a") Allocation allocation);

    int saveAllocation(@Param("a") Allocation allocation);
}
