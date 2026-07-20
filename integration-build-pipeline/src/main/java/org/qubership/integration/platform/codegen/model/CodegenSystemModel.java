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

import org.qubership.integration.platform.io.model.exportimport.system.OperationProtocol;

import java.util.List;

/**
 * A system model presented to the DTO-library code generators.
 *
 * <p>Carries exactly what the generators read to build source code and a package name: the model
 * identity, the owning system and group names, the protocol that selects the generator, and the
 * specification sources with their text. The catalog supplies this view by wrapping its JPA
 * {@code SystemModel}; the generators never touch persistence types.
 */
public interface CodegenSystemModel {

    String getId();

    String getName();

    String getSystemName();

    String getGroupName();

    OperationProtocol getProtocol();

    List<CodegenSpecificationSource> getSpecificationSources();
}
