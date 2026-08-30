package com.arte.ai.mcp.server;

import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * MCP 资源声明
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/8/28 10:53 ✾
 **/
@Slf4j
@Service
public class ArteMcpResources {

    private static final String ARTE_LOGO_URI_STR = "resource://arte-logo-str";
    private static final String ARTE_LOGO_URI_BYTE = "resource://arte-logo-byte";

    private static final String ARTE_LOGO_PATH = "static/img/arte-logo.svg";

    /**
     * 返回文本资源
     *
     * @return svg 字符串
     * @throws IOException 读取文件时出错
     */
    @McpResource(
            uri = ARTE_LOGO_URI_STR,
            name = "ARTE Logo SVG String",
            description = "ARTE project logo as an SVG string",
            mimeType = "image/svg+xml"
    )
    public String getArteLogoStr() throws IOException {
        log.info("返回 ARTE Logo SVG Str");
        return new ClassPathResource(ARTE_LOGO_PATH).getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * 返回字节资源
     *
     * @return svg 字节
     * @throws IOException 读取文件时出错
     */
    @McpResource(
            uri = ARTE_LOGO_URI_BYTE,
            name = "ARTE Logo SVG Bytes",
            description = "ARTE project logo as Base64-encoded SVG bytes",
            mimeType = "image/svg+xml"
    )
    public McpSchema.ReadResourceResult getArteLogoByte() throws IOException {
        log.info("返回 ARTE Logo SVG Byte");
        ClassPathResource resource = new ClassPathResource(ARTE_LOGO_PATH);
        byte[] bytes;
        try (InputStream inputStream = resource.getInputStream()) {
            bytes = inputStream.readAllBytes();
        }
        String base64 = Base64.getEncoder().encodeToString(bytes);
        McpSchema.BlobResourceContents contents = McpSchema.BlobResourceContents.builder(ARTE_LOGO_URI_BYTE, base64)
                .mimeType("image/svg+xml")
                .build();
        return McpSchema.ReadResourceResult.builder(List.of(contents)).build();
    }

}
