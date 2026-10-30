package com.arte.ainew.spi.model;

import com.arte.ainew.pojo.model.ModelPublication;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.reference.DefinitionRef;

/**
 * 模型发布记录服务接口
 * <p>
 * 管理侧精确发布读取；先检查采用既有配置的受信资格，不以目标模型的 publish 权限认领任意平台配置。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 22:40 ✾
 */
public interface ModelPublicationSource {

    ModelPublication requireCurrent(TrustedActor actor, Scope scope, ModelPublication.Source source, Long catalogRevision, DefinitionRef binding);
}
