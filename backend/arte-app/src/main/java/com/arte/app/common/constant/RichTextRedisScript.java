package com.arte.app.common.constant;

/**
 * 富文本模块使用的 Redis Key 和 Lua 脚本。
 *
 * <p>文章 ES 同步脚本将修改版本与待执行时间保存在 Redis 中，
 * 用于合并同一文章的连续更新，并保证多实例消费时的状态变更具有原子性。</p>
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/8/20 17:28 ✾
 **/
public final class RichTextRedisScript {

    private RichTextRedisScript() {
    }

    /**
     * 文章 ES 同步待执行任务 ZSet。
     * member 为文章 ID，score 为下次允许执行同步的时间戳（毫秒）。
     */
    public static final String ES_SYNC_PENDING_KEY = "arte:{article-es-sync}:pending";

    /**
     * 文章 ES 同步脏版本 Key 前缀，完整 Key 以文章 ID 结尾。
     * 每次文章发生持久化修改时版本号递增。
     */
    public static final String ES_SYNC_DIRTY_VERSION_KEY_PREFIX = "arte:{article-es-sync}:dirty-version:";

    /**
     * 文章 ES 同步分布式锁 Key 前缀，完整 Key 以文章 ID 结尾。
     */
    public static final String ES_SYNC_LOCK_KEY_PREFIX = "arte:article:es:sync:lock:";

    /**
     * 原子标记文章需要同步到 ES。
     *
     * <ul>
     *     <li>KEYS[1]：文章脏版本 Key</li>
     *     <li>KEYS[2]：待同步任务 ZSet Key</li>
     *     <li>ARGV[1]：计划执行时间戳</li>
     *     <li>ARGV[2]：文章 ID</li>
     * </ul>
     *
     * <p>ZADD 使用 NX，确保连续修改只增加脏版本，不会不断推迟已经安排的任务。</p>
     */
    public static final String MARK_ES_SYNC_DIRTY_SCRIPT = """
            redis.call('INCR', KEYS[1])
            redis.call('ZADD', KEYS[2], 'NX', ARGV[1], ARGV[2])
            return 1
            """;

    /**
     * 标记文章已删除，并立即安排 ES 同步任务。
     */
    public static final String MARK_ES_DELETE_DIRTY_SCRIPT = """
            redis.call('INCR', KEYS[1])
            redis.call('ZADD', KEYS[2], ARGV[1], ARGV[2])
            return 1
            """;

    /**
     * 查询已经到期的文章 ES 同步任务。
     *
     * <ul>
     *     <li>KEYS[1]：待同步任务 ZSet Key</li>
     *     <li>ARGV[1]：当前时间戳</li>
     *     <li>ARGV[2]：最大返回数量</li>
     * </ul>
     * <p>
     * 返回按执行时间排序的文章 ID 列表
     */
    public static final String FIND_DUE_ES_SYNC_TASKS_SCRIPT = """
            return redis.call('ZRANGEBYSCORE', KEYS[1], '-inf', ARGV[1], 'LIMIT', 0, ARGV[2])
            """;

    /**
     * 在获得文章级分布式锁后，再次确认任务是否已经到期。
     *
     * <ul>
     *     <li>KEYS[1]：待同步任务 ZSet Key</li>
     *     <li>ARGV[1]：文章 ID</li>
     *     <li>ARGV[2]：当前时间戳</li>
     * </ul>
     * <p>
     * 到期返回 1，否则返回 0
     */
    public static final String IS_DUE_ES_SYNC_TASK_SCRIPT = """
            local score = redis.call('ZSCORE', KEYS[1], ARGV[1])
            if score and tonumber(score) <= tonumber(ARGV[2]) then
                return 1
            end
            return 0
            """;

    /**
     * 完成一次文章 ES 同步，并根据脏版本决定删除任务或安排下一轮同步。
     *
     * <ul>
     *     <li>KEYS[1]：文章脏版本 Key</li>
     *     <li>KEYS[2]：待同步任务 ZSet Key</li>
     *     <li>ARGV[1]：本次处理的脏版本</li>
     *     <li>ARGV[2]：下一次计划执行时间戳</li>
     *     <li>ARGV[3]：文章 ID</li>
     * </ul>
     *
     * <p>当前版本大于本次处理版本，表示同步过程中又发生了修改，需要保留任务；
     * 否则删除任务。</p>
     * <p>
     * 返回当前脏版本
     */
    public static final String COMPLETE_ES_SYNC_TASK_SCRIPT = """
            local currentVersion = tonumber(redis.call('GET', KEYS[1]) or '0')
            if currentVersion > tonumber(ARGV[1]) then
                redis.call('ZADD', KEYS[2], ARGV[2], ARGV[3])
            else
                redis.call('ZREM', KEYS[2], ARGV[3])
            end
            return currentVersion
            """;

    /**
     * 将同步失败的文章重新安排到待同步任务 ZSet。
     *
     * <ul>
     *     <li>KEYS[1]：待同步任务 ZSet Key</li>
     *     <li>ARGV[1]：重试时间戳</li>
     *     <li>ARGV[2]：文章 ID</li>
     * </ul>
     */
    public static final String RESCHEDULE_ES_SYNC_TASK_SCRIPT = """
            redis.call('ZADD', KEYS[1], ARGV[1], ARGV[2])
            return 1
            """;
}
