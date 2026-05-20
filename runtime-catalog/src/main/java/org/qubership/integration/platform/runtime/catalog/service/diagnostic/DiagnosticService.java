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

package org.qubership.integration.platform.runtime.catalog.service.diagnostic;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.qubership.integration.platform.runtime.catalog.model.diagnostic.ValidationAlertsSet;
import org.qubership.integration.platform.runtime.catalog.model.filter.FilterCondition;
import org.qubership.integration.platform.runtime.catalog.model.filter.FilterFeature;
import org.qubership.integration.platform.runtime.catalog.persistence.TransactionHandler;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.ConfigParameter;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.diagnostic.ValidationChainAlert;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.diagnostic.ValidationState;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.diagnostic.ValidationStatus;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.diagnostic.ValidationChainAlertRepository;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.diagnostic.ValidationStatusRepository;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.FilterRequestDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.diagnostic.DiagnosticValidationFilterDTO;
import org.qubership.integration.platform.runtime.catalog.service.ConfigParameterService;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.validations.AbstractValidation;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.validations.DiagnosticValidationUnexpectedException;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.validations.ValidationAlreadyInProgressUnexpectedException;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.validations.builtin.BuiltinValidation;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.validations.external.ExternalValidation;
import org.qubership.integration.platform.runtime.catalog.service.filter.ChainAlertFilterSpecificationBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.annotation.Nullable;

@Slf4j
@Component
public class DiagnosticService {
    private static final String DIAGNOSTIC_NAMESPACE = "diagnostic";
    private static final String DIAGNOSTIC_VALIDATION_STATE_UPDATE_LOCK_NAME = "diagnosticValidationUpdateLock";
    private static final int VALIDATION_DB_LOCK_TIMEOUT_MINUTES = 15;

    // <validationId, validation>
    private final Map<String, AbstractValidation> validations = new HashMap<>();

    private final ValidationChainAlertRepository chainAlertRepository;
    private final ValidationStatusRepository validationStatusRepository;
    private final ConfigParameterService configParameterService;
    private final TransactionHandler transactionHandler;
    private final ChainAlertFilterSpecificationBuilder chainAlertSpecBuilder;

    private final EntityManager entityManager;

    @Autowired
    public DiagnosticService(ValidationChainAlertRepository chainAlertRepository,
                             List<BuiltinValidation> builtinValidations,
                             ValidationStatusRepository validationStatusRepository,
                             ConfigParameterService configParameterService,
                             TransactionHandler transactionHandler,
                             ChainAlertFilterSpecificationBuilder chainAlertSpecBuilder,
                             EntityManager entityManager) {
        this.validationStatusRepository = validationStatusRepository;
        this.configParameterService = configParameterService;
        this.transactionHandler = transactionHandler;
        this.chainAlertSpecBuilder = chainAlertSpecBuilder;
        this.validations.putAll(
                builtinValidations.stream().collect(Collectors.toMap(AbstractValidation::getId, Function.identity())));
        this.chainAlertRepository = chainAlertRepository;
        this.entityManager = entityManager;
    }

    @Transactional
    public List<Pair<AbstractValidation, ValidationAlertsSet>> getFilteredValidations(DiagnosticValidationFilterDTO filterRequest) {
        // Map<validationId, validations_with_filtered_alerts>
        Map<String, Pair<AbstractValidation, ValidationAlertsSet>> result;

        if (filterRequest == null
                || (StringUtils.isEmpty(filterRequest.getSearchString()) && CollectionUtils.isEmpty(filterRequest.getFilters()))) {
            result = validations.values().stream().collect(
                    Collectors.toMap(AbstractValidation::getId, validation -> Pair.of(validation,
                            ValidationAlertsSet.builder()
                                    .alertsCount(getAlertsCount(validation.getId()))
                                    .build())));
        } else {
            // search has higher priority over filters
            boolean searchMode = StringUtils.isNotEmpty(filterRequest.getSearchString());
            List<FilterRequestDTO> filters = searchMode
                    ? Stream.of(
                            FilterFeature.CHAIN_NAME,
                            FilterFeature.ELEMENT_NAME,
                            FilterFeature.ELEMENT_TYPE)
                        .map(feature ->
                                FilterRequestDTO
                                        .builder()
                                        .feature(feature)
                                        .value(filterRequest.getSearchString())
                                        .condition(FilterCondition.CONTAINS)
                                        .build()
                        )
                        .toList()
                    : filterRequest.getFilters();

            result = executeChainAlertFilterQuery(
                    filters.stream().filter(filter -> filter.getFeature() != FilterFeature.VALIDATION_SEVERITY).toList(), searchMode);
            result = applyExtraFilters(result, filters, filterRequest.getSearchString(), searchMode);
        }

        return result.values().stream().toList();
    }

    private Map<String, Pair<AbstractValidation, ValidationAlertsSet>> executeChainAlertFilterQuery(List<FilterRequestDTO> filters,
                                                                                                    boolean searchMode) {
        Map<String, Pair<AbstractValidation, ValidationAlertsSet>> result = new HashMap<>();
        Specification<ValidationChainAlert> specification = searchMode
                ? chainAlertSpecBuilder.buildSearch(filters)
                : chainAlertSpecBuilder.buildFilter(filters);

        List<ValidationChainAlert> chainAlerts = chainAlertRepository.findAll(specification);

        // assign alerts to validations
        for (ValidationChainAlert chainAlert : chainAlerts) {
            // skip alerts from unknown validations
            if (validations.containsKey(chainAlert.getValidationId())) {
                Pair<AbstractValidation, ValidationAlertsSet> resultPair =
                        result.computeIfAbsent(chainAlert.getValidationId(),
                                validationId -> Pair.of(validations.get(validationId), new ValidationAlertsSet()));

                resultPair.getRight().getChainAlerts().add(chainAlert);
            }
        }

        // count alerts and check includeEntities flag
        for (Pair<AbstractValidation, ValidationAlertsSet> pair : result.values()) {
            ValidationAlertsSet alertsSet = pair.getRight();
            long alertsCount = getAlertsCount(alertsSet.getChainAlerts());
            alertsSet.setAlertsCount(alertsCount);
        }
        return result;
    }

    private Map<String, Pair<AbstractValidation, ValidationAlertsSet>> applyExtraFilters(Map<String, Pair<AbstractValidation, ValidationAlertsSet>> result,
                                                                                         List<FilterRequestDTO> filters, String searchString,
                                                                                         boolean searchMode) {
        if (filters == null || filters.isEmpty()) {
            return result;
        }

        if (searchMode) {
            List<AbstractValidation> filteredValidations =
                    validations.values().stream()
                            .filter(validation -> validation.getTitle().toLowerCase().contains(searchString.toLowerCase())).toList();
            for (AbstractValidation filteredValidation : filteredValidations) {
                if (!result.containsKey(filteredValidation.getId())) {
                    List<ValidationChainAlert> alerts = getAllChainAlertsByValidationId(filteredValidation.getId());
                    long count = getAlertsCount(alerts);
                    ValidationAlertsSet alertsSet = ValidationAlertsSet.builder()
                            .alertsCount(count)
                            .chainAlerts(alerts)
                            .build();

                    result.put(filteredValidation.getId(), Pair.of(filteredValidation, alertsSet));
                }
            }
        } else {
            boolean filterApplied = false;
            Set<String> filteredValidations = new HashSet<>();
            for (FilterRequestDTO filter : filters) {
                if (Objects.requireNonNull(filter.getFeature()) == FilterFeature.VALIDATION_SEVERITY) {
                    filterApplied = true;
                    for (Map.Entry<String, Pair<AbstractValidation, ValidationAlertsSet>> entry : result.entrySet()) {
                        String k = entry.getKey();
                        Pair<AbstractValidation, ValidationAlertsSet> pair = entry.getValue();
                        String value = filter.getValue();
                        Set<ValidationSeverity> values = Arrays.stream(value.split(","))
                                .map(ValidationSeverity::valueOf).collect(Collectors.toSet());
                        if ((filter.getCondition() == FilterCondition.IN && values.contains(pair.getLeft().getSeverity()))
                            || (filter.getCondition() == FilterCondition.NOT_IN && !values.contains(pair.getLeft().getSeverity()))
                        ) {
                            filteredValidations.add(k);
                        }
                    }
                }
            }

            if (filterApplied) {
                result.keySet().retainAll(filteredValidations);
            }
        }
        return result;
    }

    @Transactional
    public Pair<AbstractValidation, ValidationAlertsSet> getValidationById(String validationId) {
        AbstractValidation validation = validations.get(validationId);
        if (validation == null) {
            throw new EntityNotFoundException("Can't find validation with id: " + validationId);
        }
        List<ValidationChainAlert> alerts = getAllChainAlertsByValidationId(validationId);
        long count = getAlertsCount(validationId);
        return Pair.of(validation, ValidationAlertsSet.builder().alertsCount(count).chainAlerts(alerts).build());
    }

    @Transactional
    public List<ValidationChainAlert> getAllChainAlertsByValidationId(String validationId) {
        return chainAlertRepository.findAllByValidationId(validationId);
    }

    @Transactional
    public long getAlertsCount(String validationId) {
        return chainAlertRepository.countAllByValidationId(validationId);
    }

    public long getAlertsCount(List<ValidationChainAlert> chainAlerts) {
        return chainAlerts == null ? 0 : chainAlerts.size();
    }

    // Only one scan task can be executed at a time
    public CompletableFuture<Void> runValidationsAsync(@Nullable Set<String> validationIds) throws DiagnosticValidationUnexpectedException {
        if (validationUpdateTryLock()) {
            return CompletableFuture.runAsync(() -> {
                try {
                    Set<String> filteredIds = validationIds == null || validationIds.isEmpty()
                            ? validations.keySet()
                            : validations.keySet().stream()
                                    .filter(validationIds::contains)
                                    .collect(Collectors.toSet());

                    Map<String, ValidationStatus> filteredValidations = new HashMap<>(filteredIds.size());
                    transactionHandler.runInNewTransaction(() -> {
                        for (String filteredId : filteredIds) {
                            ValidationStatus savedStatus = validationStatusRepository.save(
                                    ValidationStatus.builder()
                                            .validationId(filteredId)
                                            .startedWhen(Timestamp.valueOf(LocalDateTime.now()))
                                            .state(ValidationState.IN_PROGRESS).build());
                            filteredValidations.put(filteredId, savedStatus);
                        }
                    });

                    for (Map.Entry<String, ValidationStatus> entry : filteredValidations.entrySet()) {
                        AbstractValidation validation = validations.get(entry.getKey());
                        ValidationStatus state = entry.getValue();
                        try {
                            log.info("Diagnostic validation '{}' has started", validation.getTitle());

                            transactionHandler.runInNewTransaction(() -> {
                                // remove old alerts and save new
                                switch (validation.getEntityType()) {
                                    case CHAIN, CHAIN_ELEMENT -> {
                                        chainAlertRepository.deleteAllByValidationId(validation.getId());
                                        chainAlertRepository.saveAll((Collection<ValidationChainAlert>) validation.validate());
                                    }
                                }
                            });

                            state.setState(ValidationState.OK);
                            log.info("Diagnostic validation '{}' completed", validation.getTitle());
                        } catch (Exception e) {
                            log.error("Validation '{}' failed with an unexpected error", validation.getTitle(), e);
                            state.setState(ValidationState.FAILED, e.getMessage());
                        }
                        validationStatusRepository.save(state);
                    }
                    log.info("Diagnostic validations task completed");
                } catch (Exception e) {
                    log.error("Diagnostic validations task failed", e);
                } finally {
                    validationUpdateUnlock();
                }
            });
        } else {
            throw new ValidationAlreadyInProgressUnexpectedException("Validation(s) already in progress");
        }
    }

    public Map<String, ValidationStatus> getCurrentStatuses() {
        List<ValidationStatus> savedStates = validationStatusRepository.findAll();
        Map<String, ValidationStatus> result = savedStates.stream().collect(Collectors.toMap(ValidationStatus::getValidationId, Function.identity()));

        // if status not present in db - add state from a validation map with NOT_STARTED status
        for (Map.Entry<String, AbstractValidation> entry : validations.entrySet()) {
            if (!result.containsKey(entry.getKey())) {
                result.put(entry.getKey(),
                        ValidationStatus.builder()
                                .validationId(entry.getKey())
                                .state(ValidationState.NOT_STARTED)
                                .build());
            }
        }

        return result;
    }

    public ValidationStatus getCurrentStatus(String validationId) {
        return validationStatusRepository.findById(validationId).orElseGet(() -> ValidationStatus.builder()
                .validationId(validationId)
                .state(ValidationState.NOT_STARTED)
                .build());
    }

    public void initExternalValidations(Supplier<Collection<ExternalValidation>> externalValidationsSupplier) {
        if (validationUpdateTryLock()) {
            try {
                externalValidationsSupplier.get()
                        .forEach(externalValidation -> {
                            externalValidation.setEntityManager(this.entityManager);
                            validations.put(externalValidation.getId(), externalValidation);
                        });
            } finally {
                validationUpdateUnlock();
            }
        }
    }

    // TODO rewrite with db locks
    private boolean validationUpdateTryLock() {
        ConfigParameter lock = configParameterService.findByName(DIAGNOSTIC_NAMESPACE, DIAGNOSTIC_VALIDATION_STATE_UPDATE_LOCK_NAME);
        if (lock == null) {
            lock = new ConfigParameter(DIAGNOSTIC_NAMESPACE, DIAGNOSTIC_VALIDATION_STATE_UPDATE_LOCK_NAME);
            lock.setBoolean(true);
            configParameterService.update(lock);
            configParameterService.flush();
            return true;
        } else {
            if (!lock.getBoolean() || lock.getModifiedWhen().before(
                    Timestamp.valueOf(LocalDateTime.now().minusMinutes(VALIDATION_DB_LOCK_TIMEOUT_MINUTES)))) {
                lock.setBoolean(true);
                configParameterService.update(lock);
                configParameterService.flush();
                return true;
            }
        }
        return false;
    }

    private void validationUpdateUnlock() {
        ConfigParameter lock = configParameterService.findByName(DIAGNOSTIC_NAMESPACE, DIAGNOSTIC_VALIDATION_STATE_UPDATE_LOCK_NAME);
        lock.setBoolean(false);
        configParameterService.update(lock);
        configParameterService.flush();
    }
}
