package org.qubership.integration.platform.engine.configuration.tenant;

import com.netcracker.cloud.framework.contexts.tenant.TenantContextObject;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class AppDefaultTenantStrategyTest {

    @Test
    void shouldReturnTenantContextObjectWithGivenTenantOnGet() {
        AppDefaultTenantStrategy strategy = new AppDefaultTenantStrategy("default-tenant");

        TenantContextObject result = strategy.get();

        assertEquals("default-tenant", result.getTenant());
    }

    @Test
    void shouldReturnSameInstanceOnRepeatedGetCalls() {
        AppDefaultTenantStrategy strategy = new AppDefaultTenantStrategy("default-tenant");

        TenantContextObject first = strategy.get();
        TenantContextObject second = strategy.get();

        assertSame(first, second);
    }

    @Test
    void shouldIgnoreSetAndAlwaysReturnDefaultTenantOnGet() {
        AppDefaultTenantStrategy strategy = new AppDefaultTenantStrategy("default-tenant");
        TenantContextObject defaultTenant = strategy.get();

        strategy.set(new TenantContextObject("overridden-tenant"));

        assertSame(defaultTenant, strategy.get());
    }

    @Test
    void shouldReturnSupplierProvidingTheSameDefaultTenantInstance() {
        AppDefaultTenantStrategy strategy = new AppDefaultTenantStrategy("default-tenant");

        Supplier<TenantContextObject> supplier = strategy.defaultObjectSupplier();

        assertSame(strategy.get(), supplier.get());
    }
}
