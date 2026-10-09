package com.arte.core.application.access;

import com.arte.core.pojo.access.Action;
import com.arte.core.pojo.access.ActionDefinition;
import com.arte.core.pojo.access.ResourceType;
import com.arte.core.spi.access.ActionRegistry;

import java.util.*;

import static com.arte.core.constant.platform.PlatformActions.*;
import static com.arte.core.constant.platform.PlatformResourceTypes.*;

/**
 * 固定首版动作登记，登记本身不授予动作。没有通配符或来自客户端的动态注册。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 22:35 ✾
 */
public final class DefaultActionRegistry implements ActionRegistry {

    private final Map<Action, ActionDefinition> definitions;

    public DefaultActionRegistry(Collection<ActionDefinition> definitions) {
        Map<Action, ActionDefinition> index = new HashMap<>();
        for (ActionDefinition definition : definitions) {
            if (index.putIfAbsent(definition.action(), definition) != null) {
                throw new IllegalArgumentException("Duplicate action definition: " + definition.action().value());
            }
        }
        this.definitions = Map.copyOf(index);
    }

    public static DefaultActionRegistry standard() {
        List<ActionDefinition> definitions = new ArrayList<>();
        add(definitions, TENANT, false, TENANT_READ, TENANT_WORKSPACE_CREATE, TENANT_POLICY_WRITE,
                TENANT_MEMBER_MANAGE, TENANT_OWNER_TRANSFER, TENANT_MODEL_CREATE, TENANT_MODEL_MANAGE);
        add(definitions, WORKSPACE, false, WORKSPACE_READ, WORKSPACE_UPDATE, WORKSPACE_INVITE,
                WORKSPACE_MEMBER_MANAGE, WORKSPACE_ADMIN_ASSIGN, WORKSPACE_OWNER_TRANSFER, WORKSPACE_POLICY_WRITE,
                WORKSPACE_ARCHIVE, WORKSPACE_CLOSE, WORKSPACE_RESTORE, WORKSPACE_RESOURCE_GOVERN,
                WORKSPACE_AI_USE, RESOURCE_DISCOVER, ARTICLE_CREATE, COLLECTION_CREATE, MODEL_CREATE, ASSISTANT_CREATE);
        add(definitions, ARTICLE, true, ARTICLE_READ, ARTICLE_EDIT, ARTICLE_COMMENT);
        add(definitions, ARTICLE, false, ARTICLE_SHARE, ARTICLE_DELETE, ARTICLE_EXPORT, ARTICLE_EGRESS);
        add(definitions, COLLECTION, true, COLLECTION_READ, COLLECTION_EDIT, COLLECTION_AI_USE);
        add(definitions, COLLECTION, false, COLLECTION_SHARE, COLLECTION_DELETE);
        add(definitions, MODEL, true, MODEL_READ, MODEL_EDIT, MODEL_USE);
        add(definitions, MODEL, false, MODEL_VALIDATE, MODEL_PUBLISH, MODEL_DISABLE, MODEL_SHARE);
        add(definitions, ASSISTANT, true, ASSISTANT_READ, ASSISTANT_EDIT, ASSISTANT_USE);
        add(definitions, ASSISTANT, false, ASSISTANT_PUBLISH, ASSISTANT_DISABLE, ASSISTANT_SHARE);
        definitions.add(new ActionDefinition(RESOURCE_ACCESS_MANAGE,
                Set.of(TENANT, WORKSPACE, ARTICLE, COLLECTION, MODEL, ASSISTANT), false));
        return new DefaultActionRegistry(definitions);
    }

    private static void add(List<ActionDefinition> definitions, ResourceType type, boolean delegable, Action... actions) {
        for (Action action : actions) definitions.add(new ActionDefinition(action, Set.of(type), delegable));
    }

    @Override
    public Optional<ActionDefinition> find(Action action) {
        return Optional.ofNullable(definitions.get(Objects.requireNonNull(action)));
    }
}
