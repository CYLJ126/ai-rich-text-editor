package com.arte.ainew.pojo.control;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.validation.ContractChecks;

import java.io.Serializable;
import java.util.Objects;

/**
 * 固定绑定的执行位置；只保存受管引用，不保存回调、实现类名、地址或凭据。
 * 目标声明不证明适配器已注册、协议已支持或当前有权执行。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17
 */
public sealed interface ExecutionTarget extends Serializable permits ExecutionTarget.Local, ExecutionTarget.Remote {
    /**
     * 固定的本地业务工具适配版本，由受信注册表解析，不按 ID 反射加载类或查找任意 Bean。
     *
     * @param adapter 类型为 tool-adapter 的固定版本引用，标识受管本地适配器。
     */
    record Local(DefinitionRef adapter) implements ExecutionTarget {
        public Local {
            Objects.requireNonNull(adapter, "adapter").requireType("tool-adapter");
        }
    }

    /**
     * 远端模型名或工具操作键；操作不是 URL，仍由受管连接及协议适配器解释。
     *
     * @param connection 类型为 connection 的固定版本引用，标识受管连接，不直接携带地址或凭据。
     * @param operation  远端模型名或工具操作键，由对应协议解释，不是任意 HTTP 路径。
     */
    record Remote(DefinitionRef connection, String operation) implements ExecutionTarget {
        public Remote {
            Objects.requireNonNull(connection, "connection").requireType("connection");
            ContractChecks.id(operation, "operation");
        }
    }
}
