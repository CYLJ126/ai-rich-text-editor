package com.arte.ainew.pojo.context;

import com.arte.ainew.common.validation.ContractChecks;
import com.arte.core.pojo.reference.DefinitionRef;

import java.time.Instant;

/**
 * Server-owned provenance. Exactly one of a released chat profile and a private trial is present.
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/7 00:45 ✾
 */
public record AssistantSource(String assistantId, DefinitionRef profile, String trialId, long revision,
                              String contentDigest, String displayName, Instant trialDeadline) {

    public AssistantSource {
        ContractChecks.id(assistantId, "assistantId");
        ContractChecks.require((profile == null) != (trialId == null) && revision > 0, "Invalid assistant source");
        ContractChecks.require((trialDeadline == null) == (trialId == null), "Invalid trial deadline");
        if (profile != null) profile.requireType("chat-profile");
        if (trialId != null) ContractChecks.id(trialId, "trialId");
        ContractChecks.digest(contentDigest, "contentDigest");
        ContractChecks.text(displayName, "displayName", 160);
    }
}
