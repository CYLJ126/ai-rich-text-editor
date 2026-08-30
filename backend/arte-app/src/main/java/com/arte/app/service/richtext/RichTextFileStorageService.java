package com.arte.app.service.richtext;

import com.arte.app.common.enums.richtext.FileStorageTypeEnum;
import com.arte.app.config.bean.RichTextStorageProperties;
import com.arte.app.service.richtext.storage.RichTextFileStorageProvider;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Facade that routes rich text file operations to the configured storage backend.
 */
@Service
public class RichTextFileStorageService {

    @Resource
    private RichTextStorageProperties richTextStorageProperties;

    @Resource
    private List<RichTextFileStorageProvider> providers;

    private final Map<FileStorageTypeEnum, RichTextFileStorageProvider> providerMap =
            new EnumMap<>(FileStorageTypeEnum.class);

    @PostConstruct
    public void init() {
        providers.forEach(provider -> providerMap.put(provider.type(), provider));
    }

    public String uploadImage(MultipartFile file, String folder) throws IOException {
        return currentProvider().uploadImage(file, folder);
    }

    public String uploadFile(MultipartFile file, String folder) throws IOException {
        return currentProvider().uploadFile(file, folder);
    }

    public String readTextByUrl(String url) throws IOException {
        return currentProvider().readTextByUrl(url);
    }

    public void deleteByUrl(String url) throws IOException {
        currentProvider().deleteByUrl(url);
    }

    public void makeArticleImagesPermanent(String contentJson, String contentMd) {
        currentProvider().makeArticleImagesPermanent(contentJson, contentMd);
    }

    public void makePermanentByUrl(String url) {
        currentProvider().makePermanentByUrl(url);
    }

    private RichTextFileStorageProvider currentProvider() {
        RichTextFileStorageProvider provider = providerMap.get(richTextStorageProperties.getType());
        if (provider == null) {
            throw new IllegalStateException("No rich text storage provider for type: "
                    + richTextStorageProperties.getType());
        }
        return provider;
    }
}
