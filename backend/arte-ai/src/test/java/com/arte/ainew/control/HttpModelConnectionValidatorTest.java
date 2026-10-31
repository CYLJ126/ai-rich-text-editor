package com.arte.ainew.control;

import com.arte.ainew.config.NewAiGenerationProperties;
import com.arte.ainew.infrastructure.http.ChatCompletionsSseProtocolAdapter;
import com.arte.ainew.infrastructure.http.HttpModelConnectionValidator;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.model.ModelDrafts.Probe;
import com.arte.core.pojo.reference.DefinitionRef;
import org.junit.Test;
import reactor.core.publisher.Mono;
import reactor.netty.http.server.HttpServer;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public class HttpModelConnectionValidatorTest {

    @Test
    public void probeUsesOnlyModelsGetAndNeverRetriesRedirectsOrReturnsBodies() {
        var calls = new AtomicInteger();
        var server = HttpServer.create().host("127.0.0.1").port(0).route(routes -> routes.get("/v1/models", (request, response) -> {
                            assertEquals("Bearer test-key", request.requestHeaders().get("Authorization"));
                            calls.incrementAndGet();
                            return response.header("Content-Type", "application/json").sendString(Mono.just("{\"data\":[{\"id\":\"test-model\"}]}"));
                        }).get("/auth/models", (request, response) -> response.status(401).sendString(Mono.just("sensitive-provider-error")))
                        .get("/redirect/models", (request, response) -> response.status(302).header("Location", "/v1/models").send())
                        .get("/invalid/models", (request, response) -> response.header("Content-Type", "application/json").sendString(Mono.just("{\"message\":\"test-key\"}")))
                        .get("/large/models", (request, response) -> response.header("Content-Type", "application/json").sendString(Mono.just("x".repeat(4096)))))
                .bindNow();
        try {
            URI origin = URI.create("http://127.0.0.1:" + server.port());
            var props = new NewAiGenerationProperties(false, List.of(origin), List.of(), 0, 0, 0, null, 0, 0);
            var validator = new HttpModelConnectionValidator(props);
            assertTrue(validator.validate(probe(origin, "/v1", "test-model")).block(Duration.ofSeconds(10)).valid());
            assertEquals("MODEL_NOT_FOUND", validator.validate(probe(origin, "/v1", "absent")).block(Duration.ofSeconds(10)).code());
            assertEquals("AUTHENTICATION_FAILED", validator.validate(probe(origin, "/auth", "x")).block(Duration.ofSeconds(10)).code());
            assertEquals("REMOTE_UNAVAILABLE", validator.validate(probe(origin, "/redirect", "x")).block(Duration.ofSeconds(10)).code());
            assertEquals("INVALID_RESPONSE", validator.validate(probe(origin, "/invalid", "x")).block(Duration.ofSeconds(10)).code());
            assertFalse(validator.validate(probe(origin, "/large", "x")).block(Duration.ofSeconds(10)).valid());
            assertEquals(2, calls.get());
            var blocked = new HttpModelConnectionValidator(new NewAiGenerationProperties(false, List.of(URI.create("https://example.invalid")), List.of(), 0, 0, 0, null, 0, 0));
            assertFalse(blocked.validate(probe(origin, "/v1", "x")).block(Duration.ofSeconds(10)).valid());
            assertEquals(2, calls.get());
        } finally {
            server.disposeNow();
        }
    }

    Probe probe(URI origin, String path, String model) {
        var c = new ConnectionDefinition(new DefinitionRef("connection", "template", "v1"), "test", ChatCompletionsSseProtocolAdapter.DEFINITION,
                origin.resolve(path + "/chat/completions"), null, ConnectionDefinition.State.ENABLED, Duration.ofSeconds(2), Duration.ofSeconds(5), 1024);
        return new Probe(c, model, "test-key");
    }
}
