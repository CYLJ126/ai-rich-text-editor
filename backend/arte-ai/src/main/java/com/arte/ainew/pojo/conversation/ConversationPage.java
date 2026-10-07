package com.arte.ainew.pojo.conversation;

import com.arte.ainew.common.validation.ContractChecks;

import java.util.List;

/**
 * 有界数据库查询结果；不包含 HTTP 状态或可变请求对象。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/7 15:38 ✾
 */
public record ConversationPage<T>(long current, long size, long total, List<T> records) {

    public ConversationPage {
        ContractChecks.range(current, "current", 1, Long.MAX_VALUE);
        ContractChecks.range(size, "size", 1, 100);
        ContractChecks.range(total, "total", 0, Long.MAX_VALUE);
        records = ContractChecks.list(records, "records", 0, (int) size);
    }
}
