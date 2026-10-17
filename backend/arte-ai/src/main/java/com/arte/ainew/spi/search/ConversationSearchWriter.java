package com.arte.ainew.spi.search;

import com.arte.ainew.pojo.search.SearchSync.Mutation;
import com.arte.ainew.pojo.search.SearchSync.Scope;
import com.arte.ainew.pojo.search.SearchSync.WriteResult;

import java.util.List;

/**
 * 会话搜索 ES 写入接口
 * <p>
 * 负责批量更新或删除索引记录、清理归档残留，以及创建和激活索引代际。
 * 由搜索专用调度器同步调用，记录写入固定物理代际。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public interface ConversationSearchWriter {

    List<WriteResult> write(String generation, List<Mutation> mutations, Runnable beforeSend) throws Exception;

    default List<WriteResult> write(String generation, List<Mutation> mutations) throws Exception {
        return write(generation, mutations, () -> {
        });
    }

    /**
     * 归档清理包含不在后台账本中的残留，条件删除不覆盖并发恢复。
     */
    boolean purgeArchived(String generation, Scope scope, Runnable beforeDelete) throws Exception;

    void createGeneration(String generation) throws Exception;

    void activateGeneration(String generation) throws Exception;
}
