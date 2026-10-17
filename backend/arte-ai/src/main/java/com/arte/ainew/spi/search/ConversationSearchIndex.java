package com.arte.ainew.spi.search;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.search.SearchText;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.pojo.search.ConversationSearch;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;

/**
 * 会话搜索的内部 ES 读取接口
 * <p>
 * 负责打开搜索快照、分页读取命中及关闭快照。
 * 原始命中仍须经应用层当前授权／归档校验后才对外返回。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 07:25 ✾
 */
public interface ConversationSearchIndex {

    /**
     * 搜索快照标识及其所属物理索引代际，用于保持同一次搜索的视图一致。
     */
    record Pit(String id, String generation) {
        public Pit {
            ContractChecks.text(id, "pitId", 32768);
            ContractChecks.text(generation, "generation", 256);
        }
    }

    /**
     * 快照内的分页读取请求，携带用户范围、搜索条件、翻页方向及排序边界。
     */
    record Read(ExecutionOwner owner, String query, List<String> groupIds, Pit pit,
                ConversationSearch.Direction direction, List<Object> after, int size, Duration keepAlive) {
        public Read {
            Objects.requireNonNull(owner, "owner");
            query = SearchText.query(query);
            groupIds = SearchText.groups(groupIds);
            Objects.requireNonNull(pit, "pit");
            Objects.requireNonNull(direction, "direction");
            // FIRST has no cursor; BACKWARD values retain ES's original sort order and precision.
            Objects.requireNonNull(after, "after");
            ContractChecks.require(after.isEmpty() || after.size() == 4, "Complete ES sort required");
            after.forEach(v -> {
                ContractChecks.require(v == null || v instanceof String || v instanceof Number, "Invalid sort value");
                ContractChecks.require(!(v instanceof Double d) || Double.isFinite(d), "Invalid sort number");
                ContractChecks.require(!(v instanceof Float f) || Float.isFinite(f), "Invalid sort number");
            });
            after = List.copyOf(after);
            ContractChecks.require(direction != ConversationSearch.Direction.FIRST || after.isEmpty(), "FIRST has no boundary");
            ContractChecks.range(size, "size", 1, 1000);
            ContractChecks.require(keepAlive != null && keepAlive.toMillis() >= 1 && keepAlive.compareTo(Duration.ofHours(24)) <= 0, "Invalid PIT retention");
        }
    }

    /**
     * 单条原始命中，包含记录标识、源字段、分页排序值及高亮片段。
     */
    record Hit(String entryKey, Map<String, Object> source, List<Object> sort, Map<String, List<String>> highlights) {
        public Hit {
            ContractChecks.digest(entryKey, "entryKey");
            source = Collections.unmodifiableMap(new LinkedHashMap<>(source));
            ContractChecks.require(sort.size() == 4, "Complete ES sort required");
            sort = List.copyOf(sort);
            var copied = new LinkedHashMap<String, List<String>>();
            highlights.forEach((k, v) -> copied.put(k, List.copyOf(v)));
            highlights = Collections.unmodifiableMap(copied);
        }
    }

    /**
     * 一批原始搜索命中及本次读取后的最新快照标识。
     */
    record Batch(Pit pit, List<Hit> hits) {
        public Batch {
            hits = List.copyOf(hits);
        }
    }

    Mono<Pit> open(Duration keepAlive);

    Mono<Batch> read(Read request);

    Mono<Void> close(Pit pit);
}
