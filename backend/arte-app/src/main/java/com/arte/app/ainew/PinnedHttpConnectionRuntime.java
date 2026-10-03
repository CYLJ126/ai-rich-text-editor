package com.arte.app.ainew;

import com.arte.ai.model.definition.ConnectionDefinition;
import com.arte.ai.spi.adapter.ConnectionRuntime;
import com.arte.base.execution.ExecutionCheckpoint;
import com.arte.base.model.security.SecretRef;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

/**
 * 单次有界 HTTP/1.1：DNS 检查后连接固定 IP，TLS 验证原主机；无代理、重定向、连接或协议重试。
 */
public final class PinnedHttpConnectionRuntime implements ConnectionRuntime {
    private final Set<String> hosts;
    private final Function<SecretRef, char[]> secrets;
    private final int maxResponseBytes;
    private final boolean localTest;

    public PinnedHttpConnectionRuntime(Set<String> hosts, Function<SecretRef, char[]> secrets, int maxResponseBytes) {
        this(hosts, secrets, maxResponseBytes, false);
    }

    // 仅包内测试允许明文回环，不提供生产配置开关。
    PinnedHttpConnectionRuntime(Set<String> hosts, Function<SecretRef, char[]> secrets, int maxResponseBytes, boolean localTest) {
        this.hosts = Set.copyOf(hosts);
        this.secrets = Objects.requireNonNull(secrets);
        this.maxResponseBytes = maxResponseBytes;
        this.localTest = localTest;
        if (maxResponseBytes <= 0 || maxResponseBytes > 1048576)
            throw new IllegalArgumentException("invalid response capacity");
    }

    @Override
    public byte[] exchange(ConnectionDefinition connection, byte[] body, ExecutionCheckpoint checkpoint) throws Exception {
        var result = new ByteArrayOutputStream();
        exchangeTo(connection, body, checkpoint, (bytes, offset, length) -> result.write(bytes, offset, length), false);
        return result.toByteArray();
    }

    @Override
    public void exchangeStream(ConnectionDefinition connection, byte[] body, ExecutionCheckpoint checkpoint, ChunkConsumer consumer) throws Exception {
        exchangeTo(connection, body, checkpoint, consumer, true);
    }

    private void exchangeTo(ConnectionDefinition connection, byte[] body, ExecutionCheckpoint checkpoint, ChunkConsumer consumer, boolean streaming) throws Exception {
        checkpoint.check();
        URI endpoint = connection.endpoint();
        String host = endpoint.getHost();
        if (!hosts.contains(host) || !localTest && !"https".equalsIgnoreCase(endpoint.getScheme()))
            throw new IOException("model endpoint rejected");
        var addresses = InetAddress.getAllByName(host);
        if (addresses.length == 0) throw new IOException("model endpoint has no addresses");
        for (var address : addresses)
            if (localTest ? !address.isLoopbackAddress() : !publicAddress(address))
                throw new IOException("model address rejected");
        int port = endpoint.getPort() == -1 ? "https".equalsIgnoreCase(endpoint.getScheme()) ? 443 : 80 : endpoint.getPort();
        char[] secret = secrets.apply(connection.secretRef());
        if (secret == null || secret.length == 0) throw new IOException("model credential unavailable");
        try (Socket raw = new Socket(); var stop = checkpoint.onStop(raw)) {
            raw.connect(new InetSocketAddress(addresses[0], port), remaining(checkpoint));
            Socket socket = raw;
            if ("https".equalsIgnoreCase(endpoint.getScheme())) {
                var tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
                var parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(parameters);
                tls.setSoTimeout(remaining(checkpoint));
                tls.startHandshake();
                socket = tls;
            }
            try (Socket connected = socket) {
                checkpoint.check();
                connected.setSoTimeout(remaining(checkpoint));
                for (char c : secret) if (c <= 32 || c >= 127) throw new IOException("invalid model credential");
                String path = endpoint.getRawPath();
                if (path == null || path.isEmpty()) path = "/";
                if (!path.chars().allMatch(c -> c >= 33 && c <= 126)) throw new IOException("invalid endpoint path");
                String authority = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
                var output = connected.getOutputStream();
                String headers = "POST " + path + " HTTP/1.1\r\nHost: " + authority + ":" + port
                        + "\r\nAuthorization: Bearer " + new String(secret) + "\r\nContent-Type: application/json\r\nAccept: " + (streaming ? "text/event-stream" : "application/json") + "\r\nAccept-Encoding: identity\r\nConnection: close\r\nContent-Length: " + body.length + "\r\n\r\n";
                output.write(headers.getBytes(StandardCharsets.ISO_8859_1));
                output.write(body);
                output.flush();
                var input = new BufferedInputStream(connected.getInputStream());
                String status = line(input, checkpoint);
                String[] statusParts = status.split(" ", 3);
                if (statusParts.length < 2 || !statusParts[0].equals("HTTP/1.1") && !statusParts[0].equals("HTTP/1.0"))
                    throw new IOException("invalid model response");
                int code = Integer.parseInt(statusParts[1]);
                var responseHeaders = new HashMap<String, String>();
                int headerBytes = status.length();
                for (String line; !(line = line(input, checkpoint)).isEmpty(); ) {
                    headerBytes += line.length();
                    if (headerBytes > 65536) throw new IOException("response headers too large");
                    int colon = line.indexOf(':');
                    if (colon < 1) throw new IOException("invalid response header");
                    if (responseHeaders.putIfAbsent(line.substring(0, colon).toLowerCase(Locale.ROOT), line.substring(colon + 1).trim()) != null)
                        throw new IOException("duplicate response header");
                }
                if (code != 200) throw new IOException("model HTTP request failed"); // 不保存／回显远端错误正文。
                String encoding = responseHeaders.get("content-encoding");
                if (encoding != null && !encoding.equalsIgnoreCase("identity"))
                    throw new IOException("unsupported response encoding");
                if (streaming && !responseHeaders.getOrDefault("content-type", "").toLowerCase(Locale.ROOT).startsWith("text/event-stream"))
                    throw new IOException("invalid model stream type");
                var result = new ResponseBody(consumer);
                String transfer = responseHeaders.get("transfer-encoding");
                if (transfer != null) {
                    if (!transfer.equalsIgnoreCase("chunked") || responseHeaders.containsKey("content-length"))
                        throw new IOException("invalid transfer framing");
                    while (true) {
                        String chunk = line(input, checkpoint).split(";", 2)[0];
                        long size = Long.parseLong(chunk, 16);
                        if (size < 0 || size > maxResponseBytes - result.size())
                            throw new IOException("model response too large");
                        if (size == 0) {
                            if (!line(input, checkpoint).isEmpty())
                                throw new IOException("response trailers unsupported");
                            break;
                        }
                        copy(input, result, size, connected, checkpoint);
                        if (!line(input, checkpoint).isEmpty()) throw new IOException("invalid chunk framing");
                    }
                } else if (responseHeaders.containsKey("content-length")) {
                    long size = Long.parseLong(responseHeaders.get("content-length"));
                    if (size < 0 || size > maxResponseBytes) throw new IOException("model response too large");
                    copy(input, result, size, connected, checkpoint);
                } else {
                    byte[] buffer = new byte[8192];
                    int count;
                    while (true) {
                        connected.setSoTimeout(remaining(checkpoint));
                        count = input.read(buffer);
                        if (count < 0) break;
                        if (count > maxResponseBytes - result.size()) throw new IOException("model response too large");
                        result.write(buffer, 0, count);
                        checkpoint.check();
                    }
                }
                checkpoint.check();
                return;
            }
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    private void copy(InputStream input, ResponseBody output, long length, Socket socket, ExecutionCheckpoint checkpoint) throws Exception {
        byte[] buffer = new byte[8192];
        while (length > 0) {
            checkpoint.check();
            socket.setSoTimeout(remaining(checkpoint));
            int count = input.read(buffer, 0, (int) Math.min(length, buffer.length));
            if (count < 0) throw new EOFException("incomplete model response");
            output.write(buffer, 0, count);
            length -= count;
        }
    }

    private final class ResponseBody {
        private final ChunkConsumer consumer;
        private int size;

        ResponseBody(ChunkConsumer consumer) {
            this.consumer = consumer;
        }

        int size() {
            return size;
        }

        void write(byte[] bytes, int offset, int length) throws Exception {
            if (length > maxResponseBytes - size) throw new IOException("model response too large");
            size += length;
            consumer.accept(bytes, offset, length);
        }
    }

    private static String line(InputStream input, ExecutionCheckpoint checkpoint) throws IOException {
        var bytes = new ByteArrayOutputStream();
        int c;
        while (true) {
            checkpoint.check();
            c = input.read();
            if (c == -1) break;
            if (bytes.size() >= 8192) throw new IOException("HTTP line too large");
            if (c == '\n') {
                byte[] line = bytes.toByteArray();
                if (line.length == 0 || line[line.length - 1] != '\r') throw new IOException("invalid HTTP line");
                return new String(line, 0, line.length - 1, StandardCharsets.ISO_8859_1);
            }
            bytes.write(c);
        }
        throw new EOFException("incomplete HTTP headers");
    }

    private static int remaining(ExecutionCheckpoint checkpoint) {
        checkpoint.check();
        Instant deadline = checkpoint.context().deadline();
        long millis = deadline == null ? 30000 : Duration.between(Instant.now(), deadline).toMillis();
        return (int) Math.max(1, Math.min(millis, 30000));
    }

    private static boolean publicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress())
            return false;
        byte[] value = address.getAddress();
        int first = value[0] & 255;
        if (value.length == 16) return (first & 254) != 252;
        int second = value[1] & 255;
        return first != 0 && first < 224 && !(first == 100 && second >= 64 && second <= 127) && !(first == 198 && (second == 18 || second == 19));
    }
}
