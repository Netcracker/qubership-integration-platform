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

package org.qubership.integration.platform.codegen.model;

/**
 * A specification source presented to the DTO-library code generators.
 *
 * <p>Carries the file name and the raw source text the generators feed to the protocol compiler.
 * Unlike the import-side specification-source model, this view includes the source text, because
 * the generators read it directly.
 */
public interface CodegenSpecificationSource {

    String getName();

    String getSource();
}
