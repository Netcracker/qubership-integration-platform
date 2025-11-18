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

package org.qubership.integration.platform.engine.util;

import io.grpc.MethodDescriptor;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Route;
import org.apache.camel.component.grpc.GrpcUtils;
import org.apache.camel.spi.ClassResolver;
import org.qubership.integration.platform.engine.camel.metadata.Metadata;
import org.qubership.integration.platform.engine.camel.metadata.MetadataService;
import org.qubership.integration.platform.engine.camel.repository.RegistryHelper;
import org.qubership.integration.platform.engine.model.constants.CamelConstants;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.Arrays;

public class GrpcProcessorUtils {

    private GrpcProcessorUtils() {}

    public static Class<?> getRequestClass(Exchange exchange) throws Exception {
        Method method = getMainServiceMethod(exchange);
        return method.getParameterTypes()[0]; // First argument of method
    }

    public static Class<?> getResponseClass(Exchange exchange) throws Exception {
        Method method = getMainServiceMethod(exchange);
        Type[] types = method.getGenericParameterTypes();
        ParameterizedType pType = (ParameterizedType) types[1];
        return (Class<?>) pType.getActualTypeArguments()[0]; // Generic from the second argument of method
    }

    /***
     * Retrieves method from generated class for GRPC service
     *
     * @param exchange
     * @return main method to call
     * @throws NoSuchMethodException
     */
    private static Method getMainServiceMethod(Exchange exchange) throws Exception {
        String fullServiceName = exchange.getProperty(CamelConstants.Properties.GRPC_SERVICE_NAME, String.class);
        String methodName = exchange.getProperty(CamelConstants.Properties.GRPC_METHOD_NAME, String.class);
        String serviceName = GrpcUtils.extractServiceName(fullServiceName);
        String servicePackage = GrpcUtils.extractServicePackage(fullServiceName);
        String camelCaseMethodName = GrpcUtils.convertMethod2CamelCase(methodName);

        ClassResolver classResolver = getClassResolver(exchange);
        CamelContext contextWithCustomClassResolver =
                createContextProxyWithClassResolver(exchange.getContext(), classResolver);

        Class<?> grpcServiceClass = GrpcUtils.constructGrpcImplBaseClass(servicePackage, serviceName, contextWithCustomClassResolver);
        return Arrays.stream(grpcServiceClass.getMethods())
                .filter(m -> camelCaseMethodName.equals(m.getName()))
                .findFirst()
                .orElseThrow(() -> {
                    String message = String.format("gRPC method not found: %s",
                            MethodDescriptor.generateFullMethodName(fullServiceName, methodName));
                    return new NoSuchMethodException(message);
                });
    }

    private static ClassResolver getClassResolver(Exchange exchange) throws Exception {
        CamelContext context = exchange.getContext();
        Route route = context.getRoute(exchange.getFromRouteId());
        MetadataService metadataService = context.getRegistry().findSingleByType(MetadataService.class);
        String deploymentId = metadataService.getMetadata(route)
                .map(Metadata::getDeploymentId)
                .orElseThrow(() -> new Exception("Failed to get deployment ID"));
        return RegistryHelper.getRegistry(context, deploymentId).findSingleByType(ClassResolver.class);
    }

    private static CamelContext createContextProxyWithClassResolver(
            CamelContext context,
            ClassResolver classResolver
    ) {
        return (CamelContext) Proxy.newProxyInstance(
                context.getClass().getClassLoader(),
                new Class[] {CamelContext.class},
                (proxy, method, args) -> {
                    return method.getName().equals("getClassResolver")
                            ? classResolver
                            : method.invoke(context, args);
                }
        );
    }
}
