package com.arte.core.pojo.platform;

import com.arte.core.enums.MyEnum;
import com.arte.core.enums.access.GrantTargetKindEnum;
import com.arte.core.enums.access.ResourceOwnershipTypeEnum;
import com.arte.core.enums.platform.PrincipalKindEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.enums.workspace.TenantRoleEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.pojo.access.Action;
import com.arte.core.pojo.access.ResourceType;
import com.arte.core.pojo.workspace.PolicyRules;
import com.arte.core.serialize.SerializerFactory;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlatformEnumSerializationTest {
    private final JsonMapper mapper = SerializerFactory.buildJsonMapperWithoutTypeProperty();

    @Test
    void enumValuesFollowExistingNumericWireStyleAndRoundTrip() {
        assertEquals("0", mapper.writeValueAsString(WorkspaceRoleEnum.OWNER));
        assertEquals("1", mapper.writeValueAsString(ResourceOwnershipTypeEnum.WORKSPACE));
        assertEquals(WorkspaceRoleEnum.MEMBER, mapper.readValue("2", WorkspaceRoleEnum.class));
        assertEquals(TenantRoleEnum.GUEST, mapper.readValue("3", TenantRoleEnum.class));
        assertThrows(Exception.class, () -> mapper.readValue("99", WorkspaceRoleEnum.class));
        assertThrows(Exception.class, () -> mapper.readValue("2", ResourceOwnershipTypeEnum.class));
    }

    @Test
    void principalRoundTripPreservesStableSubjectAndUsesDedicatedEnumTypes() {
        var principal = new Principal("stable-subject", PrincipalKindEnum.USER, PrincipalStatusEnum.ACTIVE);
        var json = mapper.writeValueAsString(principal);
        assertEquals(principal, mapper.readValue(json, Principal.class));
        assertEquals("负责人", WorkspaceRoleEnum.OWNER.getDescription());
        assertEquals(WorkspaceRoleEnum.ADMIN, MyEnum.fromValue(WorkspaceRoleEnum.class, 1));
        assertNotEquals((Object) TenantRoleEnum.OWNER, WorkspaceRoleEnum.OWNER);
        assertEquals(WorkspaceRoleEnum.MEMBER,
                WorkspaceRoleEnum.IntegerToWorkspaceRoleEnumConverter.INSTANCE.convert(2));
        assertEquals(2, WorkspaceRoleEnum.WorkspaceRoleEnumToIntegerConverter.INSTANCE.convert(WorkspaceRoleEnum.MEMBER));
    }

    @Test
    void actionAndResourceTypeUseStringNamesAndPolicyMapKeysRoundTrip() {
        var action = new Action("article:read");
        var resourceType = new ResourceType("article");
        assertEquals("\"article:read\"", mapper.writeValueAsString(action));
        assertEquals(action, mapper.readValue("\"article:read\"", Action.class));
        assertEquals("\"article\"", mapper.writeValueAsString(resourceType));
        assertEquals(resourceType, mapper.readValue("\"article\"", ResourceType.class));
        var policy = new PolicyRules(Set.of(resourceType), Map.of(resourceType, Set.of(WorkspaceRoleEnum.MEMBER)),
                false, false, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of(GrantTargetKindEnum.SUBJECT));
        var json = mapper.writeValueAsString(policy);
        assertTrue(json.contains("\"article\""));
        assertFalse(json.contains("ResourceType[value="));
        assertEquals(policy, mapper.readValue(json, PolicyRules.class));
    }
}
