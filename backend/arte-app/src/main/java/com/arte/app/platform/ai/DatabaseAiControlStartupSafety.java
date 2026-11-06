package com.arte.app.platform.ai;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * DATABASE 启动核验数据库权威可读；head=0 合法，缺表／指针或数据库故障不可伪装成空目录。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/01 13:50 ✾
 */
@Component
@ConditionalOnProperty(prefix = "arte.ai-new", name = "configuration-mode", havingValue = "DATABASE")
public final class DatabaseAiControlStartupSafety implements SmartInitializingSingleton {

    private final DatabaseAiControlService source;
    private final org.springframework.core.env.Environment environment;

    public DatabaseAiControlStartupSafety(DatabaseAiControlService source, org.springframework.core.env.Environment environment) {
        this.source = source;
        this.environment = environment;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (environment.acceptsProfiles(org.springframework.core.env.Profiles.of("ai-fixed"))) {
            throw new IllegalStateException("DATABASE mode must not load the ai-fixed file definitions profile");
        }
        source.definitions();
        source.workspaceSafety();
        source.egressSafety();
    }
}
