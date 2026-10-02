package com.arte.base.model.security;

/**
 * 公共资源动作，各动作独立授权，不表达角色继承关系。
 * 资源类型和操作是否适用由对应领域授权提供者判断。
 */
public enum CommonResourceAction implements ResourceAction {
    READ("resource.read"),
    ANNOTATE("resource.annotate"),
    COMMENT("resource.comment"),
    EDIT("resource.edit"),
    MANAGE_SHARING("resource.manage_sharing"),
    EXPORT("resource.export"),
    COPY("resource.copy"),
    AI_PROCESS("resource.ai_process"),
    EGRESS("resource.egress"),
    SUBSCRIBE_EVENTS("resource.subscribe_events"),
    DOWNLOAD("resource.download");

    private final String code;

    CommonResourceAction(String code) {
        this.code = code;
    }

    @Override
    public String code() {
        return code;
    }
}
