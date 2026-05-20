package org.qubership.integration.platform.engine.configuration.tenant;

import com.netcracker.cloud.context.propagation.core.Strategy;
import com.netcracker.cloud.context.propagation.core.contextdata.IncomingContextData;
import com.netcracker.cloud.framework.contexts.tenant.TenantContextObject;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.CDI;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.context.propagation.CamelExchangeOverrideContextData;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static com.netcracker.cloud.framework.contexts.tenant.TenantContextObject.TENANT_HEADER;
import static org.junit.Assert.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class AppDefaultTenantProviderTest {

    @Test
    void shouldReturnStrategy() {
        try (MockedStatic<CDI> cdi = mockTenantConfiguration("default-tenant")) {
            AppDefaultTenantProvider provider = new AppDefaultTenantProvider();

            Strategy<TenantContextObject> strategy = provider.strategy();

            assertNotNull(strategy);
        }
    }

    @Test
    void shouldReturnMinProviderOrder() {
        try (MockedStatic<CDI> cdi = mockTenantConfiguration("default-tenant")) {
            AppDefaultTenantProvider provider = new AppDefaultTenantProvider();

            assertEquals(Integer.MIN_VALUE, provider.providerOrder());
        }
    }

    @Test
    void shouldReturnTenantFromCamelExchangeOverrideContextDataWhenHeaderIsPresent() {
        try (MockedStatic<CDI> cdi = mockTenantConfiguration("default-tenant")) {
            AppDefaultTenantProvider provider = new AppDefaultTenantProvider();
            CamelExchangeOverrideContextData contextData = mock(CamelExchangeOverrideContextData.class);

            when(contextData.get(TENANT_HEADER)).thenReturn("override-tenant");

            TenantContextObject result = provider.provide(contextData);

            assertEquals("override-tenant", tenant(result));
        }
    }

    @Test
    void shouldReturnTenantContextObjectWithNullTenantWhenCamelExchangeOverrideContextDataDoesNotContainHeader() {
        try (MockedStatic<CDI> cdi = mockTenantConfiguration("default-tenant")) {
            AppDefaultTenantProvider provider = new AppDefaultTenantProvider();
            CamelExchangeOverrideContextData contextData = mock(CamelExchangeOverrideContextData.class);

            when(contextData.get(TENANT_HEADER)).thenReturn(null);

            TenantContextObject result = provider.provide(contextData);

            assertNotNull(result);
            assertNull(tenant(result));
        }
    }

    @Test
    void shouldReturnTenantContextObjectWithNullTenantWhenIncomingContextDataIsNotCamelExchangeOverrideContextData() {
        try (MockedStatic<CDI> cdi = mockTenantConfiguration("default-tenant")) {
            AppDefaultTenantProvider provider = new AppDefaultTenantProvider();
            IncomingContextData contextData = mock(IncomingContextData.class);

            TenantContextObject result = provider.provide(contextData);

            assertNotNull(result);
            assertNull(tenant(result));
        }
    }

    @SuppressWarnings("unchecked")
    private static MockedStatic<CDI> mockTenantConfiguration(String defaultTenant) {
        MockedStatic<CDI> cdi = mockStatic(CDI.class);

        CDI<Object> current = mock(CDI.class);
        Instance<TenantConfiguration> instance = mock(Instance.class);
        TenantConfiguration tenantConfiguration = mock(TenantConfiguration.class);

        when(tenantConfiguration.getDefaultTenant()).thenReturn(defaultTenant);
        when(instance.get()).thenReturn(tenantConfiguration);
        when(current.select(TenantConfiguration.class)).thenReturn((Instance) instance);

        cdi.when(CDI::current).thenReturn(current);
        return cdi;
    }

    private static String tenant(TenantContextObject tenantContextObject) {
        return tenantContextObject.getTenant();
    }
}
