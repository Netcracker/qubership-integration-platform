package org.qubership.integration.platform.engine.configuration.tenant;

import com.netcracker.cloud.framework.contexts.tenant.DefaultTenantStrategy;
import com.netcracker.cloud.framework.contexts.tenant.TenantContextObject;

import java.util.function.Supplier;

public class AppDefaultTenantStrategy extends DefaultTenantStrategy {

    private final TenantContextObject defaultTenant;

    public AppDefaultTenantStrategy(String defaultTenant) {
        super(() -> new TenantContextObject(defaultTenant));
        this.defaultTenant = new TenantContextObject(defaultTenant);
    }

    @Override
    protected Supplier<TenantContextObject> defaultObjectSupplier() {
        return () -> defaultTenant;
    }
}
