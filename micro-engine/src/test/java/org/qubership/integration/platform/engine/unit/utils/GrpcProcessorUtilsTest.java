/*
 * Copyright 2024-2025 NetCracker Technology Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.qubership.integration.platform.engine.unit.utils;

import io.grpc.stub.StreamObserver;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Route;
import org.apache.camel.component.grpc.GrpcUtils;
import org.apache.camel.spi.ClassResolver;
import org.apache.camel.spi.Registry;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.metadata.Metadata;
import org.qubership.integration.platform.engine.camel.metadata.MetadataService;
import org.qubership.integration.platform.engine.camel.repository.RegistryHelper;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.util.GrpcProcessorUtils;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class GrpcProcessorUtilsTest {

    static class FooRequest {
    }

    static class FooResponse {
    }

    public static class DummyGrpcImplBase {
        public void myCamelCaseMethod(FooRequest req, StreamObserver<FooResponse> obs) {
        }
    }

    public static class NoSuchMethodGrpcImplBase {
        public void other(FooRequest req, StreamObserver<FooResponse> obs) {
        }
    }

    @Test
    void shouldReturnRequestClassWhenMethodExists() throws Exception {
        Exchange exchange = mock(Exchange.class);
        CamelContext camelContext = mock(CamelContext.class);
        Route route = mock(Route.class);
        Registry registry = mock(Registry.class);
        MetadataService metadataService = mock(MetadataService.class);
        Metadata metadata = mock(Metadata.class);
        Registry runtimeRegistry = mock(Registry.class);
        ClassResolver classResolver = mock(ClassResolver.class);

        when(exchange.getProperty(CamelConstants.Properties.GRPC_SERVICE_NAME, String.class)).thenReturn("org.example.DummyService");
        when(exchange.getProperty(CamelConstants.Properties.GRPC_METHOD_NAME, String.class)).thenReturn("my_method");
        when(exchange.getContext()).thenReturn(camelContext);
        when(exchange.getFromRouteId()).thenReturn("r1");
        when(camelContext.getRoute("r1")).thenReturn(route);
        when(camelContext.getRegistry()).thenReturn(registry);
        when(registry.findSingleByType(MetadataService.class)).thenReturn(metadataService);
        when(metadataService.getMetadata(route)).thenReturn(Optional.of(metadata));
        when(metadata.getDeploymentId()).thenReturn("dep-1");
        when(runtimeRegistry.findSingleByType(ClassResolver.class)).thenReturn(classResolver);

        try (MockedStatic<RegistryHelper> regStatic = mockStatic(RegistryHelper.class);
             MockedStatic<GrpcUtils> grpcStatic = mockStatic(GrpcUtils.class)) {
            regStatic.when(() -> RegistryHelper.getRegistry(camelContext, "dep-1")).thenReturn(runtimeRegistry);

            grpcStatic.when(() -> GrpcUtils.extractServiceName("org.example.DummyService")).thenReturn("DummyService");
            grpcStatic.when(() -> GrpcUtils.extractServicePackage("org.example.DummyService")).thenReturn("org.example");
            grpcStatic.when(() -> GrpcUtils.convertMethod2CamelCase("my_method")).thenReturn("myCamelCaseMethod");
            grpcStatic.when(() -> GrpcUtils.constructGrpcImplBaseClass(eq("org.example"), eq("DummyService"), any(CamelContext.class)))
                    .thenReturn(DummyGrpcImplBase.class);

            Class<?> req = GrpcProcessorUtils.getRequestClass(exchange);
            assertEquals(FooRequest.class, req);
        }
    }

    @Test
    void shouldReturnResponseClassWhenMethodHasStreamObserverGeneric() throws Exception {
        Exchange exchange = mock(Exchange.class);
        CamelContext camelContext = mock(CamelContext.class);
        Route route = mock(Route.class);
        Registry registry = mock(Registry.class);
        MetadataService metadataService = mock(MetadataService.class);
        Metadata metadata = mock(Metadata.class);
        Registry runtimeRegistry = mock(Registry.class);
        ClassResolver classResolver = mock(ClassResolver.class);

        when(exchange.getProperty(CamelConstants.Properties.GRPC_SERVICE_NAME, String.class)).thenReturn("org.example.DummyService");
        when(exchange.getProperty(CamelConstants.Properties.GRPC_METHOD_NAME, String.class)).thenReturn("my_method");
        when(exchange.getContext()).thenReturn(camelContext);
        when(exchange.getFromRouteId()).thenReturn("r1");
        when(camelContext.getRoute("r1")).thenReturn(route);
        when(camelContext.getRegistry()).thenReturn(registry);
        when(registry.findSingleByType(MetadataService.class)).thenReturn(metadataService);
        when(metadataService.getMetadata(route)).thenReturn(Optional.of(metadata));
        when(metadata.getDeploymentId()).thenReturn("dep-1");
        when(runtimeRegistry.findSingleByType(ClassResolver.class)).thenReturn(classResolver);

        try (MockedStatic<RegistryHelper> regStatic = mockStatic(RegistryHelper.class);
             MockedStatic<GrpcUtils> grpcStatic = mockStatic(GrpcUtils.class)) {
            regStatic.when(() -> RegistryHelper.getRegistry(camelContext, "dep-1")).thenReturn(runtimeRegistry);

            grpcStatic.when(() -> GrpcUtils.extractServiceName("org.example.DummyService")).thenReturn("DummyService");
            grpcStatic.when(() -> GrpcUtils.extractServicePackage("org.example.DummyService")).thenReturn("org.example");
            grpcStatic.when(() -> GrpcUtils.convertMethod2CamelCase("my_method")).thenReturn("myCamelCaseMethod");
            grpcStatic.when(() -> GrpcUtils.constructGrpcImplBaseClass(eq("org.example"), eq("DummyService"), any(CamelContext.class)))
                    .thenReturn(DummyGrpcImplBase.class);

            Class<?> resp = GrpcProcessorUtils.getResponseClass(exchange);
            assertEquals(FooResponse.class, resp);
        }
    }

    @Test
    void shouldThrowNoSuchMethodWhenMethodNotFound() {
        Exchange exchange = mock(Exchange.class);
        CamelContext camelContext = mock(CamelContext.class);
        Route route = mock(Route.class);
        Registry registry = mock(Registry.class);
        MetadataService metadataService = mock(MetadataService.class);
        Metadata metadata = mock(Metadata.class);
        Registry runtimeRegistry = mock(Registry.class);
        ClassResolver classResolver = mock(ClassResolver.class);

        when(exchange.getProperty(CamelConstants.Properties.GRPC_SERVICE_NAME, String.class)).thenReturn("org.example.DummyService");
        when(exchange.getProperty(CamelConstants.Properties.GRPC_METHOD_NAME, String.class)).thenReturn("my_method");
        when(exchange.getContext()).thenReturn(camelContext);
        when(exchange.getFromRouteId()).thenReturn("r1");
        when(camelContext.getRoute("r1")).thenReturn(route);
        when(camelContext.getRegistry()).thenReturn(registry);
        when(registry.findSingleByType(MetadataService.class)).thenReturn(metadataService);
        when(metadataService.getMetadata(route)).thenReturn(Optional.of(metadata));
        when(metadata.getDeploymentId()).thenReturn("dep-1");
        when(runtimeRegistry.findSingleByType(ClassResolver.class)).thenReturn(classResolver);

        try (MockedStatic<RegistryHelper> regStatic = mockStatic(RegistryHelper.class);
             MockedStatic<GrpcUtils> grpcStatic = mockStatic(GrpcUtils.class)) {
            regStatic.when(() -> RegistryHelper.getRegistry(camelContext, "dep-1")).thenReturn(runtimeRegistry);

            grpcStatic.when(() -> GrpcUtils.extractServiceName("org.example.DummyService")).thenReturn("DummyService");
            grpcStatic.when(() -> GrpcUtils.extractServicePackage("org.example.DummyService")).thenReturn("org.example");
            grpcStatic.when(() -> GrpcUtils.convertMethod2CamelCase("my_method")).thenReturn("myCamelCaseMethod");
            grpcStatic.when(() -> GrpcUtils.constructGrpcImplBaseClass(eq("org.example"), eq("DummyService"), any(CamelContext.class)))
                    .thenReturn(NoSuchMethodGrpcImplBase.class);

            NoSuchMethodException ex = assertThrows(NoSuchMethodException.class, () -> GrpcProcessorUtils.getRequestClass(exchange));
            assertTrue(ex.getMessage().contains("org.example.DummyService/my_method"));
        }
    }

    @Test
    void shouldFailWhenDeploymentIdMissing() {
        Exchange exchange = mock(Exchange.class);
        CamelContext camelContext = mock(CamelContext.class);
        Route route = mock(Route.class);
        Registry registry = mock(Registry.class);
        MetadataService metadataService = mock(MetadataService.class);

        when(exchange.getProperty(CamelConstants.Properties.GRPC_SERVICE_NAME, String.class)).thenReturn("org.example.DummyService");
        when(exchange.getProperty(CamelConstants.Properties.GRPC_METHOD_NAME, String.class)).thenReturn("my_method");
        when(exchange.getContext()).thenReturn(camelContext);
        when(exchange.getFromRouteId()).thenReturn("r1");
        when(camelContext.getRoute("r1")).thenReturn(route);
        when(camelContext.getRegistry()).thenReturn(registry);
        when(registry.findSingleByType(MetadataService.class)).thenReturn(metadataService);
        when(metadataService.getMetadata(route)).thenReturn(Optional.empty());

        Exception ex = assertThrows(Exception.class, () -> GrpcProcessorUtils.getRequestClass(exchange));
        assertEquals("Failed to get deployment ID", ex.getMessage());
    }
}
