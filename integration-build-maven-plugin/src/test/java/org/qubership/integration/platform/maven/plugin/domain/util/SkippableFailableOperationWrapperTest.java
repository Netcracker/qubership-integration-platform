package org.qubership.integration.platform.maven.plugin.domain.util;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import org.apache.commons.lang3.function.FailableConsumer;
import org.apache.commons.lang3.function.FailableFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkippableFailableOperationWrapperTest {
    private static final IOException FAILURE = new IOException("broken input");

    private final Logger logger = (Logger) LoggerFactory.getLogger(SkippableFailableOperationWrapper.class);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
    }

    @Test
    void functionReturnsTheResultAndCountsNothingOnSuccess() throws IOException {
        SkippableFailableOperationWrapper wrapper = new SkippableFailableOperationWrapper(false);

        assertEquals(4, wrapper.<String, Integer, IOException>wrapFunction(String::length).apply("four"));
        assertEquals(0, wrapper.getErrorCount());
        assertTrue(logs.list.isEmpty());
    }

    @Test
    void functionRethrowsTheFailureWhenFailingFast() {
        SkippableFailableOperationWrapper wrapper = new SkippableFailableOperationWrapper(true);
        FailableFunction<String, Integer, IOException> function = wrapper.wrapFunction(this::fail);

        IOException exception = assertThrows(IOException.class, () -> function.apply("input"));

        assertSame(FAILURE, exception);
        assertEquals(1, wrapper.getErrorCount());
        assertTrue(logs.list.isEmpty(), "Maven reports a rethrown failure; logging it here would print it twice");
    }

    @Test
    void functionLogsTheFailureAndReturnsNullWhenSkipping() throws IOException {
        SkippableFailableOperationWrapper wrapper = new SkippableFailableOperationWrapper(false);

        assertNull(wrapper.wrapFunction(this::fail).apply("input"));

        assertEquals(1, wrapper.getErrorCount());
        assertLoggedOnce();
    }

    @Test
    void consumerAcceptsTheArgumentAndCountsNothingOnSuccess() throws IOException {
        SkippableFailableOperationWrapper wrapper = new SkippableFailableOperationWrapper(false);
        List<String> accepted = new ArrayList<>();

        wrapper.<String, IOException>wrapConsumer(accepted::add).accept("input");

        assertEquals(List.of("input"), accepted);
        assertEquals(0, wrapper.getErrorCount());
        assertTrue(logs.list.isEmpty());
    }

    @Test
    void consumerRethrowsTheFailureWhenFailingFast() {
        SkippableFailableOperationWrapper wrapper = new SkippableFailableOperationWrapper(true);
        FailableConsumer<String, IOException> consumer = wrapper.wrapConsumer(this::reject);

        IOException exception = assertThrows(IOException.class, () -> consumer.accept("input"));

        assertSame(FAILURE, exception);
        assertEquals(1, wrapper.getErrorCount());
        assertTrue(logs.list.isEmpty(), "Maven reports a rethrown failure; logging it here would print it twice");
    }

    @Test
    void consumerLogsTheFailureWhenSkipping() throws IOException {
        SkippableFailableOperationWrapper wrapper = new SkippableFailableOperationWrapper(false);

        wrapper.wrapConsumer(this::reject).accept("input");

        assertEquals(1, wrapper.getErrorCount());
        assertLoggedOnce();
    }

    /** The goals report one total at the end, so every skipped failure has to reach the same counter. */
    @Test
    void countsFailuresAcrossEveryWrappedOperation() throws IOException {
        SkippableFailableOperationWrapper wrapper = new SkippableFailableOperationWrapper(false);
        FailableFunction<String, Integer, IOException> function = wrapper.wrapFunction(this::fail);
        FailableConsumer<String, IOException> consumer = wrapper.wrapConsumer(this::reject);

        function.apply("first");
        function.apply("second");
        consumer.accept("third");

        assertEquals(3, wrapper.getErrorCount());
        assertEquals(3, logs.list.size());
    }

    private Integer fail(String input) throws IOException {
        throw FAILURE;
    }

    private void reject(String input) throws IOException {
        throw FAILURE;
    }

    private void assertLoggedOnce() {
        assertEquals(1, logs.list.size());
        ILoggingEvent event = logs.list.getFirst();
        assertEquals(Level.ERROR, event.getLevel());
        assertEquals("broken input", event.getFormattedMessage());
        assertSame(FAILURE, ((ThrowableProxy) event.getThrowableProxy()).getThrowable());
    }
}
