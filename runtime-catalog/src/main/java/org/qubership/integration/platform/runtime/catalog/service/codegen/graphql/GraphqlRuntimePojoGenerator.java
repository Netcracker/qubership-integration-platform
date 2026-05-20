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

import com.graphql_java_generator.plugin.CodeTemplate;
import com.graphql_java_generator.plugin.conf.GenerateCodeCommonConfiguration;
import com.graphql_java_generator.plugin.conf.PluginMode;
import com.graphql_java_generator.plugin.generate_code.ExceptionThrower;
import com.graphql_java_generator.plugin.generate_code.GenerateCodeGenerator;
import com.graphql_java_generator.plugin.language.Type;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.ClasspathResourceLoader;
import org.qubership.integration.platform.runtime.catalog.configuration.GraphQLCodegenConfiguration;
import org.qubership.integration.platform.runtime.catalog.model.system.OperationProtocol;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SpecificationSource;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.SystemModel;

import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;


/**
 * This class based on {@link GenerateCodeGenerator}
 */
@Slf4j
public class GraphqlRuntimePojoGenerator {
    private final GraphqlCodeDocumentParser parser;

    private final GenerateCodeCommonConfiguration configuration;

    /**
     * The Velocity engine, that will merge the templates with their context
     */
    private final VelocityEngine velocityEngine;

    public GraphqlRuntimePojoGenerator(GraphqlCodeDocumentParser parser, GenerateCodeCommonConfiguration configuration) {
        this.parser = parser;
        this.configuration = configuration;

        // Initialization for Velocity
        velocityEngine = new VelocityEngine();

        velocityEngine.setProperty(RuntimeConstants.RESOURCE_LOADERS, "classpath, file");

        // Configuration for 'real' executions of the plugin (that is: from the plugin's packaged jar)
        velocityEngine.setProperty("resource.loader.classpath.description", "Velocity Classpath Resource Loader");
        velocityEngine.setProperty("resource.loader.classpath.class", ClasspathResourceLoader.class.getName());

        velocityEngine.init();
    }

    public Map<String, String> generateCode(SystemModel model) throws Exception {
        IntegrationSystem system = model.getSpecificationGroup().getSystem();
        OperationProtocol protocol = system.getProtocol();
        if (!OperationProtocol.GRAPHQL.equals(protocol)) {
            String message = String.format("Wrong system type. Expected: %s. Got: %s.",
                    OperationProtocol.GRAPHQL.name(), protocol.name());
            throw new Exception(message);
        }

        List<SpecificationSource> sources = model.getSpecificationSources();
        if (sources.isEmpty()) {
            String message = String.format("System model %s specification sources are empty", model.getId());
            throw new Exception(message);
        }

        String source = sources.get(0).getSource();
        return generateCode(source);
    }

    private Map<String, String> generateCode(String specificationSource) throws Exception {
        parser.parseSchema(specificationSource);

        Map<String, String> result = new HashMap<>();

        log.debug("Generating objects");
        result.putAll(generateCodeForObjects(parser.getObjectTypes(), "object", resolveTemplate(CodeTemplate.OBJECT)));

        log.debug("Generating interfaces");
        result.putAll(generateCodeForObjects(parser.getInterfaceTypes(), "interface", resolveTemplate(CodeTemplate.INTERFACE)));

        log.debug("Generating unions");
        result.putAll(generateCodeForObjects(parser.getUnionTypes(), "union", resolveTemplate(CodeTemplate.UNION)));

        log.debug("Generating enums");
        result.putAll(generateCodeForObjects(parser.getEnumTypes(), "enum", resolveTemplate(CodeTemplate.ENUM)));

        result.remove(getFullyQualifiedClassName(GraphQLCodegenConfiguration.CODEGEN_QUERY_CLASS));
        result.remove(getFullyQualifiedClassName(GraphQLCodegenConfiguration.CODEGEN_MUTATION_CLASS));

        return result;
    }

    private Map<String, String> generateCodeForObjects(
            List<? extends Type> objects,
            String type,
            String templateFilename
    ) throws RuntimeException {
        return objects.stream().filter(Objects::nonNull)
                .map(object -> generateObjectCode(object, type, templateFilename, parser))
                .collect(Collectors.toMap(Pair::getKey, Pair::getValue));
    }

    private Pair<String, String> generateObjectCode(
            Type object,
            String type,
            String templateFilename,
            GraphqlCodeDocumentParser parser
    ) {
        String targetFileName = (String) execWithOneStringParam("getTargetFileName", object, type);
        if (type.equals("executor") && configuration.getSpringBeanSuffix() != null) {
            targetFileName += configuration.getSpringBeanSuffix();
        }
        String fullyQualifiedClassName = getFullyQualifiedClassName(targetFileName);

        VelocityContext context = getVelocityContext(parser);
        context.put("object", object);
        context.put("targetFileName", targetFileName);
        context.put("type", type);

        log.debug("Generating {} '{}' into {}", type, object.getName(), fullyQualifiedClassName);
        String code = instantiateTemplate(context, templateFilename);
        return Pair.of(fullyQualifiedClassName, code);
    }

    private String instantiateTemplate(VelocityContext context, String templateFilename) {
        StringWriter writer = new StringWriter();
        Template template = velocityEngine.getTemplate(templateFilename, "UTF-8");
        template.merge(context, writer);
        return writer.toString();
    }

    private String getFullyQualifiedClassName(String simpleClassName) {
        String packageName = simpleClassName.startsWith("SpringConfiguration")
                ? configuration.getSpringAutoConfigurationPackage()
                : configuration.getPackageName();
        return packageName + "." + simpleClassName;
    }


    /**
     * Calls the 'methodName' method on the given object
     *
     * @param methodName The name of the method name
     * @param object     The given node, on which the 'methodName' method is to be called
     */
    private Object execWithOneStringParam(String methodName, Object object, String param) {
        try {
            Method getType = object.getClass().getMethod(methodName, String.class);
            return getType.invoke(object, param);
        } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException | NoSuchMethodException
                 | SecurityException e) {
            throw new RuntimeException("Error when trying to execute '" + methodName + "' (with a String param) on '"
                    + object.getClass().getName() + "': " + e.getMessage(), e);
        }
    }

    /**
     * Returns a {@link VelocityContext} with all default values filled.
     */
    private VelocityContext getVelocityContext(GraphqlCodeDocumentParser parser) {
        VelocityContext context = new VelocityContext();
        context.put("sharp", "#");
        context.put("dollar", "$");
        context.put("configuration", configuration);
        context.put("exceptionThrower", new ExceptionThrower());
        // Velocity can't access to enum values. So we add it into the context
        context.put("isPluginModeClient", configuration.getMode() == PluginMode.client);

        context.put("packageUtilName", parser.getUtilPackageName());
        context.put("customScalars", parser.getCustomScalars());
        context.put("directives", parser.getDirectives());
        return context;
    }

    /**
     * Resolves the template for the given key
     */
    protected String resolveTemplate(CodeTemplate template) {
        if (configuration.getTemplates().containsKey(template.name())) {
            return configuration.getTemplates().get(template.name());
        } else {
            return template.getDefaultValue();
        }
    }
}
