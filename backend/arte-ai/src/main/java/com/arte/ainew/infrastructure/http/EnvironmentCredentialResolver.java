package com.arte.ainew.infrastructure.http;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.config.NewAiGenerationProperties;
import com.arte.ainew.spi.credential.ConnectionCredentialResolver;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 环境变量凭据解析器
 * <p>
 * 只按已登记的 SecretRef 查找环境变量，不接受请求提供的变量名；不缓存凭据值。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 14:01 ✾
 */
public final class EnvironmentCredentialResolver implements ConnectionCredentialResolver {

    private final Map<DefinitionRef, String> names;
    private final Function<String, String> environment;

    public EnvironmentCredentialResolver(NewAiGenerationProperties properties) {
        this(properties, System::getenv);
    }

    public EnvironmentCredentialResolver(NewAiGenerationProperties properties, Function<String, String> environment) {
        this.names = properties.secrets().stream().collect(Collectors.toUnmodifiableMap(
                NewAiGenerationProperties.SecretEnvironment::reference, NewAiGenerationProperties.SecretEnvironment::environmentVariable));
        this.environment = environment;
    }

    @Override
    public Mono<BearerCredential> resolve(DefinitionRef secret, ExecutionContext context) {
        return Mono.fromCallable(() -> {
            String name = names.get(secret);
            if (name == null) {
                throw GenerationException.beforeSend("CREDENTIAL_NOT_AVAILABLE");
            }
            try {
                return new BearerCredential(environment.apply(name));
            } catch (RuntimeException ignored) {
                throw GenerationException.beforeSend("CREDENTIAL_NOT_AVAILABLE");
            }
        });
    }
}
