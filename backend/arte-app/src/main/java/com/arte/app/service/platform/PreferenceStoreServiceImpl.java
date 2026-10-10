package com.arte.app.service.platform;

import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.mapper.PlatformWorkspacePreferenceMapper;
import com.arte.app.pojo.platform.PlatformWorkspacePreferenceDto;

import com.arte.core.pojo.workspace.WorkspacePreference;
import com.arte.core.spi.platform.PublicTransactionRunner;
import com.arte.core.spi.workspace.PreferenceStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * 空间偏好服务
 * <p>
 * 锁账号和稳定身份后初始化，默认关联受保护；业务侧通过 core SPI 调用，数据库写入参与原物理库事务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
@Service("preferenceStoreService")
@ConditionalOnProperty(prefix = "arte.platform.persistence", name = "enabled", havingValue = "true")
public class PreferenceStoreServiceImpl extends PlatformStoreSupport<PlatformWorkspacePreferenceMapper, PlatformWorkspacePreferenceDto> implements PreferenceStore {

    private final PlatformJson json;

    public PreferenceStoreServiceImpl(
            @Qualifier("platformWorkspacePreferenceMapper") PlatformWorkspacePreferenceMapper mapper,
            @Qualifier("platformTransactionRunner") PublicTransactionRunner tx,
            @Qualifier("platformJson") PlatformJson json) {
        super(mapper, tx);
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public void lockSubject(String subject) {
        tx.requireActive();
        String external = baseMapper.selectExternalId(subject);
        if (external == null) throw new IllegalStateException("Missing stable identity gate");
        if (baseMapper.lockAccount(Long.parseLong(external)) == null)
            throw new IllegalStateException("Missing account gate");
        if (baseMapper.lockIdentity(subject, external) == null)
            throw new IllegalStateException("Identity gate changed");
        subjectHeld(subject);
    }

    @Override
    public Optional<WorkspacePreference> find(String subject) {
        return one(() -> baseMapper.selectById(subject), row -> PlatformRecords.preference(row, json));
    }

    @Override
    public Optional<WorkspacePreference> findCurrent(String subject) {
        tx.requireActive();
        return one(() -> baseMapper.selectCurrent(subject), row -> PlatformRecords.preference(row, json));
    }

    @Override
    public boolean insert(WorkspacePreference value) {
        requireSubject(value.subjectId());
        return insertRecord(() -> baseMapper.insert(PlatformRecords.preference(value, json)));
    }

    @Override
    public boolean replace(WorkspacePreference value, long expected) {
        requireSubject(value.subjectId());
        next(value.version(), expected);
        return updateRecord(() -> baseMapper.replaceCas(PlatformRecords.preference(value, json), expected));
    }
}
