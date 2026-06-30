package org.qubership.integration.platform.engine.logging;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.qubership.integration.platform.engine.errorhandling.errorcode.ErrorCode;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ExtendedErrorLoggerTest {

    private static final String LOGGER_NAME = "my.logger";

    @Test
    void shouldReturnDelegateNameFromGetName() {
        Logger delegate = mock(Logger.class);

        when(delegate.getName()).thenReturn("delegate.name");

        ExtendedErrorLogger log = loggerWithDelegate(LOGGER_NAME, delegate);

        assertEquals("delegate.name", log.getName());
    }

    @Test
    void shouldUseClassNameWhenConstructedWithClass() {
        Logger delegate = mock(Logger.class);

        try (MockedStatic<LoggerFactory> loggerFactory = Mockito.mockStatic(LoggerFactory.class)) {
            loggerFactory.when(() -> LoggerFactory.getLogger(String.class.getName())).thenReturn(delegate);

            ExtendedErrorLogger log = new ExtendedErrorLogger(String.class);

            log.error("hi");

            verify(delegate).error("hi");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"trace", "debug", "info", "warn", "error"})
    void shouldDelegatePlainLogMethods(String level) {
        Logger delegate = mock(Logger.class);
        Marker marker = mock(Marker.class);
        RuntimeException exception = new RuntimeException(level);
        Object[] varArgs = {"arg1", "arg2", "arg3"};

        when((Boolean) invoke(delegate, enabledMethod(level))).thenReturn(true);
        when((Boolean) invoke(delegate, enabledMethod(level), new Class<?>[]{Marker.class}, marker)).thenReturn(false);

        ExtendedErrorLogger log = loggerWithDelegate(LOGGER_NAME, delegate);

        assertTrue((Boolean) invoke(log, enabledMethod(level)));
        assertFalse((Boolean) invoke(log, enabledMethod(level), new Class<?>[]{Marker.class}, marker));

        invoke(log, level, new Class<?>[]{String.class}, level + " message");
        invoke(log, level, new Class<?>[]{String.class, Object.class}, level + " {}", "arg");
        invoke(log, level, new Class<?>[]{String.class, Object.class, Object.class},
                level + " {} {}", "arg1", "arg2");
        invoke(log, level, new Class<?>[]{String.class, Object[].class}, level + " {} {} {}", varArgs);
        invoke(log, level, new Class<?>[]{String.class, Throwable.class}, level + " exception", exception);

        invoke(log, level, new Class<?>[]{Marker.class, String.class}, marker, level + " marker message");
        invoke(log, level, new Class<?>[]{Marker.class, String.class, Object.class},
                marker, level + " marker {}", "arg");
        invoke(log, level, new Class<?>[]{Marker.class, String.class, Object.class, Object.class},
                marker, level + " marker {} {}", "arg1", "arg2");
        invoke(log, level, new Class<?>[]{Marker.class, String.class, Object[].class},
                marker, level + " marker {} {} {}", varArgs);
        invoke(log, level, new Class<?>[]{Marker.class, String.class, Throwable.class},
                marker, level + " marker exception", exception);

        Logger verifiedDelegate = verify(delegate);
        invoke(verifiedDelegate, enabledMethod(level));
        invoke(verifiedDelegate, enabledMethod(level), new Class<?>[]{Marker.class}, marker);
        invoke(verifiedDelegate, level, new Class<?>[]{String.class}, level + " message");
        invoke(verifiedDelegate, level, new Class<?>[]{String.class, Object.class}, level + " {}", "arg");
        invoke(verifiedDelegate, level, new Class<?>[]{String.class, Object.class, Object.class},
                level + " {} {}", "arg1", "arg2");
        invoke(verifiedDelegate, level, new Class<?>[]{String.class, Object[].class}, level + " {} {} {}", varArgs);
        invoke(verifiedDelegate, level, new Class<?>[]{String.class, Throwable.class}, level + " exception", exception);

        invoke(verifiedDelegate, level, new Class<?>[]{Marker.class, String.class}, marker, level + " marker message");
        invoke(verifiedDelegate, level, new Class<?>[]{Marker.class, String.class, Object.class},
                marker, level + " marker {}", "arg");
        invoke(verifiedDelegate, level, new Class<?>[]{Marker.class, String.class, Object.class, Object.class},
                marker, level + " marker {} {}", "arg1", "arg2");
        invoke(verifiedDelegate, level, new Class<?>[]{Marker.class, String.class, Object[].class},
                marker, level + " marker {} {} {}", varArgs);
        invoke(verifiedDelegate, level, new Class<?>[]{Marker.class, String.class, Throwable.class},
                marker, level + " marker exception", exception);
    }

    @Test
    void shouldPrefixPlainMessageWhenErrorCodePresent() {
        Logger delegate = mock(Logger.class);
        ErrorCode code = mock(ErrorCode.class);

        when(code.getFormattedCode()).thenReturn("E-123");

        ExtendedErrorLogger log = loggerWithDelegate(LOGGER_NAME, delegate);

        log.error(code, "Something bad happened");

        verify(delegate).error("[error_code=E-123] Something bad happened");
    }

    @Test
    void shouldNotPrefixPlainMessageWhenErrorCodeIsNull() {
        Logger delegate = mock(Logger.class);

        ExtendedErrorLogger log = loggerWithDelegate(LOGGER_NAME, delegate);

        log.error((ErrorCode) null, "Just a message");

        verify(delegate).error("Just a message");
    }

    @Test
    void shouldPrefixAndForwardSingleArgWhenFormatWithOneArg() {
        Logger delegate = mock(Logger.class);
        ErrorCode code = mock(ErrorCode.class);

        when(code.getFormattedCode()).thenReturn("E-42");

        ExtendedErrorLogger log = loggerWithDelegate(LOGGER_NAME, delegate);

        log.error(code, "Hello {}", "world");

        verify(delegate).error("[error_code=E-42] Hello {}", "world");
    }

    @Test
    void shouldPrefixAndForwardTwoArgsWhenFormatWithTwoArgs() {
        Logger delegate = mock(Logger.class);
        ErrorCode code = mock(ErrorCode.class);

        when(code.getFormattedCode()).thenReturn("E-7");

        ExtendedErrorLogger log = loggerWithDelegate(LOGGER_NAME, delegate);

        log.error(code, "X={}, Y={}", 10, 20);

        verify(delegate).error("[error_code=E-7] X={}, Y={}", 10, 20);
    }

    @Test
    void shouldPrefixAndForwardVarArgsWhenFormatWithVarArgs() {
        Logger delegate = mock(Logger.class);
        ErrorCode code = mock(ErrorCode.class);

        when(code.getFormattedCode()).thenReturn("E-VA");

        ExtendedErrorLogger log = loggerWithDelegate(LOGGER_NAME, delegate);

        log.error(code, "vals: {} {} {}", 1, "two", 3L);

        verify(delegate).error("[error_code=E-VA] vals: {} {} {}", 1, "two", 3L);
    }

    @Test
    void shouldPrefixAndForwardThrowableWhenErrorWithThrowable() {
        Logger delegate = mock(Logger.class);
        ErrorCode code = mock(ErrorCode.class);
        RuntimeException exception = new RuntimeException("boom");

        when(code.getFormattedCode()).thenReturn("E-T");

        ExtendedErrorLogger log = loggerWithDelegate(LOGGER_NAME, delegate);

        log.error(code, "Failed op", exception);

        verify(delegate).error("[error_code=E-T] Failed op", exception);
    }

    private static ExtendedErrorLogger loggerWithDelegate(String loggerName, Logger delegate) {
        try (MockedStatic<LoggerFactory> loggerFactory = Mockito.mockStatic(LoggerFactory.class)) {
            loggerFactory.when(() -> LoggerFactory.getLogger(loggerName)).thenReturn(delegate);

            return new ExtendedErrorLogger(loggerName);
        }
    }

    private static String enabledMethod(String level) {
        return "is" + Character.toUpperCase(level.charAt(0)) + level.substring(1) + "Enabled";
    }

    private static Object invoke(Object target, String methodName) {
        return invoke(target, methodName, new Class<?>[0]);
    }

    private static Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... arguments) {
        try {
            return target.getClass().getMethod(methodName, parameterTypes).invoke(target, arguments);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("Failed to invoke " + methodName, exception);
        }
    }
}
