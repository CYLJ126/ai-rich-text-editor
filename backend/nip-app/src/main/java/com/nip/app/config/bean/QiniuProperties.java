package com.nip.app.config.bean;

import lombok.Data;

/**
 * 七牛云对象存储配置属性
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/5/10
 */
@Data
public class QiniuProperties {

    /**
     * 七牛云 Access Key
     */
    private String accessKey;

    /**
     * 七牛云 Secret Key
     */
    private String secretKey;

    /**
     * 存储空间名称
     */
    private String bucket;

    /**
     * 存储空间绑定的域名（用于拼接文件访问链接）
     */
    private String domain;

    /**
     * 图片默认过期天数（上传后未关联文章时，自动删除）
     */
    private int imageExpireDays = 7;
}
