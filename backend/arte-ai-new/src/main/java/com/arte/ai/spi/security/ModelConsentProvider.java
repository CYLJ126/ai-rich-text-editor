package com.arte.ai.spi.security;

import com.arte.base.model.resource.ResourceRef;
import com.arte.base.model.security.EgressRequest;

/**
 * 已明确确认的服务端入口，为实际协议内容和目标建立同意；须独立提交后返回。
 */
@FunctionalInterface
public interface ModelConsentProvider {
    ResourceRef confirm(EgressRequest prepared);
}
