package com.arte.app.service.richtext.storage;

import com.arte.app.common.enums.richtext.FileStorageTypeEnum;
import com.arte.app.service.richtext.QiniuUploadService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Qiniu implementation for rich text file storage.
 *
 * <p>Images uploaded through this provider are first written to Qiniu Kodo and
 * returned as public object URLs. Referenced article images are later marked as
 * permanent by {@link QiniuUploadService}.</p>
 */
@Component
public class QiniuRichTextFileStorageProvider implements RichTextFileStorageProvider {

    @Resource
    private QiniuUploadService qiniuUploadService;

    @Override
    public FileStorageTypeEnum type() {
        return FileStorageTypeEnum.QINIU;
    }

    @Override
    public String uploadImage(MultipartFile file, String folder) throws IOException {
        return qiniuUploadService.upload(file, folder);
    }

    @Override
    public String uploadFile(MultipartFile file, String folder) throws IOException {
        return qiniuUploadService.uploadFile(file, folder);
    }

    @Override
    public String readTextByUrl(String url) throws IOException {
        return qiniuUploadService.readTextByUrl(url);
    }

    @Override
    public void deleteByUrl(String url) throws IOException {
        qiniuUploadService.deleteByUrl(url);
    }

    @Override
    public void makeArticleImagesPermanent(String contentJson, String contentMd) {
        qiniuUploadService.makeArticleImagesPermanent(contentJson, contentMd);
    }

    @Override
    public void makePermanentByUrl(String url) {
        String key = qiniuUploadService.extractKeyFromUrl(url);
        if (key != null) {
            qiniuUploadService.makePermanentSilently(key);
        }
    }
}
