package org.qubership.integration.platform.engine.service;

import org.apache.camel.Exchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ServiceVariableUtilsTest {

    @Mock
    Exchange exchange;

    private ServiceVariableUtils utils;

    @BeforeEach
    void setUp() throws Exception {
        utils = newUtilsWithServiceVariableNames(List.of("svcA", "svcB"));
    }

    @Test
    void shouldRemoveServiceVariablesFromExchangePropertiesWhenGetCustomProperties() {
        Map<String, Object> props = new HashMap<>();
        props.put("svcA", 1);
        props.put("svcB", 2);
        props.put("custom1", "x");
        props.put("custom2", 123);

        when(exchange.getProperties()).thenReturn(props);

        Map<String, Object> result = utils.getCustomProperties(exchange);

        assertEquals(Map.of("custom1", "x", "custom2", 123), result);
    }

    @Test
    void shouldNotMutateOriginalExchangePropertiesWhenGetCustomProperties() {
        Map<String, Object> props = new HashMap<>();
        props.put("svcA", 1);
        props.put("custom", "x");

        when(exchange.getProperties()).thenReturn(props);

        Map<String, Object> result = utils.getCustomProperties(exchange);

        assertNotSame(props, result);
        assertEquals(2, props.size());
        assertTrue(props.containsKey("svcA"));
        assertTrue(props.containsKey("custom"));
        assertEquals(Map.of("custom", "x"), result);
    }

    @Test
    void shouldReturnAllPropertiesWhenNoServiceVariablesPresent() {
        Map<String, Object> props = new HashMap<>();
        props.put("custom1", "x");
        props.put("custom2", 123);

        when(exchange.getProperties()).thenReturn(props);

        Map<String, Object> result = utils.getCustomProperties(exchange);

        assertEquals(props, result);
    }

    @Test
    void shouldHandleEmptyPropertiesWhenGetCustomProperties() {
        when(exchange.getProperties()).thenReturn(new HashMap<>());

        Map<String, Object> result = utils.getCustomProperties(exchange);

        assertTrue(result.isEmpty());
    }

    private ServiceVariableUtils newUtilsWithServiceVariableNames(List<String> names) throws Exception {
        Constructor<ServiceVariableUtils> ctor = ServiceVariableUtils.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        ServiceVariableUtils instance = ctor.newInstance();

        Field field = ServiceVariableUtils.class.getDeclaredField("serviceVariablesName");
        field.setAccessible(true);
        field.set(instance, new ArrayList<>(names));

        return instance;
    }
}
