package org.qubership.integration.platform.engine.configuration.opensearch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.opensearch.ism.model.FailedIndex;
import org.qubership.integration.platform.engine.opensearch.ism.model.time.TimeValue;
import org.qubership.integration.platform.engine.opensearch.ism.rest.ISMStatusResponse;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.OpenSearchTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class OpenSearchInitializerHelpersTest {

    private final OpenSearchInitializer initializer = new OpenSearchInitializer();

    @Mock
    private OpenSearchProperties properties;

    @Mock
    private OpenSearchProperties.IndexProperties indexProperties;

    @Mock
    private OpenSearchProperties.ElementsProperties elementsProperties;

    @Mock
    private OpenSearchProperties.RolloverProperties rolloverProperties;

    @BeforeEach
    void setUp() {
        initializer.properties = properties;
    }

    @Test
    void shouldReturnNullWhenOldIndexAgePropertiesAreNotConfigured() throws Exception {
        when(properties.rollover()).thenReturn(rolloverProperties);
        when(rolloverProperties.minIndexAge()).thenReturn(null);
        when(rolloverProperties.minRolloverAgeToDelete()).thenReturn(null);

        TimeValue result = OpenSearchTestUtils.invoke(
                initializer,
                "calculateOldIndexMinAge",
                new Class<?>[]{Instant.class},
                Instant.ofEpochMilli(1_000L)
        );

        assertNull(result);
    }

    @Test
    void shouldCalculateOldIndexMinAgeFromCreationTimestampAndConfiguredThresholds() throws Exception {
        TimeValue minIndexAge = TimeValue.timeValueDays(1);
        TimeValue minDeleteAge = TimeValue.timeValueDays(14);
        Instant creationTimestamp = Instant.ofEpochMilli(1_000L);
        Instant before = Instant.now();

        when(properties.rollover()).thenReturn(rolloverProperties);
        when(rolloverProperties.minIndexAge()).thenReturn(minIndexAge);
        when(rolloverProperties.minRolloverAgeToDelete()).thenReturn(minDeleteAge);

        TimeValue result = OpenSearchTestUtils.invoke(
                initializer,
                "calculateOldIndexMinAge",
                new Class<?>[]{Instant.class},
                creationTimestamp
        );

        Instant after = Instant.now();

        long lowerBound = before.toEpochMilli() - creationTimestamp.toEpochMilli()
                + minIndexAge.millis()
                + minDeleteAge.millis();
        long upperBound = after.toEpochMilli() - creationTimestamp.toEpochMilli()
                + minIndexAge.millis()
                + minDeleteAge.millis();

        assertTrue(result.millis() >= lowerBound);
        assertTrue(result.millis() <= upperBound);
    }

    @Test
    void shouldNotThrowWhenIsmStatusResponseHasNoFailures() {
        ISMStatusResponse response = ISMStatusResponse.builder()
                .failures(false)
                .build();

        assertDoesNotThrow(() -> OpenSearchTestUtils.invokeVoid(
                initializer,
                "handleISMStatusResponse",
                new Class<?>[]{ISMStatusResponse.class},
                response
        ));
    }

    @Test
    void shouldThrowExceptionWithConcatenatedReasonsWhenIsmStatusResponseHasFailures() {
        ISMStatusResponse response = ISMStatusResponse.builder()
                .failures(true)
                .failedIndices(List.of(
                        FailedIndex.builder().reason("first reason").build(),
                        FailedIndex.builder().reason(null).build(),
                        FailedIndex.builder().reason("second reason").build()
                ))
                .build();

        Exception exception = assertThrows(
                Exception.class,
                () -> OpenSearchTestUtils.invokeVoid(
                        initializer,
                        "handleISMStatusResponse",
                        new Class<?>[]{ISMStatusResponse.class},
                        response
                )
        );

        assertEquals("first reason second reason", exception.getMessage());
    }

    @Test
    void shouldThrowExceptionWithUnspecifiedErrorWhenIsmStatusResponseHasFailuresWithoutFailedIndices() {
        ISMStatusResponse response = ISMStatusResponse.builder()
                .failures(true)
                .failedIndices(null)
                .build();

        Exception exception = assertThrows(
                Exception.class,
                () -> OpenSearchTestUtils.invokeVoid(
                        initializer,
                        "handleISMStatusResponse",
                        new Class<?>[]{ISMStatusResponse.class},
                        response
                )
        );

        assertEquals("Unspecified error", exception.getMessage());
    }

    @Test
    void shouldReturnIndexSettingsWithConfiguredShardCountAndAlias() throws Exception {
        when(properties.index()).thenReturn(indexProperties);
        when(indexProperties.elements()).thenReturn(elementsProperties);
        when(elementsProperties.shards()).thenReturn(3);

        Map<String, Object> result = OpenSearchTestUtils.invoke(
                initializer,
                "getIndexSettings",
                new Class<?>[]{String.class},
                "sessions"
        );

        assertEquals(3, result.get("index.number_of_shards"));
        assertEquals("sessions-session-elements", result.get("plugins.index_state_management.rollover_alias"));
    }

    @ParameterizedTest
    @CsvSource({
            "getOldIndexRolloverPolicyId, sessions, sessions-old-index-rollover-policy",
            "getRolloverPolicyId, sessions, sessions-rollover-policy",
            "getFirstRolloverIndexName, sessions, sessions-000001",
            "getIndexNameMask, sessions, sessions-*",
            "getOldIndexNameMask, sessions, sessions",
            "getOldIndexName, sessions, sessions",
            "getIndexTemplateName, sessions, sessions_template",
            "getAliasName, sessions, sessions-session-elements"
    })
    void shouldBuildNamesFromPrefix(String methodName, String prefix, String expected) throws Exception {
        String result = OpenSearchTestUtils.invoke(
                initializer,
                methodName,
                new Class<?>[]{String.class},
                prefix
        );

        assertEquals(expected, result);
    }

    @Test
    void shouldReturnIndexPatterns() throws Exception {
        List<String> result = OpenSearchTestUtils.invoke(
                initializer,
                "getIndexPatterns",
                new Class<?>[]{String.class},
                "sessions"
        );

        assertEquals(List.of("sessions", "sessions-*"), result);
    }
}
