package com.arte.app.service.platform;

import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.mapper.PlatformPersonalPolicyTemplateMapper;
import com.arte.core.pojo.workspace.PersonalTenantPolicyTemplate;
import com.arte.core.pojo.workspace.PolicyRules;
import com.arte.core.spi.platform.PublicTransactionRunner;
import com.arte.core.spi.workspace.PersonalTenantPolicyTemplateStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@ConditionalOnProperty(prefix = "arte.platform.persistence", name = "enabled", havingValue = "true")
public class PersonalTenantPolicyTemplateStoreServiceImpl implements PersonalTenantPolicyTemplateStore {
    private final PlatformPersonalPolicyTemplateMapper mapper;
    private final PublicTransactionRunner transactions;
    private final PlatformJson json;

    public PersonalTenantPolicyTemplateStoreServiceImpl(PlatformPersonalPolicyTemplateMapper mapper,
                                                        @Qualifier("platformTransactionRunner") PublicTransactionRunner transactions, PlatformJson json) {
        this.mapper = mapper;
        this.transactions = transactions;
        this.json = json;
    }

    @Override
    public Optional<PersonalTenantPolicyTemplate> findCurrent() {
        transactions.requireActive();
        return Optional.ofNullable(mapper.selectCurrent()).map(row -> new PersonalTenantPolicyTemplate(
                json.read(row.getRules(), PolicyRules.class), row.getMembersMayCreateWorkspace(),
                row.getPolicySchemaVersion(), row.getRolePresetVersion(), row.getVersion()));
    }
}
