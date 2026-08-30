package com.arte.app.service.richtext.storage;

import cn.hutool.core.util.StrUtil;
import com.arte.app.common.enums.richtext.FileStorageTypeEnum;
import com.arte.app.config.bean.RichTextStorageProperties;
import com.arte.core.i18n.MessageUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

/**
 * Local disk implementation for rich text file storage.
 */
@Slf4j
@Component
public class LocalRichTextFileStorageProvider implements RichTextFileStorageProvider {

    private static final DateTimeFormatter MONTH_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM");
    private static final String IMAGE_ACCESS_PATH = "/richText/file/local/image";
    private static final String FILE_ACCESS_PATH = "/richText/file/local/file";

    @Resource
    private RichTextStorageProperties richTextStorageProperties;

    @Value("${server.servlet.context-path:}")
    private String contextPath;

    @Override
    public FileStorageTypeEnum type() {
        return FileStorageTypeEnum.LOCAL;
    }

    @Override
    public String uploadImage(MultipartFile file, String folder) throws IOException {
        return save(file, richTextStorageProperties.getLocal().getImagePath(), IMAGE_ACCESS_PATH);
    }

    @Override
    public String uploadFile(MultipartFile file, String folder) throws IOException {
        return save(file, richTextStorageProperties.getLocal().getFilePath(), FILE_ACCESS_PATH);
    }

    @Override
    public String readTextByUrl(String url) throws IOException {
        return Files.readString(resolveStoredFile(url), StandardCharsets.UTF_8);
    }

    @Override
    public void deleteByUrl(String url) throws IOException {
        Files.deleteIfExists(resolveStoredFile(url));
    }

    @Override
    public void makeArticleImagesPermanent(String contentJson, String contentMd) {
        // Local storage keeps files until an explicit cleanup policy is added.
    }

    @Override
    public void makePermanentByUrl(String url) {
        // Local storage keeps files until an explicit cleanup policy is added.
    }

    private String save(MultipartFile file, String storagePath, String accessPath) throws IOException {
        String monthPath = MONTH_FORMATTER.format(LocalDate.now());
        String suffix = resolveSuffix(file);
        String filename = UUID.randomUUID().toString().replace("-", "") + suffix;
        Path rootPath = Paths.get(storagePath).toAbsolutePath().normalize();
        Path targetDir = rootPath.resolve(monthPath).normalize();
        if (!targetDir.startsWith(rootPath)) {
            throw new IOException("Invalid upload path: " + storagePath);
        }
        Files.createDirectories(targetDir);
        Path targetFile = targetDir.resolve(filename).normalize();
        file.transferTo(targetFile);
        String relativePath = monthPath + "/" + filename;
        String url = joinUrl(normalizeUrlPath(contextPath) + accessPath, relativePath);
        log.info("Local rich text file uploaded, path: {}, url: {}", targetFile, url);
        return url;
    }

    private String resolveSuffix(MultipartFile file) {
        String originalFilename = file.getOriginalFilename();
        if (StrUtil.isNotBlank(originalFilename) && originalFilename.contains(".")) {
            String suffix = originalFilename.substring(originalFilename.lastIndexOf("."));
            return suffix.toLowerCase(Locale.ROOT);
        }
        String contentType = file.getContentType();
        if ("image/png".equals(contentType)) {
            return ".png";
        }
        if ("image/gif".equals(contentType)) {
            return ".gif";
        }
        if ("image/webp".equals(contentType)) {
            return ".webp";
        }
        if ("image/jpeg".equals(contentType)) {
            return ".jpg";
        }
        return "";
    }

    private String joinUrl(String prefix, String relativePath) {
        String normalizedPrefix = StrUtil.blankToDefault(prefix, "");
        while (normalizedPrefix.endsWith("/")) {
            normalizedPrefix = normalizedPrefix.substring(0, normalizedPrefix.length() - 1);
        }
        return normalizedPrefix + "/" + relativePath;
    }

    private String normalizeUrlPath(String path) {
        String normalized = StrUtil.blankToDefault(path, "");
        if (StrUtil.isBlank(normalized)) {
            return "";
        }
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private Path resolveStoredFile(String url) throws IOException {
        try {
            URI uri = URI.create(url);
            String urlPath = URLDecoder.decode(uri.getPath(), StandardCharsets.UTF_8);
            String basePath = normalizeUrlPath(contextPath);
            String imagePrefix = basePath + IMAGE_ACCESS_PATH + "/";
            String filePrefix = basePath + FILE_ACCESS_PATH + "/";
            String storagePath;
            String relativePath;

            if (urlPath.startsWith(imagePrefix)) {
                storagePath = richTextStorageProperties.getLocal().getImagePath();
                relativePath = urlPath.substring(imagePrefix.length());
            } else if (urlPath.startsWith(filePrefix)) {
                storagePath = richTextStorageProperties.getLocal().getFilePath();
                relativePath = urlPath.substring(filePrefix.length());
            } else {
                throw new IOException(MessageUtils.get("error.file.urlNotCurrentStorage"));
            }

            Path rootPath = Paths.get(storagePath).toAbsolutePath().normalize();
            Path targetFile = rootPath.resolve(relativePath).normalize();
            if (!targetFile.startsWith(rootPath)) {
                throw new IOException(MessageUtils.get("error.file.illegalPath"));
            }
            return targetFile;
        } catch (IllegalArgumentException e) {
            throw new IOException(MessageUtils.get("error.file.illegalUrl"), e);
        }
    }
}
