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

package org.qubership.integration.platform.runtime.catalog.service.diagnostic.validations.builtin;

import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.catalog.persistence.configs.entity.diagnostic.ValidationChainAlert;
import org.qubership.integration.platform.catalog.persistence.configs.repository.diagnostic.ChainValidationRepository;
import org.qubership.integration.platform.runtime.catalog.model.diagnostic.ValidationImplementationType;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.ValidationEntityType;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.ValidationSeverity;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.validations.DiagnosticValidationUnexpectedException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Slf4j
@Component
public class LargeSnapshotsNumberValidation extends BuiltinValidation {
    private static final String SNAPSHOT_WARN_COUNT_THRESHOLD_KEY = "snapshotWarnCountThreshold";
    private static final String SNAPSHOT_OLDER_THAN_DAYS_THRESHOLD_KEY = "snapshotOlderThanDaysThreshold";

    private final ChainValidationRepository chainRepository;

    public LargeSnapshotsNumberValidation(ChainValidationRepository chainRepository) {
        super(
                "large-snapshot-number_P860TWJZ",
                "Large number of snapshots",
                "Rule allows to check the number of snapshots that older than configured value.",
                "Large number of snapshots might cause space consumption issues." +
                        " Delete old and unused snapshots and only keep actual ones.",
                ValidationEntityType.CHAIN,
                ValidationImplementationType.BUILT_IN,
                ValidationSeverity.WARNING
        );
        this.chainRepository = chainRepository;

        // HOURS, DAYS, MONTH, etc.
        putProperty(SNAPSHOT_OLDER_THAN_DAYS_THRESHOLD_KEY, "7 days");
        putProperty(SNAPSHOT_WARN_COUNT_THRESHOLD_KEY, 50);
    }

    @Override
    public Collection<ValidationChainAlert> validate() throws DiagnosticValidationUnexpectedException {
        try {
            return processValidation();
        } catch (Exception e) {
            throw new DiagnosticValidationUnexpectedException("Validation failed with an unexpected error: " + e.getMessage(), e);
        }
    }

    private @NotNull List<ValidationChainAlert> processValidation() {
        List<String[]> chainIdsAndSnapshotsCount = chainRepository.findAllForLargeSnapshotsNumberValidation(
                (String) getProperty(SNAPSHOT_OLDER_THAN_DAYS_THRESHOLD_KEY),
                (Integer) getProperty(SNAPSHOT_WARN_COUNT_THRESHOLD_KEY));

        List<ValidationChainAlert> validationAlerts = new ArrayList<>();
        for (String[] entity : chainIdsAndSnapshotsCount) {
            String chainId = entity[0];
            String snapshotsCount = entity[1];

            Optional<Chain> chainOptional = chainRepository.findById(chainId);
            if (chainOptional.isPresent()) {
                ValidationChainAlert alert = ValidationChainAlert.builder()
                        .validationId(getId())
                        .chain(chainOptional.get())
                        .build();
                alert.addProperty(VALIDATION_ALERT_ISSUES_COUNTER_PROP, snapshotsCount);
                validationAlerts.add(alert);
            }
        }

        return validationAlerts;
    }
}
