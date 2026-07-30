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

package org.qubership.integration.platform.runtime.catalog.model.system;

import com.fasterxml.jackson.annotation.JsonAlias;

public enum SystemModelSource {
    // CUSTOMER_MANUAL left this enum in this release: nothing ever produced it, but archives written before the
    // removal carry it and would fail to deserialize. The alias reads them as MANUAL, which is what they meant.
    // Rows already in the database are remapped by the api-model-storage migration — Hibernate maps the column by
    // enum name and never sees this annotation.
    @JsonAlias("CUSTOMER_MANUAL")
    MANUAL,
    DISCOVERED
}
