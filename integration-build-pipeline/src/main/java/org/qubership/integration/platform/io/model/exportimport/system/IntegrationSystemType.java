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

package org.qubership.integration.platform.io.model.exportimport.system;

/**
 * Service type recorded in an integration-system export.
 *
 * <p>Library-local copy of the catalog {@code IntegrationSystemType}. The constant names match the
 * catalog enum, so an export deserializes into it unchanged; the catalog seam maps it back to the
 * persistence enum by name.
 */
public enum IntegrationSystemType {
    INTERNAL,
    EXTERNAL,
    IMPLEMENTED
}
