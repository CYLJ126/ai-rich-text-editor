package com.arte.app.service.richtext;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.arte.app.common.enums.richtext.FileStorageTypeEnum;
import com.arte.app.config.bean.QiniuProperties;
import com.arte.app.config.bean.RichTextStorageProperties;
import com.arte.core.i18n.MessageUtils;
import com.qiniu.common.QiniuException;
import com.qiniu.http.Response;
import com.qiniu.storage.BucketManager;
import com.qiniu.storage.Configuration;
import com.qiniu.storage.Region;
import com.qiniu.storage.UploadManager;
import com.qiniu.storage.model.DefaultPutRet;
import com.qiniu.storage.model.StorageType;
import com.qiniu.util.Auth;
import com.qiniu.util.Json;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 七牛云上传服务
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/5/10
 */
@Slf4j
@Service
public class QiniuUploadService {

    @Resource
    private QiniuProperties qiniuProperties;

    private UploadManager uploadManager;
    private BucketManager bucketManager;
    private Auth auth;
    @Autowired
    private RichTextStorageProperties richTextStorageProperties;

    @PostConstruct
    public void init() {
        if (FileStorageTypeEnum.QINIU == richTextStorageProperties.getType()) {
            // 自动识别上传区域
            Configuration cfg = new Configuration(Region.autoRegion());
            cfg.resumableUploadAPIVersion = Configuration.ResumableUploadAPIVersion.V2;
            this.uploadManager = new UploadManager(cfg);
            this.auth = Auth.create(qiniuProperties.getAccessKey(), qiniuProperties.getSecretKey());
            this.bucketManager = new BucketManager(auth, cfg);
            log.info("七牛云上传服务初始化完成，bucket: {}", qiniuProperties.getBucket());
        }
    }

    /**
     * 上传文件到七牛云
     *
     * @param file       上传的文件
     * @param folder     存储目录（可选，如 "images/"）
     * @return 文件访问链接
     */
    public String upload(MultipartFile file, String folder) throws IOException {
        // 生成唯一文件名
        String originalFilename = file.getOriginalFilename();
        String suffix = "";
        if (StrUtil.isNotBlank(originalFilename) && originalFilename.contains(".")) {
            suffix = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String key = generateKey(folder, suffix);

        // 生成上传凭证
        String upToken = auth.uploadToken(qiniuProperties.getBucket(), key);

        try (InputStream inputStream = file.getInputStream()) {
            byte[] data = IoUtil.readBytes(inputStream);
            Response response = uploadManager.put(data, key, upToken);
            DefaultPutRet putRet = Json.decode(response.bodyString(), DefaultPutRet.class);
            String url = buildUrl(putRet.key);
            log.info("文件上传成功，key: {}, url: {}", putRet.key, url);
            // 上传后设置过期时间，未被文章引用的图片会在过期后自动删除
            setDeleteAfterDaysSilently(putRet.key, qiniuProperties.getImageExpireDays());
            return url;
        } catch (QiniuException e) {
            log.error("七牛云上传失败", e);
            throw new IOException(e.response.bodyString(), e);
        }
    }

    /**
     * 上传任意文件到七牛云，并转为低频存储
     * 适用于文档、压缩包等非图片文件
     *
     * @param file   上传的文件
     * @param folder 存储目录（可选，如 "files/"）
     * @return 文件访问链接
     */
    public String uploadFile(MultipartFile file, String folder) throws IOException {
        String originalFilename = file.getOriginalFilename();
        String suffix = "";
        if (StrUtil.isNotBlank(originalFilename) && originalFilename.contains(".")) {
            suffix = originalFilename.substring(originalFilename.lastIndexOf("."));
        }
        String key = generateKey(folder, suffix);

        String upToken = auth.uploadToken(qiniuProperties.getBucket(), key);

        try (InputStream inputStream = file.getInputStream()) {
            byte[] data = IoUtil.readBytes(inputStream);
            Response response = uploadManager.put(data, key, upToken);
            DefaultPutRet putRet = Json.decode(response.bodyString(), DefaultPutRet.class);
            String url = buildUrl(putRet.key);
            log.info("文件上传成功，key: {}, url: {}", putRet.key, url);
            // 上传后转为低频存储，节省成本
            changeToInfrequentAccessSilently(putRet.key);
            return url;
        } catch (QiniuException e) {
            log.error("七牛云上传失败", e);
            throw new IOException(e.response.bodyString(), e);
        }
    }

    /**
     * 将文件转为低频存储（type=1）
     *
     * @param key 文件 key
     */
    public void changeToInfrequentAccess(String key) throws QiniuException {
        // 0=标准存储, 1=低频存储, 2=归档存储
        Response response = bucketManager.changeType(
                qiniuProperties.getBucket(), key, StorageType.INFREQUENCY);
        log.info("文件转为低频存储，key: {}, response: {}", key, response.statusCode);
    }

    /**
     * 将文件转为低频存储（静默处理异常）
     */
    private void changeToInfrequentAccessSilently(String key) {
        try {
            changeToInfrequentAccess(key);
        } catch (QiniuException e) {
            log.error("文件转低频存储失败，key: {}", key, e);
        }
    }

    /**
     * 设置文件生命周期（过期后自动删除）
     *
     * @param key             文件 key
     * @param deleteAfterDays 过期天数，0 表示永不过期
     */
    public void setDeleteAfterDays(String key, int deleteAfterDays) throws QiniuException {
        Response response = bucketManager.deleteAfterDays(
                qiniuProperties.getBucket(), key, deleteAfterDays);
        log.info("设置文件生命周期，key: {}, deleteAfterDays: {}, response: {}",
                key, deleteAfterDays, response.statusCode);
    }

    /**
     * 设置文件生命周期（静默处理异常，失败时仅日志记录）
     */
    private void setDeleteAfterDaysSilently(String key, int deleteAfterDays) {
        try {
            setDeleteAfterDays(key, deleteAfterDays);
        } catch (QiniuException e) {
            log.error("设置文件生命周期失败，key: {}, deleteAfterDays: {}", key, deleteAfterDays, e);
        }
    }

    /**
     * 将文件设为永久保留（设置 deleteAfterDays=0）
     *
     * @param key 文件 key
     */
    public void makePermanent(String key) throws QiniuException {
        setDeleteAfterDays(key, 0);
    }

    /**
     * 将文件设为永久保留（静默处理异常）
     *
     * @param key 文件 key
     */
    public void makePermanentSilently(String key) {
        try {
            makePermanent(key);
        } catch (QiniuException e) {
            log.error("设置文件永久保留失败，key: {}", key, e);
        }
    }

    /**
     * 从文件访问 URL 中提取 key
     * 例如 https://image.haiqingd.top/images/article/abc.jpg → images/article/abc.jpg
     *
     * @param url 文件访问 URL
     * @return 提取到的 key，失败返回 null
     */
    public String extractKeyFromUrl(String url) {
        if (StrUtil.isBlank(url)) {
            return null;
        }
        String domain = qiniuProperties.getDomain();
        String prefix;
        if (domain.startsWith("http://")) {
            prefix = domain + "/";
        } else if (domain.startsWith("https://")) {
            prefix = domain + "/";
        } else {
            prefix = "https://" + domain + "/";
        }
        // 也兼容 http 写法
        String httpPrefix = prefix.replace("https://", "http://");

        if (url.startsWith(prefix)) {
            return url.substring(prefix.length());
        }
        if (url.startsWith(httpPrefix)) {
            return url.substring(httpPrefix.length());
        }
        return null;
    }

    public String readTextByUrl(String url) throws IOException {
        requireStoredKey(url);
        URLConnection connection = URI.create(url).toURL().openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        try (InputStream inputStream = connection.getInputStream()) {
            return new String(IoUtil.readBytes(inputStream), StandardCharsets.UTF_8);
        }
    }

    public void deleteByUrl(String url) throws IOException {
        String key = requireStoredKey(url);
        try {
            bucketManager.delete(qiniuProperties.getBucket(), key);
        } catch (QiniuException e) {
            throw new IOException(MessageUtils.get("error.file.qiniuDeleteFailed"), e);
        }
    }

    private String requireStoredKey(String url) throws IOException {
        String key = extractKeyFromUrl(url);
        if (StrUtil.isBlank(key)) {
            throw new IOException(MessageUtils.get("error.file.urlNotQiniu"));
        }
        return key;
    }

    /**
     * 构建文件访问链接
     */
    private String buildUrl(String key) {
        String domain = qiniuProperties.getDomain();
        if (!domain.startsWith("http")) {
            domain = "https://" + domain;
        }
        if (domain.endsWith("/")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        return domain + "/" + key;
    }

    /**
     * 从文章内容中提取所有七牛云图片 key，并设为永久保留
     *
     * @param contentJson Tiptap JSON 内容
     * @param contentMd   Markdown 内容
     */
    public void makeArticleImagesPermanent(String contentJson, String contentMd) {
        Set<String> keys = extractKeysFromContent(contentJson, contentMd);
        if (keys.isEmpty()) {
            return;
        }
        log.info("将文章中的 {} 张图片设为永久保留", keys.size());
        keys.forEach(this::makePermanentSilently);
    }

    /**
     * 从文章内容中提取所有七牛云图片的 key
     */
    private Set<String> extractKeysFromContent(String contentJson, String contentMd) {
        Set<String> keys = new LinkedHashSet<>();
        if (StrUtil.isNotBlank(contentJson)) {
            keys.addAll(extractKeysFromTiptapJson(contentJson));
        }
        if (StrUtil.isNotBlank(contentMd)) {
            keys.addAll(extractKeysFromMarkdown(contentMd));
        }
        return keys;
    }

    /**
     * 从 Tiptap JSON 中提取图片 URL 对应的七牛云 key
     * 图片节点格式：{"type":"image","attrs":{"src":"https://..."}}
     */
    private Set<String> extractKeysFromTiptapJson(String json) {
        Set<String> keys = new LinkedHashSet<>();
        try {
            JsonElement root = JsonParser.parseString(json);
            collectImageSrcs(root, keys);
        } catch (Exception e) {
            log.error("解析 Tiptap JSON 失败", e);
        }
        return keys;
    }

    /**
     * 从 Markdown 文本中提取图片 URL 对应的七牛云 key
     * 格式：![alt](url)
     */
    private Set<String> extractKeysFromMarkdown(String markdown) {
        Set<String> keys = new LinkedHashSet<>();
        if (StrUtil.isBlank(markdown)) {
            return keys;
        }
        Pattern pattern = Pattern.compile("!\\[.*?]\\((https?://[^\\s)]+)\\)");
        Matcher matcher = pattern.matcher(markdown);
        while (matcher.find()) {
            String url = matcher.group(1);
            String key = extractKeyFromUrl(url);
            if (key != null) {
                keys.add(key);
            }
        }
        Pattern canvasPreviewPattern = Pattern.compile("\\bpreview=\"(https?://[^\"]+)\"");
        Matcher canvasPreviewMatcher = canvasPreviewPattern.matcher(markdown);
        while (canvasPreviewMatcher.find()) {
            String key = extractKeyFromUrl(canvasPreviewMatcher.group(1));
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }

    /**
     * 递归遍历 Tiptap JSON 树，收集所有图片 src 对应的七牛云 key
     */
    private void collectImageSrcs(JsonElement element, Set<String> keys) {
        if (element.isJsonObject()) {
            JsonObject obj = element.getAsJsonObject();
            String type = obj.has("type") ? obj.get("type").getAsString() : null;
            if (("image".equals(type) || "canvasBlock".equals(type)) && obj.has("attrs")) {
                JsonObject attrs = obj.getAsJsonObject("attrs");
                String urlAttribute = "canvasBlock".equals(type) ? "preview" : "src";
                if (attrs.has(urlAttribute)) {
                    String key = extractKeyFromUrl(attrs.get(urlAttribute).getAsString());
                    if (key != null) {
                        keys.add(key);
                    }
                }
            }
            // 递归遍历子节点
            if (obj.has("content")) {
                collectImageSrcs(obj.get("content"), keys);
            }
        } else if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            for (JsonElement child : array) {
                collectImageSrcs(child, keys);
            }
        }
    }

    /**
     * 生成唯一文件名
     */
    private String generateKey(String folder, String suffix) {
        String uuid = java.util.UUID.randomUUID().toString().replace("-", "");
        StringBuilder sb = new StringBuilder();
        if (StrUtil.isNotBlank(folder)) {
            sb.append(folder);
            if (!folder.endsWith("/")) {
                sb.append("/");
            }
        }
        sb.append(uuid);
        if (StrUtil.isNotBlank(suffix)) {
            sb.append(suffix);
        }
        return sb.toString();
    }
}
