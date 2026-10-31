package com.arte.ainew.spi.model;

import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.reference.DefinitionRef;

import java.util.List;

/**
 * 个人模型配置可使用的受控连接模板端口，连接定义来自服务端审核来源。
 * <p>
 * 模板还须满足当前租户、空间及出口政策，不接收用户自报地址，也不借用平台凭据。
 * 返回值供服务端配置校验和验证流程使用，不直接作为 HTTP 视图或资源授权结论。
 * 当前政策读取依赖原事务及调用方已取得的范围治理门控。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 15:55 ✾
 */
public interface ModelConnectionTemplates {

    /**
     * 筛选目标范围中当前允许的连接模板，移除平台凭据引用。
     *
     * @param scope 准备配置或发起验证的租户和空间范围
     * @return 受支持、已启用且满足政策的服务端模板列表；空名单不表示任意连接可用
     */
    List<ConnectionDefinition> available(Scope scope);

    /**
     * 按精确引用取得当前仍允许使用的模板，模板缺失、停用或被政策禁止时拒绝。
     *
     * @param scope     模板将被使用的租户和空间范围
     * @param reference connection 类型的固定模板引用，必须包含具体版本
     * @return 已重新校验且不带平台凭据引用的连接定义
     */
    ConnectionDefinition require(Scope scope, DefinitionRef reference);

    /**
     * 计算模板内容摘要，识别引用不变但地址、协议、状态或限制已变化的情况。
     * 摘要只用于绑定验证依赖，不授予权限，也不替代当前模板政策检查。
     *
     * @param connection 已从受信来源取得、去除平台凭据的连接模板
     * @return 模板精确引用及相关连接属性的确定性摘要
     */
    String digest(ConnectionDefinition connection);
}
