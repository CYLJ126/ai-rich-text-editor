package com.arte.core.constant.platform;

import com.arte.core.pojo.access.Action;

/**
 * 详细设计首版动作名。常量不是允许记录或权限判定，后续规则实现必须通过受信 ActionRegistry 校验。
 * AI 专有执行、凭据和预算动作继续由 AI 定义，本包不复制其执行授权类型。
 */
public final class PlatformActions {
    public static final Action TENANT_READ = new Action("tenant:read");
    public static final Action TENANT_WORKSPACE_CREATE = new Action("tenant:workspace:create");
    public static final Action TENANT_POLICY_WRITE = new Action("tenant:policy:write");
    public static final Action TENANT_MEMBER_MANAGE = new Action("tenant:member:manage");
    public static final Action TENANT_OWNER_TRANSFER = new Action("tenant:owner:transfer");
    public static final Action TENANT_MODEL_CREATE = new Action("tenant:model:create");
    public static final Action TENANT_MODEL_MANAGE = new Action("tenant:model:manage");
    public static final Action WORKSPACE_READ = new Action("workspace:read");
    public static final Action WORKSPACE_UPDATE = new Action("workspace:update");
    public static final Action WORKSPACE_INVITE = new Action("workspace:invite");
    public static final Action WORKSPACE_MEMBER_MANAGE = new Action("workspace:member:manage");
    public static final Action WORKSPACE_ADMIN_ASSIGN = new Action("workspace:admin:assign");
    public static final Action WORKSPACE_OWNER_TRANSFER = new Action("workspace:owner:transfer");
    public static final Action WORKSPACE_POLICY_WRITE = new Action("workspace:policy:write");
    public static final Action WORKSPACE_ARCHIVE = new Action("workspace:archive");
    public static final Action WORKSPACE_CLOSE = new Action("workspace:close");
    public static final Action WORKSPACE_RESTORE = new Action("workspace:restore");
    public static final Action WORKSPACE_RESOURCE_GOVERN = new Action("workspace:resource:govern");
    public static final Action WORKSPACE_AI_USE = new Action("workspace:ai:use");
    public static final Action RESOURCE_DISCOVER = new Action("resource:discover");
    public static final Action RESOURCE_ACCESS_MANAGE = new Action("resource:access:manage");
    public static final Action ARTICLE_CREATE = new Action("article:create");
    public static final Action ARTICLE_READ = new Action("article:read");
    public static final Action ARTICLE_EDIT = new Action("article:edit");
    public static final Action ARTICLE_COMMENT = new Action("article:comment");
    public static final Action ARTICLE_SHARE = new Action("article:share");
    public static final Action ARTICLE_DELETE = new Action("article:delete");
    public static final Action ARTICLE_EXPORT = new Action("article:export");
    public static final Action ARTICLE_EGRESS = new Action("article:egress");
    public static final Action COLLECTION_CREATE = new Action("collection:create");
    public static final Action COLLECTION_READ = new Action("collection:read");
    public static final Action COLLECTION_EDIT = new Action("collection:edit");
    public static final Action COLLECTION_SHARE = new Action("collection:share");
    public static final Action COLLECTION_DELETE = new Action("collection:delete");
    public static final Action COLLECTION_AI_USE = new Action("collection:ai:use");
    public static final Action MODEL_CREATE = new Action("model:create");
    public static final Action MODEL_READ = new Action("model:read");
    public static final Action MODEL_EDIT = new Action("model:edit");
    public static final Action MODEL_VALIDATE = new Action("model:validate");
    public static final Action MODEL_PUBLISH = new Action("model:publish");
    public static final Action MODEL_DISABLE = new Action("model:disable");
    public static final Action MODEL_SHARE = new Action("model:share");
    public static final Action MODEL_USE = new Action("model:use");
    public static final Action ASSISTANT_CREATE = new Action("assistant:create");
    public static final Action ASSISTANT_READ = new Action("assistant:read");
    public static final Action ASSISTANT_EDIT = new Action("assistant:edit");
    public static final Action ASSISTANT_PUBLISH = new Action("assistant:publish");
    public static final Action ASSISTANT_DISABLE = new Action("assistant:disable");
    public static final Action ASSISTANT_SHARE = new Action("assistant:share");
    public static final Action ASSISTANT_USE = new Action("assistant:use");

    private PlatformActions() {
    }
}
