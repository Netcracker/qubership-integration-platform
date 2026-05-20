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

package org.qubership.integration.platform.runtime.catalog.configuration;

import com.graphql_java_generator.plugin.CodeTemplate;
import com.graphql_java_generator.plugin.conf.*;
import graphql.parser.Parser;
import graphql.parser.ParserOptions;
import org.qubership.integration.platform.runtime.catalog.service.codegen.graphql.GraphqlCodeDocumentParser;
import org.qubership.integration.platform.runtime.catalog.service.codegen.graphql.GraphqlRuntimePojoGenerator;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Configuration
public class GraphQLCodegenConfiguration {
    public static final String CODEGEN_BASE_PACKAGE = "org.qubership.integration.engine.graphql.generated";
    public static final String CODEGEN_QUERY_CLASS = "Query";
    public static final String CODEGEN_MUTATION_CLASS = "Mutation";

    private static final int MAX_CHARS_TOKENS = 1_000_000;

    private static final List<CustomScalarDefinition> CUSTOM_SCALARS = List.of(
            new CustomScalarDefinition("DateTime", "org.joda.time.DateTime", "", "", ""),
            new CustomScalarDefinition("Void", "java.lang.Void", "", "", ""),
            new CustomScalarDefinition("JSON", "jakarta.json.Json", "", "", ""),
            new CustomScalarDefinition(
                    "Date",
                    "java.util.Date",
                    "graphql.schema.GraphQLScalarType",
                    "com.graphql_java_generator.customscalars.GraphQLScalarTypeDate.Date",
                    "com.graphql_java_generator.customscalars.GraphQLScalarTypeDate"
            )
    ); // TODO add custom scalars

    private static final Map<String, String> CUSTOM_TEMPLATES_REFS = Map.of(
            CodeTemplate.OBJECT.name(), "graphql/templates/custom_object_type.vm.java",
            CodeTemplate.INTERFACE.name(), "graphql/templates/custom_interface_type.vm.java",
            CodeTemplate.UNION.name(), "graphql/templates/custom_union_type.vm.java",
            CodeTemplate.ENUM.name(), "graphql/templates/custom_enum_type.vm.java"
    );

    @Bean
    public static Parser graphqlParser() {
        return new Parser();
    }

    @Bean("graphqlOperationParserOptions")
    public static ParserOptions graphqlOperationParserOptions() {
        return ParserOptions.getDefaultOperationParserOptions().transform(builder -> builder.maxTokens(MAX_CHARS_TOKENS));
    }

    @Bean("graphqlSdlParserOptions")
    public static ParserOptions graphqlSdlParserOptions() {
        return ParserOptions.getDefaultSdlParserOptions().transform(builder -> builder.maxTokens(MAX_CHARS_TOKENS));
    }

    @Bean
    public Function<CommonConfiguration, GraphqlCodeDocumentParser> graphqlCodeDocumentParserFactory(
            Parser parser,
            @Qualifier("graphqlSdlParserOptions") ParserOptions parserOptions
    ) {
        return configuration -> new GraphqlCodeDocumentParser(parser, parserOptions, configuration);
    }

    @Bean
    public Function<String, GraphqlRuntimePojoGenerator> graphqlPojoGeneratorFactory(
            Function<String, GenerateCodeCommonConfiguration> codeConfigurationFactory,
            Function<CommonConfiguration, GraphqlCodeDocumentParser> graphqlCodeDocumentParserFactory
    ) {
        return packageName -> {
            GenerateCodeCommonConfiguration configuration = codeConfigurationFactory.apply(packageName);
            GraphqlCodeDocumentParser parser = graphqlCodeDocumentParserFactory.apply(configuration);
            return new GraphqlRuntimePojoGenerator(parser, configuration);
        };
    }

    @Bean
    public Function<String, GenerateCodeCommonConfiguration> codeConfigurationFactory() {
        return packageName -> new GenerateCodeCommonConfiguration() {

            @Override
            public List<CustomScalarDefinition> getCustomScalars() {
                return CUSTOM_SCALARS;
            }

            @Override
            public PluginMode getMode() {
                return PluginMode.client;
            }

            @Override
            public String getPackageName() {
                return packageName;
            }

            @Override
            public String getSourceEncoding() {
                return DEFAULT_SOURCE_ENCODING;
            }

            @Override
            public String getSpringBeanSuffix() {
                return DEFAULT_SPRING_BEAN_SUFFIX;
            }

            @Override
            public File getTargetClassFolder() {
                return null;
            }

            @Override
            public File getTargetResourceFolder() {
                return null;
            }

            @Override
            public File getTargetSourceFolder() {
                return null;
            }

            @Override
            public QueryMutationExecutionProtocol getQueryMutationExecutionProtocol() {
                return null;
            }

            @Override
            public boolean isCopyRuntimeSources() {
                return false;
            }

            @Override
            public boolean isGenerateUtilityClasses() {
                return false;
            }

            @Override
            public boolean isSeparateUtilityClasses() {
                return false;
            }

            @Override
            public boolean isUseJakartaEE9() {
                return false;
            }

            @Override
            public Integer getMaxTokens() {
                return MAX_CHARS_TOKENS;
            }

            @Override
            public File getProjectDir() {
                return null;
            }

            @Override
            public File getSchemaFileFolder() {
                return null;
            }

            @Override
            public String getSchemaFilePattern() {
                return null;
            }

            @Override
            public Map<String, String> getTemplates() {
                return CUSTOM_TEMPLATES_REFS;
            }

            @Override
            public boolean isAddRelayConnections() {
                return false;
            }

            @Override
            public boolean isGenerateJacksonAnnotations() {
                return false;
            }

            @Override
            public boolean isSkipGenerationIfSchemaHasNotChanged() {
                return false;
            }

            @Override
            public String getDefaultTargetSchemaFileName() {
                return null;
            }

            @Override
            public void logConfiguration() {

            }

            @Override
            public String getEnumPrefix() {
                return null;
            }

            @Override
            public String getEnumSuffix() {
                return null;
            }

            @Override
            public String getInputPrefix() {
                return null;
            }

            @Override
            public String getInputSuffix() {
                return null;
            }

            @Override
            public String getInterfacePrefix() {
                return null;
            }

            @Override
            public String getInterfaceSuffix() {
                return null;
            }

            @Override
            public String getTypePrefix() {
                return null;
            }

            @Override
            public String getTypeSuffix() {
                return null;
            }

            @Override
            public String getUnionPrefix() {
                return null;
            }

            @Override
            public String getUnionSuffix() {
                return null;
            }
        };
    }
}
