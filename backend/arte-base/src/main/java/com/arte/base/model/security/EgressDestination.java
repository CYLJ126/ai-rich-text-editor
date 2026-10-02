package com.arte.base.model.security;

import com.arte.base.model.resource.ResourceRef;
import com.arte.base.validation.ContractChecks;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * 服务端从已发布连接解析出的目的地；连接必须固定到正式版本，不接受草稿或范围引用。
 * origin 仅为 HTTP(S) 源（协议、主机、端口），不携带路径、查询参数、片段或凭据。
 * 主机、协议及默认端口规范化用于判定匹配；DNS、私网地址、重定向及实际出口仍由连接运行时验证。
 */
public record EgressDestination(ResourceRef connectionRef, URI origin) {

    public EgressDestination {
        connectionRef = ContractChecks.required(connectionRef, "connectionRef");
        if (connectionRef.version() == null || connectionRef.isDraft() || connectionRef.rangeRef() != null) {
            throw new IllegalArgumentException("connectionRef requires a saved version without draft or range");
        }
        origin = normalizeOrigin(origin);
    }

    private static URI normalizeOrigin(URI origin) {
        ContractChecks.required(origin, "origin");
        String scheme = origin.getScheme();
        String path = origin.getRawPath();
        if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                || origin.getHost() == null || origin.getRawUserInfo() != null
                || origin.getRawQuery() != null || origin.getRawFragment() != null
                || (path != null && !path.isEmpty() && !"/".equals(path))
                || origin.getPort() == 0 || origin.getPort() > 65535) {
            throw new IllegalArgumentException("origin must be an HTTP(S) origin without path, credentials, query or fragment");
        }
        scheme = scheme.toLowerCase(Locale.ROOT);
        String host = origin.getHost().toLowerCase(Locale.ROOT);
        int port = origin.getPort();
        if (("https".equals(scheme) && port == 443) || ("http".equals(scheme) && port == 80)) {
            port = -1;
        }
        try {
            return new URI(scheme, null, host, port, null, null, null);
        } catch (URISyntaxException invalidOrigin) {
            throw new IllegalArgumentException("origin is invalid");
        }
    }
}
