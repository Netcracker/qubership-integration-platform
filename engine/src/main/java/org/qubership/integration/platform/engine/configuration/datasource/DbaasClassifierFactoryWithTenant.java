package org.qubership.integration.platform.engine.configuration.datasource;

import com.netcracker.cloud.context.propagation.core.ContextManager;
import com.netcracker.cloud.dbaas.client.config.MSInfoProvider;
import com.netcracker.cloud.dbaas.client.management.DbaasDbClassifier;
import com.netcracker.cloud.dbaas.client.management.classifier.DbaaSChainClassifierBuilder;
import com.netcracker.cloud.dbaas.client.management.classifier.DbaasClassifierFactory;
import com.netcracker.cloud.framework.contexts.tenant.TenantContextObject;

import java.util.Map;

import static com.netcracker.cloud.framework.contexts.tenant.BaseTenantProvider.TENANT_CONTEXT_NAME;

public class DbaasClassifierFactoryWithTenant extends DbaasClassifierFactory {
    private final String tenant;

    public DbaasClassifierFactoryWithTenant(MSInfoProvider msInfoProvider, String tenant) {
        super(msInfoProvider);
        this.tenant = tenant;
    }

    @Override
    public DbaaSChainClassifierBuilder newTenantClassifierBuilder() {
        return addTenant(super.newTenantClassifierBuilder());
    }

    @Override
    public DbaaSChainClassifierBuilder newTenantClassifierBuilder(DbaaSChainClassifierBuilder dbaaSChainClassifierBuilder) {
        return addTenant(super.newTenantClassifierBuilder(dbaaSChainClassifierBuilder));
    }

    @Override
    public DbaaSChainClassifierBuilder newTenantClassifierBuilder(Map<String, Object> additionalFields) {
        return addTenant(super.newTenantClassifierBuilder(additionalFields));
    }

    private DbaaSChainClassifierBuilder addTenant(DbaaSChainClassifierBuilder builder) {
        return builder.pass(createClassifierBuilderWithTenant());
    }

    private DbaasDbClassifier.Builder createClassifierBuilderWithTenant() {
        return new DbaasDbClassifier.Builder() {
            @Override
            public DbaasDbClassifier build() {
                // TODO: not sure about this part
                ContextManager.set(TENANT_CONTEXT_NAME, new TenantContextObject(tenant));
                return super.build();
            }
        };
    }
}
