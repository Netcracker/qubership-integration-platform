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

package org.qubership.integration.platform.runtime.catalog.service.diagnostic.validations.external;

import jakarta.persistence.EntityManager;
import org.qubership.integration.platform.runtime.catalog.model.diagnostic.ValidationImplementationType;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.ValidationEntityType;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.ValidationSeverity;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.validations.AbstractValidation;
import org.springframework.lang.Nullable;

public abstract class ExternalValidation extends AbstractValidation {

    private EntityManager entityManager;

    public ExternalValidation(
            String id, String title, String description, String suggestion,
            ValidationEntityType entityType, ValidationImplementationType implementationType,
            ValidationSeverity severity
    ) {
        super(id, title, description, suggestion, entityType, implementationType, severity);
    }

    @Nullable
    public final EntityManager getEntityManager() {
        return this.entityManager;
    }

    public final void setEntityManager(EntityManager entityManager) {
        this.entityManager = entityManager;
    }
}
