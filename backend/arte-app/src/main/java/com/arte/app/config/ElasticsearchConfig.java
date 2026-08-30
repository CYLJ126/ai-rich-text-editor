package com.arte.app.config;

import co.elastic.clients.elasticsearch.ElasticsearchAsyncClient;
import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.Jackson3JsonpMapper;
import co.elastic.clients.transport.TransportUtils;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import co.elastic.clients.transport.rest5_client.low_level.Rest5ClientBuilder;
import com.arte.core.es.ElasticsearchProperties;
import com.arte.core.serialize.SerializerFactory;
import org.apache.hc.client5.http.impl.async.HttpAsyncClientBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultProxyRoutePlanner;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.client5.http.ssl.TrustAllStrategy;
import org.apache.hc.core5.ssl.SSLContextBuilder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.hc.core5.util.Timeout;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.client.ClientConfiguration;
import org.springframework.data.elasticsearch.client.elc.rest5_client.Rest5Clients;
import org.springframework.data.elasticsearch.support.HttpHeaders;
import tools.jackson.databind.json.JsonMapper;

import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Configuration
@EnableConfigurationProperties(ElasticsearchProperties.class)
public class ElasticsearchConfig {

    /**
     * 专用于 Elasticsearch 序列化的 ObjectMapper
     */
    private final static JsonMapper ELASTICSEARCH_OBJECT_MAPPER = SerializerFactory.buildJsonMapperWithoutTypeProperty();

    private final ElasticsearchProperties props;

    public ElasticsearchConfig(ElasticsearchProperties props) {
        this.props = props;
    }

    @Bean
    public ClientConfiguration clientConfiguration() {
        ClientConfiguration.MaybeSecureClientConfigurationBuilder builder = ClientConfiguration.builder().connectedTo(resolveEndpoints());
        builder.withConnectTimeout(props.connectTimeout());
        builder.withSocketTimeout(props.socketTimeout());
        if (hasText(props.username()) && hasText(props.password())) {
            builder.withBasicAuth(props.username(), props.password());
        }

        // === 新增：如果配置的地址是 https，则显式启用 SSL ===
        boolean isSsl = props.uris().stream().anyMatch(uri -> uri.toLowerCase().startsWith("https"));
        if (isSsl) {
            builder.usingSsl();
        }

        return builder.build();
    }

    /**
     * 底层 HTTP 客户端（Apache HttpClient5 异步），
     * 兼容 ES 客户端 9.x 连接 ES 服务端 8.x 的核心 Bean。
     */
    @Bean(destroyMethod = "close")
    public Rest5Client rest5Client() {
        return buildRest5Client(clientConfiguration());
    }

    /**
     * 同步客户端，供 ElasticsearchTemplate、IndexManager 直接注入使用。
     */
    @Bean
    public ElasticsearchClient elasticsearchClient(Rest5Client rest5Client) {
        Rest5ClientTransport transport = new Rest5ClientTransport(rest5Client, new Jackson3JsonpMapper(ELASTICSEARCH_OBJECT_MAPPER));
        return new ElasticsearchClient(transport);
    }

    /**
     * 异步客户端，供 ElasticsearchTemplate、BulkProcessor 的异步方法使用。
     */
    @Bean
    public ElasticsearchAsyncClient elasticsearchAsyncClient(Rest5Client rest5Client) {
        Rest5ClientTransport transport = new Rest5ClientTransport(
                rest5Client, new Jackson3JsonpMapper(ELASTICSEARCH_OBJECT_MAPPER));
        return new ElasticsearchAsyncClient(transport);
    }

    /**
     * 参考 {@link Rest5Clients#getRest5ClientBuilder(ClientConfiguration)}
     * 在客户端版本为 9.X 的情况下，兼容 elasticsearch 8.X 服务端，注入 Content-Type/Accept 兼容拦截器。
     *
     * @return Rest5ClientBuilder
     */
    private Rest5Client buildRest5Client(ClientConfiguration clientConfiguration) {
        // --- 1. 基础 Host + 路径前缀 ---
        HttpHost[] httpHosts = resolveHttpHosts(clientConfiguration);
        Rest5ClientBuilder builder = Rest5Client.builder(httpHosts);
        if (clientConfiguration.getPathPrefix() != null) {
            builder.setPathPrefix(clientConfiguration.getPathPrefix());
        }
        // --- 2. 默认请求头 ---
        HttpHeaders defaultHeaders = clientConfiguration.getDefaultHeaders();
        if (!defaultHeaders.isEmpty()) {
            builder.setDefaultHeaders(toHeaderArray(defaultHeaders));
        }
        // --- 3. 用户自定义 Rest5ClientBuilder 回调 ---
        for (ClientConfiguration.ClientConfigurationCallback<?> callback
                : clientConfiguration.getClientConfigurers()) {
            if (callback instanceof Rest5Clients.ElasticsearchRest5ClientConfigurationCallback cfg) {
                builder = cfg.configure(builder);
            }
        }
        // --- 4. HttpAsyncClient 配置（代理、拦截器、用户回调） ---
        builder.setHttpClientConfigCallback(httpAsyncClientBuilder -> {
            // 4-1. 代理
            HttpAsyncClientBuilder finalHttpAsyncClientBuilder = httpAsyncClientBuilder;
            clientConfiguration.getProxy().ifPresent(proxy -> {
                try {
                    finalHttpAsyncClientBuilder.setRoutePlanner(new DefaultProxyRoutePlanner(HttpHost.create(proxy)));
                } catch (URISyntaxException e) {
                    throw new IllegalStateException("Invalid proxy URI: " + proxy, e);
                }
            });
            // 4-2. 动态请求头注入（优先级最高，放 first）
            httpAsyncClientBuilder.addRequestInterceptorFirst((request, entity, context) -> {
                clientConfiguration.getHeadersSupplier().get()
                        .forEach((header, values) -> {
                            if ("Accept".equalsIgnoreCase(header)
                                    || "Content-Type".equalsIgnoreCase(header)) {
                                request.removeHeaders(header);
                            }
                            values.forEach(value -> request.addHeader(header, value));
                        });
            });
            // 4-3. 兼容拦截器：ES 8.x 服务端不认 application/vnd.elasticsearch+json
            //      强制覆写为标准 application/json（放 last，确保最终生效）
            httpAsyncClientBuilder.addRequestInterceptorLast(
                    (request, entityDetails, context) -> {
                        request.setHeader("Content-Type", "application/json");
                        request.setHeader("Accept", "application/json");
                    });
            // 4-4. 用户自定义 HttpClient 回调
            for (ClientConfiguration.ClientConfigurationCallback<?> callback
                    : clientConfiguration.getClientConfigurers()) {
                if (callback instanceof Rest5Clients.ElasticsearchHttpClientConfigurationCallback cfg) {
                    httpAsyncClientBuilder = cfg.configure(httpAsyncClientBuilder);
                }
            }
        });
        // --- 5. 连接参数（connect / socket timeout、用户回调） ---
        builder.setConnectionConfigCallback(connectionConfigBuilder -> {
            long connectMs = clientConfiguration.getConnectTimeout().toMillis();
            long socketMs = clientConfiguration.getSocketTimeout().toMillis();
            if (connectMs >= 0) {
                connectionConfigBuilder.setConnectTimeout(
                        Timeout.of(Math.toIntExact(connectMs), TimeUnit.MILLISECONDS));
            }
            connectionConfigBuilder.setSocketTimeout(
                    socketMs >= 0
                            ? Timeout.of(Math.toIntExact(socketMs), TimeUnit.MILLISECONDS)
                            : Timeout.of(Rest5Clients.DEFAULT_SOCKET_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            for (ClientConfiguration.ClientConfigurationCallback<?> callback
                    : clientConfiguration.getClientConfigurers()) {
                if (callback instanceof Rest5Clients.ElasticsearchConnectionConfigurationCallback cfg) {
                    connectionConfigBuilder = cfg.configure(connectionConfigBuilder);
                }
            }
        });
        // --- 6. 连接池 + TLS ---
        builder.setConnectionManagerCallback(poolingBuilder -> {
            SSLContext sslContext = resolveSslContext(clientConfiguration);
            poolingBuilder.setTlsStrategy(new DefaultClientTlsStrategy(sslContext, NoopHostnameVerifier.INSTANCE));
            poolingBuilder.setMaxConnTotal(props.pool().maxConnTotal());
            poolingBuilder.setMaxConnPerRoute(props.pool().maxConnPerRoute());
            for (ClientConfiguration.ClientConfigurationCallback<?> callback
                    : clientConfiguration.getClientConfigurers()) {
                if (callback instanceof Rest5Clients.ElasticsearchConnectionManagerCallback cfg) {
                    poolingBuilder = cfg.configure(poolingBuilder);
                }
            }
        });
        // --- 7. 请求配置（connectionRequestTimeout、用户回调） ---
        builder.setRequestConfigCallback(requestConfigBuilder -> {
            long socketMs = clientConfiguration.getSocketTimeout().toMillis();
            requestConfigBuilder.setConnectionRequestTimeout(
                    socketMs >= 0
                            ? Timeout.of(Math.toIntExact(socketMs), TimeUnit.MILLISECONDS)
                            : Timeout.of(Rest5Clients.DEFAULT_RESPONSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
            for (ClientConfiguration.ClientConfigurationCallback<?> callback
                    : clientConfiguration.getClientConfigurers()) {
                if (callback instanceof Rest5Clients.ElasticsearchRequestConfigCallback cfg) {
                    requestConfigBuilder = cfg.configure(requestConfigBuilder);
                }
            }
        });
        return builder.build();
    }

    /**
     * 将 properties 中的 URI 列表转换为 "host:port" 字符串数组，
     * 供 ClientConfiguration.builder().connectedTo() 使用。
     */
    private String[] resolveEndpoints() {
        return props.uris().stream()
                .map(URI::create)
                .map(uri -> uri.getHost() + ":" + uri.getPort())
                .toArray(String[]::new);
    }

    /**
     * 从 ClientConfiguration 中提取 HttpHost 数组。
     */
    @NonNull
    private static HttpHost[] resolveHttpHosts(ClientConfiguration clientConfiguration) {
        List<InetSocketAddress> endpoints = clientConfiguration.getEndpoints();
        boolean useSsl = clientConfiguration.useSsl();
        return endpoints.stream()
                .map(addr -> (useSsl ? "https" : "http")
                        + "://" + addr.getHostString() + ":" + addr.getPort())
                .map(URI::create)
                .map(HttpHost::create)
                .toArray(HttpHost[]::new);
    }

    /**
     * 默认使用 TrustAllStrategy.INSTANCE，信任自签名证书，类似于 curl -k
     */
    private static SSLContext resolveSslContext(ClientConfiguration clientConfiguration) {
        try {
            if (clientConfiguration.getCaFingerprint().isPresent()) {
                return TransportUtils.sslContextFromCaFingerprint(
                        clientConfiguration.getCaFingerprint().get());
            }
            if (clientConfiguration.getSslContext().isPresent()) {
                return clientConfiguration.getSslContext().get();
            }
            // 默认兜底：如果没有配置 CA 指纹或 SSLContext，构建一个信任各种证书的 SSLContext
            return new SSLContextBuilder()
                    .loadTrustMaterial(null, TrustAllStrategy.INSTANCE)
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException("Could not create SSL context", e);
        }
    }

    private static Header[] toHeaderArray(HttpHeaders headers) {
        return headers.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream()
                        .map(value -> new BasicHeader(entry.getKey(), value)))
                .toList().toArray(new Header[0]);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}