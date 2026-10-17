package com.arte.ainew.pojo.search;

import com.arte.ainew.spi.search.ConversationSearchIndex.Pit;

/**
 * Redis 仅保存控制状态；不含标题、正文、片段或授权决定。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public record SearchSession(String id, String ownerKey, String queryHash, int size, String rulesVersion, String secret,
                            String pitId, String generation, long createdAt, long lastSuccessAt, long hardExpiresAt,
                            long idleExpiresAt, long pitExpiresAt, String operationToken, long operationUntil,
                            long serverNow) {

    public Pit pit() {
        return pitId == null || pitId.isEmpty() ? null : new Pit(pitId, generation);
    }
}
