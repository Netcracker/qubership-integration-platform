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
import org.qubership.integration.platform.catalog.consul.ConsulService;
import org.qubership.integration.platform.catalog.consul.exception.KVNotFoundException;
import org.qubership.integration.platform.catalog.model.chain.SessionsLoggingLevel;
import org.qubership.integration.platform.catalog.model.deployment.properties.DeploymentRuntimeProperties;
import org.qubership.integration.platform.catalog.persistence.configs.entity.chain.Chain;
import org.qubership.integration.platform.catalog.persistence.configs.entity.diagnostic.ValidationChainAlert;
import org.qubership.integration.platform.runtime.catalog.model.diagnostic.ValidationImplementationType;
import org.qubership.integration.platform.runtime.catalog.service.ChainService;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.ValidationEntityType;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.ValidationSeverity;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.validations.DiagnosticValidationUnexpectedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.*;

@Slf4j
@Component
public class ExcessiveLoggingValidation extends BuiltinValidation {
    public static final String CHAIN_SESSIONS_LOGGING_LEVEL_KEY = "chainSessionsLoggingLevel";

    private final ChainService chainService;
    private final ConsulService consulService;

    @Autowired
    public ExcessiveLoggingValidation(ChainService chainService, ConsulService consulService) {
        super(
                "excessive-logging_I8P3SG4G",
                "Deployment has excessive logging settings",
                "Rule allows to detect chains, deployed with \"Debug\" session level of logging.",
                "Utilizing the \"Debug\" logging level might lead" +
                        " to issues with log storage space or drastically degrade performance." +
                        " It is recommended to reduce the level of logging and use \"Debug\" mode only on test environments.",
                ValidationEntityType.CHAIN,
                ValidationImplementationType.BUILT_IN,
                ValidationSeverity.WARNING
        );
        this.chainService = chainService;
        this.consulService = consulService;

        putProperty(CHAIN_SESSIONS_LOGGING_LEVEL_KEY, (Serializable) Set.of(SessionsLoggingLevel.DEBUG.name()));
    }

    @Override
    public Collection<ValidationChainAlert> validate() throws DiagnosticValidationUnexpectedException {
        try {
            return processValidation();
        } catch (KVNotFoundException kvnfe) {
            return Collections.emptyList();
        } catch (Exception e) {
            throw new DiagnosticValidationUnexpectedException("Validation failed with an unexpected error: " + e.getMessage(), e);
        }
    }

    private @NotNull Collection<ValidationChainAlert> processValidation() {
        Map<String, DeploymentRuntimeProperties> runtimeConfigs = consulService.getChainRuntimeConfig();

        Collection<ValidationChainAlert> result = new ArrayList<>();
        for (Map.Entry<String, DeploymentRuntimeProperties> entry : runtimeConfigs.entrySet()) {
            DeploymentRuntimeProperties props = entry.getValue();
            if (((Set<String>) getProperty(CHAIN_SESSIONS_LOGGING_LEVEL_KEY)).contains(props.getSessionsLoggingLevel().name())) {
                Chain chain = chainService.tryFindById(entry.getKey()).orElse(null);

                // if a chain is present only in consul - skip alert
                if (chain != null) {
                    ValidationChainAlert alert = ValidationChainAlert.builder()
                            .validationId(getId())
                            .chain(chain)
                            .build();
                    alert.addProperty("sessionsLoggingLevel", props.getSessionsLoggingLevel().name());
                    result.add(alert);
                }
            }
        }

        return result;
    }
}
