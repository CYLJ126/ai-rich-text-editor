package com.arte.ainew.api.model;

import com.arte.ainew.pojo.model.ModelResourceVersion;
import com.arte.ainew.pojo.model.PersonalModels;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.reference.DefinitionRef;

import java.util.List;

/**
 * 普通用户的个人模型发布应用接口；同步数据库线程调用，不要求平台目录管理权限，不执行网络请求。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 20:55 ✾
 */
public interface PersonalModelPublicationService {

    /**
     * 查询当前草稿可采用的审核方案，不允许任意费率移植到其他远端模型。
     *
     * @param actor    当前可信操作者，仍需 model:publish
     * @param scope    模型主空间
     * @param resource 稳定模型资源
     * @return 与当前草稿匹配的精确审核绑定和目录修订
     */
    List<PersonalModels.Option> options(TrustedActor actor, Scope scope, ResourceRef resource);

    /**
     * 原子发布不可变版本，固定验证、凭据及审核目录依据，不创建预算或改写历史。
     *
     * @param actor             当前可信操作者，须具有发布权且为凭据责任主体
     * @param meta              幂等键及服务端追踪信息
     * @param scope             模型主空间
     * @param resource          稳定模型资源
     * @param expectedVersion   模型管理版本，不是草稿或凭据版本
     * @param draftVersion      期望草稿版本
     * @param credentialVersion 期望凭据代次
     * @param validationId      成功验证 ID，须为本主体且与依赖一致
     * @param planRef   选定的审核模型精确绑定
     * @param catalogRevision   选定方案的当前目录修订
     * @return 新精确版本的幂等回执，不表示已具备兼容预算或已调用模型
     */
    CommandReceipt<ModelResourceVersion> publish(TrustedActor actor, CommandMeta meta, Scope scope, ResourceRef resource,
                                                 long expectedVersion, long draftVersion, long credentialVersion, String validationId, DefinitionRef planRef, long catalogRevision);

    /**
     * 查询精确个人发布对当前主体的运行准备状态，缺少兼容预算时明确说明。
     *
     * @param actor    当前可信操作者，须有模型读取权
     * @param scope    模型主空间
     * @param resource 稳定模型资源
     * @param binding  待检查的精确个人绑定，不解析 latest
     * @return 当前准备状态，不替代受理、派发或外发前检查
     */
    PersonalModels.Status status(TrustedActor actor, Scope scope, ResourceRef resource, DefinitionRef binding);
}
