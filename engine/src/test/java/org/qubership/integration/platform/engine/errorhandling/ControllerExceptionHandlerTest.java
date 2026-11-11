package org.qubership.integration.platform.engine.errorhandling;

import jakarta.validation.Validation;
import org.hibernate.validator.HibernateValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.rest.v1.controller.LiveExchangesController;
import org.qubership.integration.platform.engine.service.LiveExchangesService;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;

import java.util.Locale;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ControllerExceptionHandlerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        // The controller is @Validated, so in production its constraints run through an AOP proxy.
        MethodValidationPostProcessor validationPostProcessor = new MethodValidationPostProcessor();
        // Hibernate Validator interpolates messages in the JVM's default locale, and the assertion
        // below matches the English one, so pin the locale rather than inherit the machine's.
        validationPostProcessor.setValidator(Validation.byProvider(HibernateValidator.class)
                .configure()
                .defaultLocale(Locale.ENGLISH)
                .buildValidatorFactory()
                .getValidator());
        validationPostProcessor.afterPropertiesSet();
        Object controller = validationPostProcessor.postProcessAfterInitialization(
                new LiveExchangesController(mock(LiveExchangesService.class)), "liveExchangesController");

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new ControllerExceptionHandler())
                .build();
    }

    @Test
    void answersConstraintViolationWithBadRequestAndNoStacktrace() throws Exception {
        mockMvc.perform(get("/v1/engine/live-exchanges").param("limit", "0").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorMessage", containsString("must be greater than 0")))
                .andExpect(jsonPath("$.stacktrace").value("No Stacktrace Available"));
    }
}
