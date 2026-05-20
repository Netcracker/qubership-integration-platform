package org.qubership.integration.platform.engine.configuration.tenant;

import com.netcracker.cloud.context.propagation.core.Strategy;
import com.netcracker.cloud.context.propagation.core.contextdata.IncomingContextData;
import com.netcracker.cloud.framework.contexts.tenant.DefaultTenantProvider;
import com.netcracker.cloud.framework.contexts.tenant.TenantContextObject;
import org.jetbrains.annotations.Nullable;
import org.qubership.integration.platform.engine.camel.context.propagation.CamelExchangeOverrideContextData;

import static com.netcracker.cloud.framework.contexts.tenant.TenantContextObject.TENANT_HEADER;
import static java.util.Objects.nonNull;

public class AppDefaultTenantProvider extends DefaultTenantProvider {

    private final AppDefaultTenantStrategy defaultTenantStrategy;

    public AppDefaultTenantProvider(String defaultTenant) {
        this.defaultTenantStrategy = new AppDefaultTenantStrategy(defaultTenant);
    }

    @Override
    public Strategy<TenantContextObject> strategy() {
        return defaultTenantStrategy;
    }

    @Override
    public int providerOrder() {
        return Integer.MIN_VALUE;
    }

    @Override
    public TenantContextObject provide(@Nullable IncomingContextData incomingContextData) {
        if (incomingContextData instanceof CamelExchangeOverrideContextData contextData && nonNull(contextData.get(TENANT_HEADER))) {
            return new TenantContextObject(String.valueOf(contextData.get(TENANT_HEADER)));
        }

        return super.provide(incomingContextData);
    }
}
