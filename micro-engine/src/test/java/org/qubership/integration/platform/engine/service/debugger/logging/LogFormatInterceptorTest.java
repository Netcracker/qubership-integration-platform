package org.qubership.integration.platform.engine.service.debugger.logging;

import io.smallrye.config.ConfigSourceInterceptorContext;
import io.smallrye.config.ConfigValue;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class LogFormatInterceptorTest {

    private static final String JSON_ENABLED = "quarkus.log.json.console.enabled";
    private static final String FORMAT = "cip.logging.format";

    private final LogFormatInterceptor interceptor = new LogFormatInterceptor();
    private final ConfigSourceInterceptorContext context = mock(ConfigSourceInterceptorContext.class);

    @ParameterizedTest
    @CsvSource({"json, true", "JSON, true", "text, false"})
    void shouldDeriveJsonConsoleEnabledFromLoggingFormat(String format, String expected) {
        when(context.proceed(FORMAT)).thenReturn(value(FORMAT, format));

        ConfigValue result = interceptor.getValue(context, JSON_ENABLED);

        assertEquals(JSON_ENABLED, result.getName());
        assertEquals(expected, result.getValue());
    }

    @Test
    void shouldEnableJsonConsoleWhenLoggingFormatIsNotSet() {
        assertEquals("true", interceptor.getValue(context, JSON_ENABLED).getValue());
    }

    @Test
    void shouldPassOtherPropertiesThrough() {
        ConfigValue original = value("some.property", "x");
        when(context.proceed("some.property")).thenReturn(original);

        assertSame(original, interceptor.getValue(context, "some.property"));
    }

    private static ConfigValue value(String name, String value) {
        return ConfigValue.builder().withName(name).withValue(value).build();
    }
}
