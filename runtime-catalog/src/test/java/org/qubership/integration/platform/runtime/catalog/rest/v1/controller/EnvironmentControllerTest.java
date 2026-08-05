package org.qubership.integration.platform.runtime.catalog.rest.v1.controller;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.BadRequestException;
import org.qubership.integration.platform.runtime.catalog.model.dto.system.EnvironmentDTO;
import org.qubership.integration.platform.runtime.catalog.model.dto.system.EnvironmentRequestDTO;
import org.qubership.integration.platform.runtime.catalog.model.mapper.mapping.EnvironmentMapper;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.service.EnvironmentService;
import org.qubership.integration.platform.runtime.catalog.service.SystemService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EnvironmentControllerTest {

    private static final String SYSTEM_ID = "system-1";
    private static final String SYSTEM_NAME = "Test service";
    private static final String ENVIRONMENT_ID = "environment-1";

    @Mock EnvironmentService environmentService;
    @Mock EnvironmentMapper environmentMapper;
    @Mock SystemService systemService;

    private EnvironmentController controller;

    @BeforeEach
    void setUp() {
        controller = new EnvironmentController(environmentService, environmentMapper, systemService);
    }

    @Test
    @DisplayName("a second environment is rejected for an internal service")
    void secondEnvironmentIsRejectedForInternalService() {
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(systemWith(IntegrationSystemType.INTERNAL, 1));

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> controller.createEnvironment(SYSTEM_ID, new EnvironmentRequestDTO()));

        assertThat(exception.getMessage(), containsString(SYSTEM_ID));
        assertThat(exception.getMessage(), containsString("internal"));
        verify(environmentService, never()).create(any(), any());
    }

    @Test
    @DisplayName("a second environment is rejected for an implemented service")
    void secondEnvironmentIsRejectedForImplementedService() {
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(systemWith(IntegrationSystemType.IMPLEMENTED, 1));

        BadRequestException exception = assertThrows(BadRequestException.class,
                () -> controller.createEnvironment(SYSTEM_ID, new EnvironmentRequestDTO()));

        assertThat(exception.getMessage(), containsString("implemented"));
        verify(environmentService, never()).create(any(), any());
    }

    @Test
    @DisplayName("a second environment is accepted for an external service")
    void secondEnvironmentIsAcceptedForExternalService() {
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(systemWith(IntegrationSystemType.EXTERNAL, 1));
        Environment created = acceptEnvironmentCreation();

        ResponseEntity<EnvironmentDTO> response = controller.createEnvironment(SYSTEM_ID, new EnvironmentRequestDTO());

        assertThat(response.getStatusCode(), equalTo(HttpStatus.CREATED));
        verify(environmentMapper).toDTO(created);
    }

    @Test
    @DisplayName("the first environment is accepted for an internal service")
    void firstEnvironmentIsAcceptedForInternalService() {
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(systemWith(IntegrationSystemType.INTERNAL, 0));
        acceptEnvironmentCreation();

        ResponseEntity<EnvironmentDTO> response = controller.createEnvironment(SYSTEM_ID, new EnvironmentRequestDTO());

        assertThat(response.getStatusCode(), equalTo(HttpStatus.CREATED));
    }

    @Test
    @DisplayName("a service row with no type does not crash the guard")
    void typelessServiceDoesNotCrashTheGuard() {
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(systemWith(null, 3));
        acceptEnvironmentCreation();

        ResponseEntity<EnvironmentDTO> response = controller.createEnvironment(SYSTEM_ID, new EnvironmentRequestDTO());

        assertThat(response.getStatusCode(), equalTo(HttpStatus.CREATED));
    }

    @Test
    @DisplayName("updating an unknown environment id on a full internal service is rejected instead of creating one")
    void updateWithUnknownIdOnFullServiceIsRejected() {
        when(environmentService.getByIdForSystemOrElseNull(SYSTEM_ID, ENVIRONMENT_ID)).thenReturn(null);
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(systemWith(IntegrationSystemType.INTERNAL, 1));

        assertThrows(BadRequestException.class,
                () -> controller.updateEnvironment(SYSTEM_ID, ENVIRONMENT_ID, new EnvironmentRequestDTO()));

        verify(environmentService, never()).create(any(), any());
    }

    @Test
    @DisplayName("updating an existing environment of a full internal service is not affected by the limit")
    void updateOfExistingEnvironmentIgnoresTheLimit() {
        Environment existing = systemWith(IntegrationSystemType.INTERNAL, 1).getEnvironments().get(0);
        when(environmentService.getByIdForSystemOrElseNull(SYSTEM_ID, ENVIRONMENT_ID)).thenReturn(existing);
        when(environmentService.update(existing)).thenReturn(existing);

        ResponseEntity<EnvironmentDTO> response =
                controller.updateEnvironment(SYSTEM_ID, ENVIRONMENT_ID, new EnvironmentRequestDTO());

        assertThat(response.getStatusCode(), equalTo(HttpStatus.OK));
        verify(environmentService).update(existing);
        verify(systemService, never()).getByIdOrNull(any());
    }

    /** The unknown id used to reach a dereference and answer 500; PUT /v1/systems/{id} reports it as 404. */
    @Test
    @DisplayName("creating an environment for an unknown service id is reported, not dereferenced")
    void unknownServiceIdIsReported() {
        when(systemService.getByIdOrNull(SYSTEM_ID)).thenReturn(null);

        EntityNotFoundException exception = assertThrows(EntityNotFoundException.class,
                () -> controller.createEnvironment(SYSTEM_ID, new EnvironmentRequestDTO()));

        assertThat(exception.getMessage(), containsString(SYSTEM_ID));
        verify(environmentService, never()).create(any(), any());
    }

    private Environment acceptEnvironmentCreation() {
        Environment created = new Environment();
        when(environmentService.create(any(), any())).thenReturn(created);
        return created;
    }

    private static IntegrationSystem systemWith(IntegrationSystemType type, int environmentCount) {
        List<Environment> environments = new ArrayList<>();
        for (int i = 0; i < environmentCount; i++) {
            Environment environment = new Environment();
            environment.setId("environment-" + (i + 1));
            environments.add(environment);
        }
        return IntegrationSystem.builder()
                .id(SYSTEM_ID)
                .name(SYSTEM_NAME)
                .integrationSystemType(type)
                .environments(environments)
                .build();
    }
}
