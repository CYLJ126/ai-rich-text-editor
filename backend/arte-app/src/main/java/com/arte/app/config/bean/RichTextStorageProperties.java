package com.arte.app.config.bean;

import com.arte.app.common.enums.richtext.FileStorageTypeEnum;
import lombok.Data;

/**
 * Storage configuration for rich text media.
 */
@Data
public class RichTextStorageProperties {

    /**
     * Active storage backend.
     */
    private FileStorageTypeEnum type = FileStorageTypeEnum.QINIU;

    /**
     * Qiniu object storage settings.
     */
    private QiniuProperties qiniu = new QiniuProperties();

    /**
     * Local file storage settings.
     */
    private Local local = new Local();

    @Data
    public static class Local {

        /**
         * Directory used to persist uploaded images.
         */
        private String imagePath = "./data/uploads/richtext/images";

        /**
         * Directory used to persist uploaded files.
         */
        private String filePath = "./data/uploads/richtext/files";
    }
}
