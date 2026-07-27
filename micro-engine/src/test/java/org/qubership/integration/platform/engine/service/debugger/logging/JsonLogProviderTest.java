package org.qubership.integration.platform.engine.service.debugger.logging;

import io.quarkiverse.loggingjson.JsonGenerator;
import io.quarkiverse.loggingjson.providers.KeyValueStructuredArgument;
import io.quarkus.test.InjectMock;
import io.quarkus.test.component.QuarkusComponentTest;
import jakarta.inject.Inject;
import org.jboss.logmanager.ExtLogRecord;
import org.jboss.logmanager.Level;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@QuarkusComponentTest
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class JsonLogProviderTest {

    @Inject
    JsonLogProvider jsonLogProvider;

    @InjectMock
    JsonGenerator jsonGenerator;

    @Test
    void shouldWriteBasicLogFieldsWhenRecordHasNoMdcOrParametersOrException() throws Exception {
        ExtLogRecord logRecord = new ExtLogRecord(Level.INFO, "Test message", "test.Class");
        logRecord.setSourceMethodName("testMethod");
        logRecord.setThreadName("test-thread");
        logRecord.setInstant(Instant.parse("2023-01-01T10:00:00Z"));

        jsonLogProvider.writeTo(jsonGenerator, logRecord);

        verify(jsonGenerator).writeStringField("time", "2023-01-01T10:00:00Z");
        verify(jsonGenerator).writeStringField("level", "INFO");
        verify(jsonGenerator).writeStringField("thread", "test-thread");
        verify(jsonGenerator).writeStringField("class", "test.Class");
        verify(jsonGenerator).writeStringField("method", "testMethod");
        verify(jsonGenerator).writeStringField("message", "Test message");
        verifyNoMoreInteractions(jsonGenerator);
    }

    @Test
    void shouldWriteMappedMdcFieldsWhenRecordContainsMdcValues() throws Exception {
        ExtLogRecord logRecord = new ExtLogRecord(Level.INFO, "Test message", "test.Class");
        logRecord.setLevel(Level.INFO);
        logRecord.setThreadName("test-thread");
        logRecord.setInstant(Instant.parse("2023-01-01T10:00:00Z"));
        logRecord.setMdc(Map.of(
                "requestId", "req-123",
                "X-B3-TraceId", "trace-456",
                "chainId", "chain-789",
                "unknownKey", "should-be-ignored"));

        jsonLogProvider.writeTo(jsonGenerator, logRecord);

        verify(jsonGenerator).writeStringField("request_id", "req-123");
        verify(jsonGenerator).writeStringField("traceId", "trace-456");
        verify(jsonGenerator).writeStringField("chain_id", "chain-789");
        verify(jsonGenerator, never()).writeStringField(eq("unknownKey"), anyString());
    }

    @Test
    void shouldWriteStructuredArgumentsWhenRecordContainsKeyValueArguments() throws Exception {
        ExtLogRecord logRecord = new ExtLogRecord(Level.INFO, "Test message", "test.Class");
        logRecord.setThreadName("test-thread");
        logRecord.setInstant(Instant.parse("2023-01-01T10:00:00Z"));

        KeyValueStructuredArgument structuredArg = mock(KeyValueStructuredArgument.class);
        logRecord.setParameters(new Object[] { structuredArg });

        jsonLogProvider.writeTo(jsonGenerator, logRecord);

        verify(structuredArg).writeTo(jsonGenerator);
    }

    @Test
    void shouldWriteStackTraceWhenRecordContainsThrowable() throws Exception {
        ExtLogRecord logRecord = new ExtLogRecord(Level.ERROR, "Error occurred", "test.Class");
        logRecord.setThreadName("test-thread");
        logRecord.setInstant(Instant.parse("2023-01-01T10:00:00Z"));

        RuntimeException exception = new RuntimeException("Test exception");
        exception.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("test.Class", "testMethod", "Class.java", 10)
        });
        logRecord.setThrown(exception);

        jsonLogProvider.writeTo(jsonGenerator, logRecord);

        verify(jsonGenerator).writeStringField(eq("stacktrace"), contains("Test exception"));
        verify(jsonGenerator).writeStringField(eq("stacktrace"), contains("test.Class.testMethod"));
    }

    @Test
    void shouldNotWriteMethodFieldWhenSourceMethodNameIsNull() throws Exception {
        ExtLogRecord logRecord = new ExtLogRecord(Level.INFO, "Test message", "test.Class");
        logRecord.setThreadName("test-thread");
        logRecord.setInstant(Instant.parse("2023-01-01T10:00:00Z"));
        logRecord.setSourceMethodName(null);

        jsonLogProvider.writeTo(jsonGenerator, logRecord);

        verify(jsonGenerator, never()).writeStringField(eq("method"), anyString());
    }
}
