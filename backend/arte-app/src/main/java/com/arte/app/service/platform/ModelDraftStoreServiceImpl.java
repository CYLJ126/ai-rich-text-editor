package com.arte.app.service.platform;

import com.arte.ainew.pojo.model.ModelDrafts.Credential;
import com.arte.ainew.pojo.model.ModelDrafts.Draft;
import com.arte.ainew.pojo.model.ModelDrafts.Validation;
import com.arte.ainew.spi.model.ModelDraftStore;
import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.mapper.PlatformModelDraftMapper;
import com.arte.app.platform.persistence.mapper.PlatformModelResourceMapper;
import com.arte.app.pojo.platform.PlatformModelResourceDto;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.spi.platform.PublicTransactionRunner;

import java.util.Optional;

/**
 * 模型草稿、加密凭据和验证记录的 MyBatis 持久化适配，所有 SQL 使用独立平台 Mapper 及 appDataSource。
 * <p>
 * 加入调用方的原可写事务，要求已取得主体及对应租户、空间治理门控；当前读取锁定记录并核对资源身份、范围和载荷版本。
 * 首次写入依赖唯一键，后续更新显式比较原版本，竞争失败返回 false，由应用服务决定整次业务事务如何处理。
 * <p>
 * 本类只保存原始事实，不计算资源授权、不解密密钥、不发送网络请求，也不自行提交事务。
 * 凭据撤销同样遵守事务和治理门控；验证 ID 不匹配调用范围时按不存在处理，不能借查询结果扩展已锁范围。
 * 继承的 MyBatis-Plus 方法含事务注解，Spring 会创建 CGLIB 子类代理，因此本类不能声明为 final。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 15:55 ✾
 */
public class ModelDraftStoreServiceImpl extends PlatformStoreSupport<PlatformModelResourceMapper, PlatformModelResourceDto> implements ModelDraftStore {

    private final PlatformModelDraftMapper mapper;
    private final PlatformJson json;

    public ModelDraftStoreServiceImpl(PlatformModelResourceMapper resources, PlatformModelDraftMapper mapper, PublicTransactionRunner tx, PlatformJson json) {
        super(resources, tx);
        this.mapper = mapper;
        this.json = json;
    }

    private void gate() {
        tx.requireWritable();
        requireAnySubject();
    }

    private void scope(com.arte.core.pojo.platform.Scope scope) {
        requireScope(scope);
    }

    private String key(ResourceRef ref) {
        return com.arte.core.application.platform.DefaultCommandExecutor.digest(java.util.List.of(ref.tenantId(), ref.type().value(), ref.resourceId()));
    }

    @Override
    public Optional<Draft> draft(ResourceRef ref) {
        gate();
        requireTenant(ref.tenantId());
        var row = mapper.draft(key(ref));
        if (row == null) return Optional.empty();
        var value = json.read(row.payload(), Draft.class);
        scope(value.scope());
        if (!value.resource().equals(ref) || value.version() != row.version()) throw unavailable();
        return Optional.of(value);
    }

    @Override
    public boolean saveDraft(Draft value, long expected) {
        gate();
        scope(value.scope());
        return expected == 0 ? insertRecord(() -> mapper.insertDraft(key(value.resource()), value.version(), json.write(value)))
                : updateRecord(() -> mapper.replaceDraft(key(value.resource()), expected, value.version(), json.write(value)));
    }

    @Override
    public Optional<Credential> credential(ResourceRef ref) {
        gate();
        requireTenant(ref.tenantId());
        var row = mapper.credential(key(ref));
        if (row == null) return Optional.empty();
        var value = json.read(row.payload(), Credential.class);
        scope(value.scope());
        if (!value.resource().equals(ref) || value.version() != row.version()) throw unavailable();
        return Optional.of(value);
    }

    @Override
    public boolean saveCredential(Credential value, long expected) {
        gate();
        scope(value.scope());
        return expected == 0 ? insertRecord(() -> mapper.insertCredential(key(value.resource()), value.version(), json.write(value)))
                : updateRecord(() -> mapper.replaceCredential(key(value.resource()), expected, value.version(), json.write(value)));
    }

    @Override
    public Optional<Validation> validation(com.arte.core.pojo.platform.Scope expectedScope, String id) {
        gate();
        var row = mapper.validation(id);
        if (row == null) return Optional.empty();
        var value = json.read(row.payload(), Validation.class);
        if (!value.scope().equals(expectedScope)) return Optional.empty();
        // 不能根据查询结果另取一个空间的锁；记录须匹配调用方已经持有的范围门控。
        scope(value.scope());
        if (!value.id().equals(id) || value.version() != row.version()) throw unavailable();
        return Optional.of(value);
    }

    @Override
    public boolean insertValidation(Validation value) {
        gate();
        scope(value.scope());
        return insertRecord(() -> mapper.insertValidation(value.id(), key(value.resource()), value.version(), json.write(value)));
    }

    @Override
    public boolean replaceValidation(Validation value, long expected) {
        gate();
        scope(value.scope());
        return updateRecord(() -> mapper.replaceValidation(value.id(), expected, value.version(), json.write(value)));
    }

    private static PlatformException unavailable() {
        return new PlatformException(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, "model-draft-store");
    }
}
