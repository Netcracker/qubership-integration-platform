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

package org.qubership.integration.platform.runtime.catalog.service.codegen.graphql;

import org.qubership.integration.platform.runtime.catalog.configuration.GraphQLCodegenConfiguration;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;
import org.qubership.integration.platform.runtime.catalog.service.codegen.SystemModelCodeGenerator;
import org.qubership.integration.platform.runtime.catalog.service.codegen.TargetProtocol;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.function.Function;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import static org.qubership.integration.platform.runtime.catalog.service.codegen.PackageNameUtil.buildPackageName;


@Component
@TargetProtocol(protocol = OperationProtocol.GRAPHQL)
public class GraphQLCodeGenerator implements SystemModelCodeGenerator {
    private final Function<String, GraphqlRuntimePojoGenerator> graphqlPojoGeneratorFactory;

    @Autowired
    public GraphQLCodeGenerator(Function<String, GraphqlRuntimePojoGenerator> graphqlPojoGeneratorFactory) {
        this.graphqlPojoGeneratorFactory = graphqlPojoGeneratorFactory;
    }

    @Override
    public Manifest generateManifest(SystemModel model) {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        return manifest;
    }

    @Override
    public Map<String, String> generateCode(SystemModel model) throws Exception {
        String packageName = buildPackageName(GraphQLCodegenConfiguration.CODEGEN_BASE_PACKAGE, model);
        GraphqlRuntimePojoGenerator generator = graphqlPojoGeneratorFactory.apply(packageName);
        return generator.generateCode(model);
    }
}
