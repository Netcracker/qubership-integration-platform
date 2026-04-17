package org.qubership.integration.platform.engine.configuration.opensearch;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.qubership.integration.platform.engine.opensearch.ism.model.time.TimeValue;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class StringToTimeValueConverterTest {

    private final StringToTimeValueConverter converter = new StringToTimeValueConverter();

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    void shouldReturnNullWhenValueIsBlank(String value) {
        assertNull(converter.convert(value));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1d", "15m", "300ms"})
    void shouldConvertStringToTimeValueWhenValueIsNotBlank(String value) {
        assertEquals(TimeValue.parseTimeValue(value, ""), converter.convert(value));
    }
}
