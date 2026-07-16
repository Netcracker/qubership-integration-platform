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

/**
 * An environment a specification declares: its name and its network address.
 *
 * <p>A parser fills in only the bits the specification itself carries. Source type, properties, and
 * the link to the owning system belong to the persistence layer, so this model omits them; the
 * catalog assigns them when it reconciles the environment onto its {@code Environment} entity.
 */
public interface ParsedEnvironment {

    String getName();

    void setName(String name);

    String getAddress();

    void setAddress(String address);
}
