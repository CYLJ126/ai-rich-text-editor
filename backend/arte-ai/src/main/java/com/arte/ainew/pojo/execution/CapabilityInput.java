package com.arte.ainew.pojo.execution;

import com.arte.ainew.pojo.control.CapabilityDescriptor;
import java.io.Serializable;

/**
 * 能力输入扩展端口；能力类别由 Kind 限定，供应商通过适配器扩展。
 * 输入类型须由受信 Schema 注册表登记，不因实现此接口自动获得可执行资格。
 * Java 未命名模块不允许跨包 sealed permits，故不强行封闭跨能力包的实现。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 22:01 ✾
 */
public interface CapabilityInput extends Serializable {
    CapabilityDescriptor.Kind kind();
}
