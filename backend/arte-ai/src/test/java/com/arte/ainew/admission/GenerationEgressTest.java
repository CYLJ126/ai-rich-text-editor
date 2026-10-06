package com.arte.ainew.admission;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.config.NewAiGenerationProperties;
import com.arte.ainew.infrastructure.http.EnvironmentCredentialResolver;
import com.arte.ainew.infrastructure.http.GenerationException;
import com.arte.ainew.infrastructure.http.HttpEgressPolicy;
import io.netty.util.concurrent.DefaultEventExecutor;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/**
 * 无网络验证出口及已校验地址固定，不依赖实际 DNS 或供应商密钥。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 14:01 ✾
 */
public class GenerationEgressTest {

    private static NewAiGenerationProperties properties(List<URI> origins, List<NewAiGenerationProperties.SecretEnvironment> secrets) {
        return new NewAiGenerationProperties(true, origins, secrets, 0, 0, 0, null, 0, 0);
    }

    @Test
    public void originIsExactAndAllDnsAnswersMustBePublic() throws Exception {
        var endpoint = URI.create("https://api.deepseek.com/v1");
        var policy = new HttpEgressPolicy(properties(null, List.of()));
        policy.validateOrigin(endpoint);
        assertThrows(GenerationException.class, () -> policy.validateOrigin(URI.create("https://api.deepseek.com.evil.test/v1")));
        assertThrows(GenerationException.class, () -> policy.validateOrigin(URI.create("https://api.deepseek.com:444/v1")));
        for (String address : List.of("127.0.0.1", "10.0.0.1", "169.254.169.254", "100.64.0.1", "::1", "fc00::1")) {
            var ip = InetAddress.getByName(address);
            assertThrows(GenerationException.class, () -> policy.validateAddresses(endpoint, new InetAddress[]{ip}));
        }
        var publicIp = InetAddress.getByAddress(new byte[]{8, 8, 8, 8});
        assertEquals(List.of(publicIp), policy.validateAddresses(endpoint, new InetAddress[]{publicIp}));
        assertThrows(GenerationException.class, () -> policy.validateAddresses(endpoint,
                new InetAddress[]{publicIp, InetAddress.getLoopbackAddress()}));
    }

    @Test
    public void explicitLoopbackTestOriginAndPinnedResolverCannotSwitchTargets() throws Exception {
        var endpoint = URI.create("http://127.0.0.1:8080/v1");
        var policy = new HttpEgressPolicy(properties(List.of(URI.create("http://127.0.0.1:8080")), List.of()));
        var addresses = policy.validateAddresses(endpoint, new InetAddress[]{InetAddress.getByName("127.0.0.1")});
        var group = HttpEgressPolicy.pinnedResolver(endpoint, addresses);
        var executor = new DefaultEventExecutor();
        try {
            var resolver = group.getResolver(executor);
            assertEquals(addresses.getFirst(), resolver.resolve(InetSocketAddress.createUnresolved("127.0.0.1", 8080)).sync().getNow().getAddress());
            var denied = resolver.resolve(InetSocketAddress.createUnresolved("other.test", 8080)).await();
            assertFalse(denied.isSuccess());
            assertTrue(denied.cause() instanceof GenerationException);
        } finally {
            group.close();
            executor.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS).sync();
        }
        assertThrows(IllegalArgumentException.class, () -> properties(List.of(URI.create("http://example.test")), List.of()));
    }

    @Test
    public void secretLookupIsColdExplicitAndDoesNotCacheValuesOrExposeThem() {
        var ref = new DefinitionRef("secret", "deepseek", "v1");
        var count = new AtomicInteger();
        var value = new AtomicReference<>("credential-one");
        var resolver = new EnvironmentCredentialResolver(properties(null,
                List.of(new NewAiGenerationProperties.SecretEnvironment(ref, "ARTE_TEST_KEY"))), name -> {
            assertEquals("ARTE_TEST_KEY", name);
            count.incrementAndGet();
            return value.get();
        });
        var cold = resolver.resolve(ref, null);
        assertEquals(0, count.get());
        assertFalse(cold.block().toString().contains("credential-one"));
        value.set("credential-two");
        assertEquals("credential-two", cold.block().token());
        assertEquals(2, count.get());
        assertThrows(GenerationException.class, () -> resolver.resolve(new DefinitionRef("secret", "other", "v1"), null).block());
        assertEquals(2, count.get());
        value.set("bad\r\nAuthorization");
        assertThrows(GenerationException.class, cold::block);
    }
}
