package com.arte.core.constant.platform;

import com.arte.core.pojo.access.ResourceType;

/**
 * 首版公共资源类型名；实际领域事实由对应 ResourceDescriptorProvider 提供。
 */
public final class PlatformResourceTypes {
    public static final ResourceType TENANT = new ResourceType("tenant");
    public static final ResourceType WORKSPACE = new ResourceType("workspace");
    public static final ResourceType ARTICLE = new ResourceType("article");
    public static final ResourceType COLLECTION = new ResourceType("collection");
    public static final ResourceType MODEL = new ResourceType("model");
    public static final ResourceType ASSISTANT = new ResourceType("assistant");

    private PlatformResourceTypes() {
    }
}
