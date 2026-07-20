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

package org.qubership.integration.platform.parsers.model;

import java.util.List;

/**
 * The system model a specification parser produces from a specification.
 *
 * <p>A parser fills in only what the specification itself carries: the description, the declared
 * version, and the operations. The version is the value declared in the specification, or
 * {@code null} when the specification declares none; in that case the catalog generates an
 * incrementing version. Identity, the resolved version name, the source, and the link to the owning
 * specification group belong to the persistence layer and are assigned when the catalog maps this
 * model onto its {@code SystemModel} entity.
 *
 * <p>The model also carries the environments the specification declares. A parser fills them from
 * the specification's server or endpoint definitions; the catalog reconciles them against the owning
 * system after mapping. Parsers that declare no environments leave the list empty.
 */
public interface ParsedSystemModel {

    String getDescription();

    void setDescription(String description);

    String getVersion();

    void setVersion(String version);

    List<ParsedOperation> getOperations();

    void setOperations(List<ParsedOperation> operations);

    List<ParsedEnvironment> getEnvironments();

    void setEnvironments(List<ParsedEnvironment> environments);
}
