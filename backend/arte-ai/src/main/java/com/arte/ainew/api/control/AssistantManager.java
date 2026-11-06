package com.arte.ainew.api.control;

import com.arte.ainew.pojo.assistant.Assistants.*;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.reference.DefinitionRef;

/**
 * Manages workspace-owned chat assistants and resolves immutable published configurations.
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 17:35 ✾
 */
public interface AssistantManager {
    CommandReceipt<Receipt> create(TrustedActor actor, Scope scope, Content content, CommandMeta meta);

    CommandReceipt<Receipt> copy(TrustedActor actor, Scope scope, String sourceId, Long sourceVersion,
                                 Long sourceDraftRevision, CommandMeta meta);

    CommandReceipt<Receipt> saveDraft(TrustedActor actor, Scope scope, String id, long expectedRevision,
                                      Content content, CommandMeta meta);

    CommandReceipt<Receipt> publish(TrustedActor actor, Scope scope, String id, long expectedVersion,
                                    long expectedRevision, String trialId, CommandMeta meta);

    CommandReceipt<Receipt> disable(TrustedActor actor, Scope scope, String id, long expectedVersion, CommandMeta meta);

    Detail get(TrustedActor actor, Scope scope, String id);

    Page<Managed> managed(TrustedActor actor, Scope scope, String afterId, String name, int limit);

    Page<Summary> usable(TrustedActor actor, Scope scope, String afterId, String name, int limit);

    Page<Release> versions(TrustedActor actor, Scope scope, String id, long afterVersion, int limit);

    Release resolvePublished(TrustedActor actor, Scope scope, DefinitionRef profile, boolean newConversation);
}
