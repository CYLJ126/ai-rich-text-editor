package com.arte.ainew.spi.model;

import com.arte.ainew.pojo.model.ModelDrafts.Credential;
import com.arte.ainew.pojo.model.ModelDrafts.Draft;
import com.arte.ainew.pojo.model.ModelDrafts.Validation;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.Scope;

import java.util.Optional;

/**
 * 模型草稿、加密凭据及验证记录的原始持久化端口，不负责计算业务授权。
 * <p>
 * 所有方法必须加入调用方的原可写事务，并按公共规则持有主体及对应租户、空间治理门控。
 * 读取使用当前事实；写入通过版本比较及条件更新防止覆盖竞争结果，不能另开事务模拟原子提交。
 * 凭据记录仅用于服务端存储和受控解密，不能直接作为公开返回对象。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 15:55 ✾
 */
public interface ModelDraftStore {

    /**
     * 锁定并读取模型当前草稿，核对原始资源身份及载荷版本。
     *
     * @param resource 已确定真实范围的稳定模型资源引用
     * @return 当前草稿，尚未保存时为空；缺失不表示可以跳过创建资格检查
     */
    Optional<Draft> draft(ResourceRef resource);

    /**
     * 首次插入或按期望版本替换草稿，实际事务提交仍由调用方负责。
     *
     * @param draft           已通过应用校验、包含下一版本及更新时间的完整草稿
     * @param expectedVersion 期望的原草稿版本；0 表示首次插入，其他值用于条件更新
     * @return 插入或更新成功为 true，已有记录竞争或版本不匹配为 false
     */
    boolean saveDraft(Draft draft, long expectedVersion);

    /**
     * 锁定并读取模型当前内部凭据记录，不解密、不代替维护或用途授权。
     *
     * @param resource 凭据关联的稳定模型资源引用
     * @return 包含密文及责任主体的内部记录，尚未提供凭据时为空
     */
    Optional<Credential> credential(ResourceRef resource);

    /**
     * 首次插入或按期望版本替换当前凭据记录，包括替换密钥和撤销后清除密文的状态变更。
     *
     * @param credential      已确认责任及范围、包含下一版本的加密或撤销记录，不能包含明文
     * @param expectedVersion 期望的原凭据版本；0 表示首次插入，与草稿版本无关
     * @return 插入或更新成功为 true，唯一记录竞争或版本不匹配为 false
     */
    boolean saveCredential(Credential credential, long expectedVersion);

    /**
     * 锁定并读取指定空间内的验证记录，ID 不匹配目标空间时按不存在处理。
     *
     * @param scope 调用方已取得治理门控的租户和空间范围，不得从查询结果自行扩展范围
     * @param id    服务端生成的验证请求 ID
     * @return 当前验证记录，不存在或不属于该空间时为空；请求者权限另由应用层核验
     */
    Optional<Validation> validation(Scope scope, String id);

    /**
     * 在验证受理事务内插入独立请求记录，与幂等回执、审计及事件一并提交。
     *
     * @param validation 服务端构建的初始验证记录，固定依赖版本且记录版本为 0
     * @return 唯一请求记录插入成功为 true，ID 已存在为 false
     */
    boolean insertValidation(Validation validation);

    /**
     * 按原记录版本推进验证状态，用于领取、完成及标记失效；不执行网络请求。
     *
     * @param validation      保留请求身份及依赖、包含下一版本的目标状态记录
     * @param expectedVersion 期望的当前验证记录版本，不是草稿版本或凭据版本
     * @return 版本匹配且更新成功为 true，记录缺失或存在竞争为 false
     */
    boolean replaceValidation(Validation validation, long expectedVersion);
}
