package com.arte.ainew.spi.search;

import com.arte.ainew.pojo.search.SearchSync.*;

import java.util.List;

/**
 * 会话搜索同步的数据源接口
 * <p>
 * 扫描已提交的会话、轮次和模型调用，提取当前索引记录及归档资格。
 * 仅由后台有界工作线程调用，直接读取权威数据，不依赖业务通知。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public interface ConversationSearchSource {

    List<Unit> scan(Kind kind, String after, int size);

    Snapshot read(Unit unit, Scope previousScope);

    interface ReadBatch {

        Snapshot read(Unit unit, Scope previousScope);

        default void refreshEligibility() {
        }
    }

    /**
     * 一个工作批次按 owner 合并资格读取；发送前可重新批量复核。
     */
    default ReadBatch batch(List<Work> work) {
        return this::read;
    }
}
