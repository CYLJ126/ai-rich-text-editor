package com.nip.app.service.richtext.storage;

import com.nip.app.common.enums.richtext.FileStorageTypeEnum;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Strategy interface for rich text file storage.
 */
public interface RichTextFileStorageProvider {

    FileStorageTypeEnum type();

    String uploadImage(MultipartFile file, String folder) throws IOException;

    String uploadFile(MultipartFile file, String folder) throws IOException;

    String readTextByUrl(String url) throws IOException;

    void deleteByUrl(String url) throws IOException;

    void makeArticleImagesPermanent(String contentJson, String contentMd);

    void makePermanentByUrl(String url);
}
