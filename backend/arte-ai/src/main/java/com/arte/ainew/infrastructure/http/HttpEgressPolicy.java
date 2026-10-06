package com.arte.ainew.infrastructure.http;

import com.arte.ainew.config.NewAiGenerationProperties;
import io.netty.resolver.AbstractAddressResolver;
import io.netty.resolver.AddressResolver;
import io.netty.resolver.AddressResolverGroup;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.Promise;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * 精确 origin 白名单及 DNS 地址校验；解析结果固定到本次连接，避免校验后重新解析目标。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/6 14:01 ✾
 */
public final class HttpEgressPolicy {

    private final List<URI> origins;

    public HttpEgressPolicy(NewAiGenerationProperties properties) {
        origins = properties.allowedOrigins();
    }

    public void validateOrigin(URI endpoint) {
        if (origins.stream().noneMatch(origin -> sameOrigin(origin, endpoint))) {
            throw GenerationException.beforeSend("EGRESS_ORIGIN_DENIED");
        }
    }

    public List<InetAddress> validateAddresses(URI endpoint, InetAddress[] addresses) {
        validateOrigin(endpoint);
        boolean loopback = endpoint.getHost().equals("127.0.0.1") || endpoint.getHost().equalsIgnoreCase("localhost");
        if (addresses.length == 0 || Arrays.stream(addresses).anyMatch(address -> loopback
                ? !address.isLoopbackAddress() : !publicAddress(address))) {
            throw GenerationException.beforeSend("EGRESS_ADDRESS_DENIED");
        }
        return List.copyOf(Arrays.asList(addresses));
    }

    private static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        var bytes = address.getAddress();
        if (bytes.length == 16) {
            return (bytes[0] & 0xfe) != 0xfc;
        }
        int first = bytes[0] & 255, second = bytes[1] & 255;
        return first != 0 && first < 224 && !(first == 100 && second >= 64 && second <= 127)
                && !(first == 198 && (second == 18 || second == 19))
                && !(first == 192 && second == 0);
    }

    public static int port(URI uri) {
        return uri.getPort() == -1 ? (uri.getScheme().equalsIgnoreCase("https") ? 443 : 80) : uri.getPort();
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme()) && left.getHost().equalsIgnoreCase(right.getHost())
                && port(left) == port(right);
    }

    /**
     * Netty 只使用已校验地址；不在事件线程执行 DNS，也不接受重定向或替换目标。
     */
    public static AddressResolverGroup<InetSocketAddress> pinnedResolver(URI endpoint, List<InetAddress> addresses) {
        return new AddressResolverGroup<>() {
            @Override
            protected AddressResolver<InetSocketAddress> newResolver(EventExecutor executor) {
                return new AbstractAddressResolver<>(executor, InetSocketAddress.class) {
                    @Override
                    protected boolean doIsResolved(InetSocketAddress address) {
                        return false;
                    }

                    private List<InetSocketAddress> targets(InetSocketAddress address) {
                        if (!address.getHostString().equalsIgnoreCase(endpoint.getHost()) || address.getPort() != port(endpoint)) {
                            throw GenerationException.beforeSend("EGRESS_ORIGIN_DENIED");
                        }
                        return addresses.stream().map(ip -> new InetSocketAddress(ip, address.getPort())).toList();
                    }

                    @Override
                    protected void doResolve(InetSocketAddress address, Promise<InetSocketAddress> promise) {
                        try {
                            promise.setSuccess(targets(address).getFirst());
                        } catch (RuntimeException error) {
                            promise.setFailure(error);
                        }
                    }

                    @Override
                    protected void doResolveAll(InetSocketAddress address, Promise<List<InetSocketAddress>> promise) {
                        try {
                            promise.setSuccess(targets(address));
                        } catch (RuntimeException error) {
                            promise.setFailure(error);
                        }
                    }
                };
            }
        };
    }
}
