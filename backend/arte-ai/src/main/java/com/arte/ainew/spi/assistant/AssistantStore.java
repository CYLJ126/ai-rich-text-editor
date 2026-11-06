package com.arte.ainew.spi.assistant;

import com.arte.ainew.pojo.assistant.Assistants.Draft;
import com.arte.ainew.pojo.assistant.Assistants.Release;
import com.arte.ainew.pojo.assistant.Assistants.Resource;
import com.arte.ainew.pojo.assistant.Assistants.Trial;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.Scope;

import java.util.List;
import java.util.Optional;

/**
 * Raw assistant facts. Current reads and mutations participate in the platform transaction.
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/7 00:09 ✾
 */
public interface AssistantStore {

    Optional<Resource> find(ResourceRef ref, boolean current);

    Optional<Resource> findByProfile(Scope scope, String profileId, boolean current);

    Optional<Draft> draft(String assistantId, boolean current);

    Optional<Release> release(String assistantId, long version);

    Optional<Trial> trial(String assistantId, String trialId);

    List<Resource> scan(Scope scope, String afterId, boolean activeOnly, int limit);

    List<Release> versions(String assistantId, long afterVersion, int limit);

    void insert(Resource resource, Draft draft);

    boolean replaceDraft(Draft draft, long expectedRevision);

    boolean replaceResource(Resource resource, long expectedVersion);

    void insertRelease(Release release);
}
