package com.arte.ainew.config;

import com.arte.ainew.spi.persistence.AdmissionCatalogStore;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * 显式运维开关启动一次可恢复的有界回填，不挂在用户目录读取上，不执行 DDL。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/15 09:28 ✾
 */
@Slf4j
public final class ConversationMetadataBackfill {

    private final AdmissionCatalogStore store;
    private Disposable running;

    public ConversationMetadataBackfill(AdmissionCatalogStore store) {
        this.store = store;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        running = Mono.defer(() -> store.backfillConversationMetadata(10))
                .expand(progress -> progress.hasMore()
                        ? Mono.delay(Duration.ofMillis(100)).then(store.backfillConversationMetadata(10)) : Mono.empty())
                .subscribe(progress -> {
                    if (!progress.hasMore()) log.info("AI conversation metadata backfill completed");
                }, error -> log.error("AI conversation metadata backfill stopped; restart with the same switch to resume", error));
    }

    @PreDestroy
    public void stop() {
        if (running != null) running.dispose();
    }
}
