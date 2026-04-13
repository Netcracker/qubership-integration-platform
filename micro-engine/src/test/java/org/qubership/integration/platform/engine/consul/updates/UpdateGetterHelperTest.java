package org.qubership.integration.platform.engine.consul.updates;

import io.smallrye.mutiny.Uni;
import io.vertx.ext.consul.BlockingQueryOptions;
import io.vertx.ext.consul.KeyValue;
import io.vertx.ext.consul.KeyValueList;
import io.vertx.mutiny.ext.consul.ConsulClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.consul.KVNotFoundException;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class UpdateGetterHelperTest {

    private static final String KEY = "config/test-env/runtime";

    private UpdateGetterHelper<String> helper;

    @Mock
    ConsulClient consulClient;
    @Mock
    Function<List<KeyValue>, String> valueParser;
    @Mock
    Consumer<String> consumer;

    @BeforeEach
    void setUp() {
        helper = new UpdateGetterHelper<>(KEY, () -> consulClient, valueParser);
    }

    @Test
    void shouldNotInvokeParserOrConsumerWhenConsulIndexHasNotChanged() {
        KeyValueList kvList = kvListWithIndex(0L);

        when(consulClient.getValuesWithOptions(eq(KEY), any(BlockingQueryOptions.class)))
                .thenReturn(Uni.createFrom().item(kvList));

        helper.checkForUpdates(consumer);

        verifyNoInteractions(valueParser);
        verifyNoInteractions(consumer);
    }

    @Test
    void shouldParseValueAndPassItToConsumerWhenConsulIndexChanged() {
        List<KeyValue> entries = List.of(mock(KeyValue.class));
        KeyValueList kvList = kvListWithIndexAndEntries(5L, entries);

        when(consulClient.getValuesWithOptions(eq(KEY), any(BlockingQueryOptions.class)))
                .thenReturn(Uni.createFrom().item(kvList));
        when(valueParser.apply(entries)).thenReturn("parsed-value");

        helper.checkForUpdates(consumer);

        verify(valueParser).apply(entries);
        verify(consumer).accept("parsed-value");
    }

    @Test
    void shouldThrowKvNotFoundExceptionWhenKeyValueListNotPresent() {
        KeyValueList kvList = notPresentKvList();

        when(consulClient.getValuesWithOptions(eq(KEY), any(BlockingQueryOptions.class)))
                .thenReturn(Uni.createFrom().item(kvList));

        KVNotFoundException exception = assertThrows(
                KVNotFoundException.class,
                () -> helper.checkForUpdates(consumer)
        );

        assertEquals("Key not present in consul", exception.getMessage());
        verifyNoInteractions(valueParser);
        verifyNoInteractions(consumer);
    }

    @Test
    void shouldWrapParserExceptionAndRollbackIndexWhenParsingFails() {
        List<KeyValue> firstEntries = List.of(mock(KeyValue.class));
        List<KeyValue> failedEntries = List.of();

        KeyValueList firstKvList = kvListWithIndexAndEntries(2L, firstEntries);
        KeyValueList secondKvList = kvListWithIndexAndEntries(5L, failedEntries);
        KeyValueList thirdKvList = kvListWithIndexAndEntries(5L, failedEntries);

        when(consulClient.getValuesWithOptions(eq(KEY), any(BlockingQueryOptions.class)))
                .thenReturn(
                        Uni.createFrom().item(firstKvList),
                        Uni.createFrom().item(secondKvList),
                        Uni.createFrom().item(thirdKvList)
                );

        when(valueParser.apply(firstEntries)).thenReturn("first-value");
        IllegalArgumentException cause = new IllegalArgumentException("Invalid value format");
        when(valueParser.apply(failedEntries))
                .thenThrow(cause)
                .thenReturn("recovered-value");

        helper.checkForUpdates(consumer);

        RuntimeException exception = assertThrows(
                RuntimeException.class,
                () -> helper.checkForUpdates(consumer)
        );

        helper.checkForUpdates(consumer);

        assertEquals(
                "Failed to parse response, target key in consul has invalid value format or value count: []",
                exception.getMessage()
        );
        assertSame(cause, exception.getCause());
        verify(consumer).accept("first-value");
        verify(consumer).accept("recovered-value");

        ArgumentCaptor<BlockingQueryOptions> optionsCaptor =
                ArgumentCaptor.forClass(BlockingQueryOptions.class);

        verify(consulClient, times(3))
                .getValuesWithOptions(eq(KEY), optionsCaptor.capture());

        List<BlockingQueryOptions> options = optionsCaptor.getAllValues();
        assertEquals(0L, options.get(0).getIndex());
        assertEquals(2L, options.get(1).getIndex());
        assertEquals(0L, options.get(2).getIndex());
    }

    private static KeyValueList notPresentKvList() {
        KeyValueList kvList = mock(KeyValueList.class);
        when(kvList.isPresent()).thenReturn(false);
        return kvList;
    }

    private static KeyValueList kvListWithIndex(long index) {
        KeyValueList kvList = mock(KeyValueList.class);
        when(kvList.isPresent()).thenReturn(true);
        when(kvList.getIndex()).thenReturn(index);
        return kvList;
    }

    private static KeyValueList kvListWithIndexAndEntries(long index, List<KeyValue> entries) {
        KeyValueList kvList = mock(KeyValueList.class);
        when(kvList.isPresent()).thenReturn(true);
        when(kvList.getIndex()).thenReturn(index);
        when(kvList.getList()).thenReturn(entries);
        return kvList;
    }
}
