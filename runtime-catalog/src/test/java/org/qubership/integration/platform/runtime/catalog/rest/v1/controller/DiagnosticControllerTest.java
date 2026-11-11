package org.qubership.integration.platform.runtime.catalog.rest.v1.controller;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.model.diagnostic.ValidationAlertsSet;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.diagnostic.ValidationChainAlert;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.diagnostic.ValidationState;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.diagnostic.ValidationStatus;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.diagnostic.DiagnosticValidationDTO;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DiagnosticValidationMapperImpl;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.DiagnosticService;
import org.qubership.integration.platform.runtime.catalog.service.diagnostic.validations.AbstractValidation;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * A validation run commits its new alerts and then saves {@code OK}. Each case lets that run
 * finish right after the controller's first read. In that interleaving the controller once
 * answered {@code OK} with the alerts of the previous run (#1140).
 */
@ExtendWith(MockitoExtension.class)
class DiagnosticControllerTest {

    private static final String VALIDATION_ID = "built-in_scripting-found-in-chain";

    @Mock
    private DiagnosticService diagnosticService;

    @Mock
    private AbstractValidation validation;

    private DiagnosticController controller;

    private final ValidationAlertsSet alerts = ValidationAlertsSet.builder()
            .alertsCount(1)
            .chainAlerts(List.of(new ValidationChainAlert()))
            .build();

    private boolean runFinished;

    @BeforeEach
    void setUp() {
        controller = new DiagnosticController(diagnosticService, new DiagnosticValidationMapperImpl());
    }

    @Test
    @DisplayName("GET /v1/catalog/diagnostic/validations/{id} does not answer OK with alerts read before the run committed")
    void getByIdReadsStatusBeforeAlerts() {
        when(diagnosticService.getCurrentStatus(VALIDATION_ID))
                .thenAnswer(invocation -> finishRunAfter(status()));
        when(diagnosticService.getValidationById(VALIDATION_ID))
                .thenAnswer(invocation -> finishRunAfter(Pair.of(validation, alerts)));

        DiagnosticValidationDTO dto = controller.getValidationById(VALIDATION_ID).getBody();

        assertThat(dto.getStatus().getState()).isEqualTo(ValidationState.IN_PROGRESS);
        assertThat(dto.getAlertsCount()).isZero();
        assertThat(dto.getChainEntities()).isEmpty();
    }

    @Test
    @DisplayName("POST /v1/catalog/diagnostic/validations does not answer OK with alert counts read before the run committed")
    void searchReadsStatusesBeforeAlerts() {
        when(validation.getId()).thenReturn(VALIDATION_ID);
        when(diagnosticService.getCurrentStatuses())
                .thenAnswer(invocation -> finishRunAfter(Map.of(VALIDATION_ID, status())));
        when(diagnosticService.getFilteredValidations(null))
                .thenAnswer(invocation -> finishRunAfter(List.of(Pair.of(validation, alerts))));

        List<DiagnosticValidationDTO> result = controller.searchValidations(null).getBody();

        assertThat(result).singleElement().satisfies(dto -> {
            assertThat(dto.getStatus().getState()).isEqualTo(ValidationState.IN_PROGRESS);
            assertThat(dto.getAlertsCount()).isZero();
        });
    }

    private <T> T finishRunAfter(T read) {
        runFinished = true;
        return read;
    }

    private ValidationStatus status() {
        return ValidationStatus.builder()
                .state(runFinished ? ValidationState.OK : ValidationState.IN_PROGRESS)
                .build();
    }
}
