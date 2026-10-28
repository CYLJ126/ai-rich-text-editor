package com.arte.ainew.spi.search;

import com.arte.ainew.pojo.search.SearchSession;
import com.arte.core.pojo.platform.ExecutionOwner;

import java.time.Duration;

/**
 * 搜索会话状态管理接口
 * <p>
 * 负责活动名额、操作租约、PIT 引用、有效期及游标签名密钥。
 * 由搜索应用专用有界线程阻塞调用，每次网络操作受剩余时间预算限制。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public interface ConversationSearchSessions {

    /**
     * 原子预占主体的活动搜索名额，创建绑定查询条件、页大小和规则版本的会话，并取得首次操作租约。
     */
    SearchSession reserve(ExecutionOwner owner, String id, String queryHash, int size, String rulesVersion, Duration timeout);

    /**
     * 校验会话归属、有效期及查询条件绑定，获取本次读取的独占操作租约。
     */
    SearchSession acquire(ExecutionOwner owner, String id, String queryHash, int size, Duration timeout);

    /**
     * 在有效操作租约下保存最新 PIT 标识、索引代际和快照到期时间，返回更新后的会话。
     */
    SearchSession pit(ExecutionOwner owner, SearchSession lease, ConversationSearchIndex.Pit pit, long expiresAt, Duration timeout);

    /**
     * 确认本次查询成功，更新成功时间和空闲期限，释放操作租约并返回更新后的会话。
     */
    SearchSession complete(ExecutionOwner owner, SearchSession lease, Duration timeout);

    /**
     * 释放本次操作租约，保留搜索会话且不延长空闲期限，供失败后继续使用。
     */
    void release(ExecutionOwner owner, SearchSession lease, Duration timeout);

    /**
     * 关闭搜索会话并释放活动名额，返回原会话供调用方清理 PIT；不存在或已关闭时返回 null。
     */
    SearchSession close(ExecutionOwner owner, String id, Duration timeout);

    /**
     * 取消首次查询占位并释放名额，写入关闭标记，阻止迟到的预占请求重新创建会话。
     * 返回原会话供调用方清理 PIT，没有原会话时返回 null。
     */
    SearchSession cancelReservation(ExecutionOwner owner, String id, Duration timeout);

    /**
     * 获取或首次原子创建共享的历史游标签名密钥，用于聊天历史翻页游标的签名和校验。
     */
    String historySigningKey(Duration timeout);
}
