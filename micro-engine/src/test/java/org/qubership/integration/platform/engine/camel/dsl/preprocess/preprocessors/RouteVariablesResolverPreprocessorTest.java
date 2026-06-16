package org.qubership.integration.platform.engine.camel.dsl.preprocess.preprocessors;

import org.apache.camel.CamelContext;
import org.apache.camel.spi.Registry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.metadata.RouteRegistrationInfo;
import org.qubership.integration.platform.engine.metadata.RouteType;
import org.qubership.integration.platform.engine.service.RouteRegistrationService;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class RouteVariablesResolverPreprocessorTest {

    @Mock
    private CamelContext camelContext;

    @Mock
    private Registry registry;

    private RouteVariablesResolverPreprocessor preprocessor;

    @BeforeEach
    void setUp() {
        when(camelContext.getRegistry()).thenReturn(registry);
        preprocessor = new RouteVariablesResolverPreprocessor(camelContext);
    }

    @Test
    void shouldReplaceExternalSenderRouteVariableWithGatewayPrefix() throws Exception {
        RouteRegistrationInfo routeInfo = externalRoute(
            "ordersGatewayPrefix",
            "/api/orders",
            RouteType.EXTERNAL_SENDER
        );

        stubRoutes(routeInfo);

        try (MockedStatic<RouteRegistrationService> routeRegistrationService =
                 mockStatic(RouteRegistrationService.class)) {
            stubFormattedRoute(routeRegistrationService, routeInfo);

            String result = preprocessor.apply("toD(\"http://host%%{ordersGatewayPrefix}/send\")");

            assertEquals("toD(\"http://host/api/orders/send\")", result);
        }
    }

    @Test
    void shouldReplaceExternalServiceRouteVariableWithGatewayPrefix() throws Exception {
        RouteRegistrationInfo routeInfo = externalRoute(
            "inventoryGatewayPrefix",
            "/api/inventory",
            RouteType.EXTERNAL_SERVICE
        );

        stubRoutes(routeInfo);

        try (MockedStatic<RouteRegistrationService> routeRegistrationService =
                 mockStatic(RouteRegistrationService.class)) {
            stubFormattedRoute(routeRegistrationService, routeInfo);

            String result = preprocessor.apply("uri=\"%%{inventoryGatewayPrefix}/items\"");

            assertEquals("uri=\"/api/inventory/items\"", result);
        }
    }

    @Test
    void shouldReplaceExternalRouteVariableWithEmptyStringWhenGatewayPrefixIsNull() throws Exception {
        RouteRegistrationInfo routeInfo = externalRoute(
            "emptyGatewayPrefix",
            null,
            RouteType.EXTERNAL_SERVICE
        );

        stubRoutes(routeInfo);

        try (MockedStatic<RouteRegistrationService> routeRegistrationService =
                 mockStatic(RouteRegistrationService.class)) {
            stubFormattedRoute(routeRegistrationService, routeInfo);

            String result = preprocessor.apply("uri=\"%%{emptyGatewayPrefix}/items\"");

            assertEquals("uri=\"/items\"", result);
        }
    }

    @Test
    void shouldIgnoreRouteWhenVariableNameIsNull() throws Exception {
        RouteRegistrationInfo routeInfo = routeWithNullVariableName();

        stubRoutes(routeInfo);

        try (MockedStatic<RouteRegistrationService> routeRegistrationService =
                 mockStatic(RouteRegistrationService.class)) {
            stubFormattedRoute(routeRegistrationService, routeInfo);

            String result = preprocessor.apply("uri=\"%%{missingVariable}/items\"");

            assertEquals("uri=\"%%{missingVariable}/items\"", result);
        }
    }

    @Test
    void shouldIgnoreRouteWhenTypeIsNotExternal() throws Exception {
        RouteRegistrationInfo routeInfo = routeWithNonExternalType();

        stubRoutes(routeInfo);

        try (MockedStatic<RouteRegistrationService> routeRegistrationService =
                 mockStatic(RouteRegistrationService.class)) {
            stubFormattedRoute(routeRegistrationService, routeInfo);

            String result = preprocessor.apply("uri=\"%%{internalGatewayPrefix}/items\"");

            assertEquals("uri=\"%%{internalGatewayPrefix}/items\"", result);
        }
    }

    @Test
    void shouldReplaceAllExternalRouteVariables() throws Exception {
        RouteRegistrationInfo senderRoute = externalRoute(
            "ordersGatewayPrefix",
            "/api/orders",
            RouteType.EXTERNAL_SENDER
        );
        RouteRegistrationInfo serviceRoute = externalRoute(
            "inventoryGatewayPrefix",
            "/api/inventory",
            RouteType.EXTERNAL_SERVICE
        );

        stubRoutes(senderRoute, serviceRoute);

        try (MockedStatic<RouteRegistrationService> routeRegistrationService =
                 mockStatic(RouteRegistrationService.class)) {
            stubFormattedRoute(routeRegistrationService, senderRoute);
            stubFormattedRoute(routeRegistrationService, serviceRoute);

            String result = preprocessor.apply(
                "orders=%%{ordersGatewayPrefix};inventory=%%{inventoryGatewayPrefix}"
            );

            assertEquals("orders=/api/orders;inventory=/api/inventory", result);
        }
    }

    @Test
    void shouldUseFormattedRouteInfoWhenResolvingVariables() throws Exception {
        RouteRegistrationInfo originalRouteInfo = mock(RouteRegistrationInfo.class);
        RouteRegistrationInfo formattedRouteInfo = externalRoute(
            "formattedGatewayPrefix",
            "/api/formatted",
            RouteType.EXTERNAL_SERVICE
        );

        stubRoutes(originalRouteInfo);

        try (MockedStatic<RouteRegistrationService> routeRegistrationService =
                 mockStatic(RouteRegistrationService.class)) {
            routeRegistrationService.when(() -> RouteRegistrationService.formatServiceRoutes(originalRouteInfo))
                .thenReturn(formattedRouteInfo);

            String result = preprocessor.apply("uri=\"%%{formattedGatewayPrefix}/items\"");

            assertEquals("uri=\"/api/formatted/items\"", result);
        }
    }

    @Test
    void shouldReturnOriginalContentWhenRoutesAreMissing() throws Exception {
        stubRoutes();

        String result = preprocessor.apply("uri=\"%%{gatewayPrefix}/items\"");

        assertEquals("uri=\"%%{gatewayPrefix}/items\"", result);
    }

    private void stubRoutes(RouteRegistrationInfo... routeInfos) {
        when(registry.findByType(RouteRegistrationInfo.class)).thenReturn(Set.of(routeInfos));
    }

    private static void stubFormattedRoute(
        MockedStatic<RouteRegistrationService> routeRegistrationService,
        RouteRegistrationInfo routeInfo
    ) {
        routeRegistrationService.when(() -> RouteRegistrationService.formatServiceRoutes(routeInfo))
            .thenReturn(routeInfo);
    }

    private static RouteRegistrationInfo externalRoute(
        String variableName,
        String gatewayPrefix,
        RouteType routeType
    ) {
        RouteRegistrationInfo routeInfo = mock(RouteRegistrationInfo.class);
        when(routeInfo.getVariableName()).thenReturn(variableName);
        when(routeInfo.getType()).thenReturn(routeType);
        when(routeInfo.getGatewayPrefix()).thenReturn(gatewayPrefix);
        return routeInfo;
    }

    private static RouteRegistrationInfo routeWithNullVariableName() {
        RouteRegistrationInfo routeInfo = mock(RouteRegistrationInfo.class);
        when(routeInfo.getVariableName()).thenReturn(null);
        return routeInfo;
    }

    private static RouteRegistrationInfo routeWithNonExternalType() {
        RouteRegistrationInfo routeInfo = mock(RouteRegistrationInfo.class);
        when(routeInfo.getVariableName()).thenReturn("internalGatewayPrefix");
        when(routeInfo.getType()).thenReturn(null);
        return routeInfo;
    }
}
