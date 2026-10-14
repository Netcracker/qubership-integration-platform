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

package org.qubership.integration.platform.codegen.graphql;

/**
 * Fixed naming the GraphQL DTO-library generator relies on.
 *
 * <p>The base package roots every generated class. The query and mutation class names mark the two
 * root operation types the generator drops from its output, because the runtime library needs only
 * the data objects.
 */
public final class GraphqlCodegenConstants {

    public static final String CODEGEN_BASE_PACKAGE = "org.qubership.integration.engine.graphql.generated";
    public static final String CODEGEN_QUERY_CLASS = "Query";
    public static final String CODEGEN_MUTATION_CLASS = "Mutation";

    private GraphqlCodegenConstants() {
    }
}
