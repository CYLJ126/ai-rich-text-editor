package com.arte.ai.web.controller;

import org.junit.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.Assert.assertNotNull;

public class ToolControllerSecurityTest {

    @Test
    public void shouldProtectEveryToolDomainEndpointWithMethodAuthorization() {
        List<Class<?>> controllers = List.of(
                ToolManagementController.class,
                ToolBindingController.class,
                AssistantToolController.class,
                ToolGatewayController.class,
                ToolApprovalController.class,
                ToolSecurityController.class,
                ToolObservabilityController.class,
                WorkflowController.class);

        for (Class<?> controller : controllers) {
            for (Method method : controller.getDeclaredMethods()) {
                if (!isEndpoint(method)) continue;
                assertNotNull(controller.getSimpleName() + "." + method.getName()
                                + " must declare @PreAuthorize",
                        method.getAnnotation(PreAuthorize.class));
            }
        }
    }

    private boolean isEndpoint(Method method) {
        return method.isAnnotationPresent(RequestMapping.class)
                || method.isAnnotationPresent(GetMapping.class)
                || method.isAnnotationPresent(PostMapping.class)
                || method.isAnnotationPresent(PutMapping.class)
                || method.isAnnotationPresent(PatchMapping.class)
                || method.isAnnotationPresent(DeleteMapping.class);
    }
}
