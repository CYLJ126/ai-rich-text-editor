package com.arte.app.platform.identity;

import com.arte.core.utils.platform.PlatformChecks;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashSet;
import java.util.List;

/**
 * 只允许部署配置提供经核对的旧主体关联；客户端不能提交这些绑定。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 13:35 ✾
 */
@ConfigurationProperties(prefix = "arte.platform.identity", ignoreUnknownFields = false)
public record PlatformIdentityProperties(boolean enabled, Long legacyUserIdCeiling, List<Binding> bindings) {

    public record Binding(long userId, String subjectId) {
        public Binding {
            if (userId <= 0) throw new IllegalArgumentException("Invalid identity userId");
            PlatformChecks.id(subjectId, "subjectId");
        }
    }

    public PlatformIdentityProperties {
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
        if (enabled && (legacyUserIdCeiling == null || legacyUserIdCeiling < 0)) {
            throw new IllegalArgumentException("Enabled identity requires reviewed legacy-user-id-ceiling");
        }
        var users = new HashSet<Long>();
        var subjects = new HashSet<String>();
        for (var binding : bindings) {
            if (!users.add(binding.userId()) || !subjects.add(binding.subjectId())) {
                throw new IllegalArgumentException("Duplicate identity binding");
            }
        }
    }
}
