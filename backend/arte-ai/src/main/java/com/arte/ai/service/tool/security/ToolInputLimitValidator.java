package com.arte.ai.service.tool.security;

import com.arte.ai.config.ToolExecutionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.Map;
import java.util.Set;

/**
 * 在反序列化为具体工具请求前实施统一输入体积上限。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
@Component
@RequiredArgsConstructor
public class ToolInputLimitValidator {

    private static final Set<String> CREDENTIAL_FIELDS = Set.of("password", "passwd", "secret",
            "clientsecret", "apikey", "privatekey", "authorization", "cookie",
            "accesstoken", "refreshtoken", "credentialreference", "credentialref");

    private final ObjectMapper objectMapper;
    private final ToolExecutionProperties properties;

    public void validate(Map<String, Object> arguments) {
        rejectCredentialMaterial(arguments, "$arguments");
        try {
            int bytes = objectMapper.writeValueAsBytes(arguments).length;
            if (bytes > properties.getMaximumInputBytes()) {
                throw new IllegalArgumentException("tool arguments exceed maximum input size of "
                        + properties.getMaximumInputBytes() + " bytes");
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("tool arguments cannot be serialized", exception);
        }
    }

    private void rejectCredentialMaterial(Object value, String path) {
        if (value instanceof Map<?, ?> map) {
            map.forEach((key, item) -> {
                String name = String.valueOf(key);
                String normalized = name.replace("-", "").replace("_", "")
                        .replace(".", "").toLowerCase(java.util.Locale.ROOT);
                if (CREDENTIAL_FIELDS.contains(normalized)) {
                    throw new IllegalArgumentException(path + "." + name
                            + " must use a server-side credential binding instead of request arguments");
                }
                rejectCredentialMaterial(item, path + "." + name);
            });
        } else if (value instanceof Collection<?> collection) {
            int index = 0;
            for (Object item : collection) rejectCredentialMaterial(item, path + "[" + index++ + "]");
        }
    }
}
