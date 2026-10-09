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

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class PlatformEnumSerializationTest {
    private final JsonMapper mapper = SerializerFactory.buildJsonMapperWithoutTypeProperty();

    @Test
    void allPublicEnumCodesRoundTripWithoutUsingOrdinal() {
        List<Class<?>> types = List.of(
                com.arte.core.enums.platform.PrincipalKindEnum.class,
                com.arte.core.enums.platform.PrincipalStatusEnum.class,
                com.arte.core.enums.platform.CommandStatusEnum.class,
                com.arte.core.enums.platform.CommandDeliveryEnum.class,
                com.arte.core.enums.platform.PlatformErrorCodeEnum.class,
                com.arte.core.enums.workspace.TenantTypeEnum.class,
                com.arte.core.enums.workspace.TenantStateEnum.class,
                com.arte.core.enums.workspace.TenantRoleEnum.class,
                com.arte.core.enums.workspace.WorkspaceStateEnum.class,
                com.arte.core.enums.workspace.WorkspaceRoleEnum.class,
                com.arte.core.enums.workspace.MembershipStateEnum.class,
                com.arte.core.enums.workspace.InvitationStateEnum.class,
                com.arte.core.enums.workspace.JoinOutcomeEnum.class,
                com.arte.core.enums.workspace.ClosureWorkStateEnum.class,
                com.arte.core.enums.workspace.CleanupAuthorizationStateEnum.class,
                com.arte.core.enums.access.AccessReasonEnum.class,
                com.arte.core.enums.access.ResourceOwnershipTypeEnum.class,
                com.arte.core.enums.access.ResourceVisibilityEnum.class,
                com.arte.core.enums.access.GrantTargetKindEnum.class,
                com.arte.core.enums.access.GrantStateEnum.class,
                com.arte.core.enums.access.GrantSourceEnum.class);
        for (Class<?> type : types) {
            for (Object value : type.getEnumConstants()) {
                assertEquals(((MyEnum<?>) value).getValue().toString(), mapper.writeValueAsString(value));
                assertEquals(value, mapper.readValue(mapper.writeValueAsString(value), type));
            }
            assertThrows(Exception.class, () -> mapper.readValue("99999", type));
        }
    }

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
