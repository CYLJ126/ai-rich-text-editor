package com.arte.core.es;

import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 异步批量处理器 —— 缓冲写操作，达到阈值或定时自动 flush
 * 线程安全：每个索引独立队列，基于 ReentrantLock 保护
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/6/14 10:29 ✾
 **/
@Service
@Slf4j
public class BulkProcessor {

    private final ElasticsearchTemplate template;

    private final ElasticsearchProperties.Bulk bulkConfig;

    // 每个索引独立的操作缓冲区
    private final ConcurrentHashMap<String, IndexBuffer> buffers = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler;

    public BulkProcessor(ElasticsearchTemplate template, ElasticsearchProperties props) {
        this.template = template;
        this.bulkConfig = props.bulk();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bulk-flush-scheduler");
            t.setDaemon(true);
            return t;
        });
        // 定时刷新
        scheduler.scheduleAtFixedRate(
                this::flushAll,
                bulkConfig.flushInterval().toSeconds(),
                bulkConfig.flushInterval().toSeconds(),
                TimeUnit.SECONDS);
    }

    /**
     * 添加操作到缓冲区
     */
    public void add(String indexName, BulkOperation operation) {
        IndexBuffer buffer = buffers.computeIfAbsent(indexName, k -> new IndexBuffer());
        buffer.add(operation, () -> flush(indexName, buffer));
    }

    /**
     * 立即刷新指定索引的缓冲区
     */
    public CompletableFuture<Void> flush(String indexName) {
        IndexBuffer buffer = buffers.get(indexName);
        if (buffer == null) return CompletableFuture.completedFuture(null);
        return flush(indexName, buffer);
    }

    private CompletableFuture<Void> flush(String indexName, IndexBuffer buffer) {
        List<BulkOperation> batch = buffer.drain();
        if (batch.isEmpty()) return CompletableFuture.completedFuture(null);
        return template.bulkAsync(indexName, batch)
                .thenAccept(response -> {
                    if (response.errors()) {
                        long errors = response.items().stream()
                                .filter(i -> i.error() != null).count();
                        log.error("批量处理异常，索引：{}，错误数：{}/{}，异常信息：{}", indexName, errors, batch.size(), response);
                    } else {
                        log.debug("批量处理成功，索引：{}，操作数：{}", indexName, batch.size());
                    }
                })
                .exceptionally(e -> {
                    log.error("批量处理异常，索引：{}", indexName, e);
                    return null;
                });
    }

    private void flushAll() {
        buffers.forEach((indexName, buffer) -> {
            if (buffer.size() > 0) {
                flush(indexName, buffer);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        log.info("批量处理器关闭，刷新剩余操作...");
        scheduler.shutdown();
        // 同步刷新剩余数据
        List<CompletableFuture<Void>> futures = buffers.entrySet().stream()
                .map(e -> flush(e.getKey(), e.getValue()))
                .toList();
        try {
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("批量处理器关闭时刷新剩余操作失败", e);
        }
    }

    // ===== 内部缓冲区 =====
    private class IndexBuffer {
        private final ReentrantLock lock = new ReentrantLock();
        private final List<BulkOperation> operations = new ArrayList<>();
        private final AtomicLong byteSize = new AtomicLong(0);

        void add(BulkOperation op, Runnable flushCallback) {
            lock.lock();
            try {
                operations.add(op);
                // 简单估算：每个操作约 1KB，可根据实际序列化大小调整
                long estimatedSize = byteSize.addAndGet(1024);
                if (operations.size() >= bulkConfig.batchSize()
                        || estimatedSize >= bulkConfig.maxBytesPerBatch()) {
                    flushCallback.run();
                }
            } finally {
                lock.unlock();
            }
        }

        List<BulkOperation> drain() {
            lock.lock();
            try {
                if (operations.isEmpty()) return List.of();
                List<BulkOperation> batch = new ArrayList<>(operations);
                operations.clear();
                byteSize.set(0);
                return batch;
            } finally {
                lock.unlock();
            }
        }

        int size() {
            lock.lock();
            try {
                return operations.size();
            } finally {
                lock.unlock();
            }
        }
    }
}

