package org.qubership.integration.platform.engine.mapper.atlasmap;

import io.atlasmap.api.AtlasSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.util.AtlasMapUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class TimestampGeneratorTest {

    private static final String CREATED_DATE_TIME_WITH_MILLIS = "2024-01-02T03:04:05.123+0000";

    private final Locale originalFormatLocale = Locale.getDefault(Locale.Category.FORMAT);
    private final TimeZone originalTimeZone = TimeZone.getDefault();

    @AfterEach
    void tearDown() {
        Locale.setDefault(Locale.Category.FORMAT, originalFormatLocale);
        TimeZone.setDefault(originalTimeZone);
    }

    @Test
    void shouldCreateFromEmptyParameterListAndApplyWithDefaults() {
        Locale.setDefault(Locale.Category.FORMAT, Locale.US);
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        TimestampGenerator generator = TimestampGenerator.fromParameterList(List.of());
        AtlasSession session = sessionWithCreatedDateTime();

        try (MockedStatic<AtlasMapUtils> atlasMapUtils = mockStatic(AtlasMapUtils.class)) {
            atlasMapUtils.when(() -> AtlasMapUtils.convertDateFormat(
                    false,
                    "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
                    "en_US",
                    "UTC",
                    false,
                    "",
                    "",
                    "",
                    CREATED_DATE_TIME_WITH_MILLIS
            )).thenReturn("converted");

            String result = generator.apply(session);

            assertEquals("converted", result);
        }
    }

    @Test
    void shouldCreateFromPartialParameterListAndApplyWithProvidedValues() {
        Locale.setDefault(Locale.Category.FORMAT, Locale.GERMANY);
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"));

        TimestampGenerator generator = TimestampGenerator.fromParameterList(List.of("true", "yyyyMMdd"));
        AtlasSession session = sessionWithCreatedDateTime();

        try (MockedStatic<AtlasMapUtils> atlasMapUtils = mockStatic(AtlasMapUtils.class)) {
            atlasMapUtils.when(() -> AtlasMapUtils.convertDateFormat(
                    false,
                    "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
                    "de_DE",
                    "Europe/Berlin",
                    true,
                    "yyyyMMdd",
                    "",
                    "",
                    CREATED_DATE_TIME_WITH_MILLIS
            )).thenReturn("partial");

            String result = generator.apply(session);

            assertEquals("partial", result);
        }
    }

    @Test
    void shouldCreateFromFullParameterListAndApplyWithAllValues() {
        Locale.setDefault(Locale.Category.FORMAT, Locale.FRANCE);
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"));

        TimestampGenerator generator = TimestampGenerator.fromParameterList(
                List.of("true", "dd.MM.yyyy HH:mm", "ru_RU", "Europe/Moscow")
        );
        AtlasSession session = sessionWithCreatedDateTime();

        try (MockedStatic<AtlasMapUtils> atlasMapUtils = mockStatic(AtlasMapUtils.class)) {
            atlasMapUtils.when(() -> AtlasMapUtils.convertDateFormat(
                    false,
                    "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
                    "fr_FR",
                    "Europe/Paris",
                    true,
                    "dd.MM.yyyy HH:mm",
                    "ru_RU",
                    "Europe/Moscow",
                    CREATED_DATE_TIME_WITH_MILLIS
            )).thenReturn("full");

            String result = generator.apply(session);

            assertEquals("full", result);
        }
    }

    @Test
    void shouldTreatInvalidBooleanParameterAsFalse() {
        Locale.setDefault(Locale.Category.FORMAT, Locale.US);
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));

        TimestampGenerator generator = TimestampGenerator.fromParameterList(
                List.of("notBoolean", "yyyy", "en_GB", "UTC")
        );
        AtlasSession session = sessionWithCreatedDateTime();

        try (MockedStatic<AtlasMapUtils> atlasMapUtils = mockStatic(AtlasMapUtils.class)) {
            atlasMapUtils.when(() -> AtlasMapUtils.convertDateFormat(
                    false,
                    "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
                    "en_US",
                    "UTC",
                    false,
                    "yyyy",
                    "en_GB",
                    "UTC",
                    CREATED_DATE_TIME_WITH_MILLIS
            )).thenReturn("invalid-boolean");

            String result = generator.apply(session);

            assertEquals("invalid-boolean", result);
        }
    }

    private static AtlasSession sessionWithCreatedDateTime() {
        AtlasSession session = mock(AtlasSession.class);
        Map<String, Object> sourceProperties = new HashMap<>();
        sourceProperties.put("Atlas.CreatedUTCDateTimeWithMillisTZ", CREATED_DATE_TIME_WITH_MILLIS);
        when(session.getSourceProperties()).thenReturn(sourceProperties);
        return session;
    }
}
