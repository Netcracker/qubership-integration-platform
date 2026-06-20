package org.qubership.integration.platform.engine.errorhandling.errorcode;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.logging.ContextHeaders;
import org.qubership.integration.platform.engine.model.errorhandling.ErrorCodePayload;
import org.qubership.integration.platform.engine.model.errorhandling.ErrorEntry;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ErrorCodeExceptionTest {

    private static final String FORMATTED_CODE = "E-001";
    private static final String REASON = "Something went wrong";
    private static final String CHAIN_ID = "3bf4e5c8-14a2-4e1b-8f9a-79b6d318b5a9";
    private static final String OVERRIDDEN_CHAIN_ID = "55bc9a8e-4291-4dd3-a163-521f079687b1";
    private static final String REQUEST_ID = "7c077a5d-4a18-45d9-b00e-2c72a88d614c";
    private static final String SNAPSHOT_ID = "f7b835b6-b31e-4e18-a872-356d955f7f19";
    private static final String COMPILED_MESSAGE = "Failed to process chain " + CHAIN_ID;

    @Test
    void shouldThrowIllegalArgumentExceptionWhenErrorCodeIsNull() {
        IllegalArgumentException exception = assertThrows(
            IllegalArgumentException.class,
            () -> new ErrorCodeException(null, "param")
        );

        assertEquals("ErrorCode cannot be null", exception.getMessage());
    }

    @Test
    void shouldStoreErrorCodeCompiledMessageAndMessageParams() {
        ErrorCode errorCode = mock(ErrorCode.class);

        when(errorCode.compileMessage(CHAIN_ID, SNAPSHOT_ID)).thenReturn(COMPILED_MESSAGE);

        ErrorCodeException exception = new ErrorCodeException(errorCode, CHAIN_ID, SNAPSHOT_ID);

        assertSame(errorCode, exception.getErrorCode());
        assertEquals(COMPILED_MESSAGE, exception.getCompiledMessage());
        assertArrayEquals(new String[]{CHAIN_ID, SNAPSHOT_ID}, exception.getMessageParams());
        assertEquals(Map.of(), exception.getAdditionalExtraParams());
    }

    @Test
    void shouldStoreCauseWhenCreatedWithInternalConstructor() {
        ErrorCode errorCode = mock(ErrorCode.class);
        RuntimeException cause = new RuntimeException("Original error");

        when(errorCode.compileMessage(CHAIN_ID)).thenReturn(COMPILED_MESSAGE);

        ErrorCodeException exception = new ErrorCodeException(
            errorCode,
            cause,
            Map.of(),
            CHAIN_ID
        );

        assertSame(cause, exception.getCause());
    }

    @Test
    void shouldReturnMessageWithFormattedCodeAndCompiledMessage() {
        ErrorCode errorCode = mock(ErrorCode.class);

        when(errorCode.compileMessage(CHAIN_ID)).thenReturn(COMPILED_MESSAGE);
        when(errorCode.getFormattedCode()).thenReturn(FORMATTED_CODE);

        ErrorCodeException exception = new ErrorCodeException(errorCode, CHAIN_ID);

        assertEquals("[E-001] Failed to process chain " + CHAIN_ID, exception.getMessage());
    }

    @Test
    void shouldReturnMessageWithReasonWhenCompiledMessageIsNull() {
        ErrorCode errorCode = errorCodeWithReasonOnly();

        when(errorCode.compileMessage(CHAIN_ID)).thenReturn(null);
        when(errorCode.getFormattedCode()).thenReturn(FORMATTED_CODE);

        ErrorCodeException exception = new ErrorCodeException(errorCode, CHAIN_ID);

        assertEquals("[E-001] Something went wrong", exception.getMessage());
    }

    @Test
    void shouldBuildResponseObjectWithCodeMessageReasonAndExtraParams() {
        ErrorCode errorCode = errorCodeWithPayload(List.of("chainId", "snapshotId"));

        when(errorCode.compileMessage(CHAIN_ID, SNAPSHOT_ID)).thenReturn(COMPILED_MESSAGE);
        when(errorCode.getFormattedCode()).thenReturn(FORMATTED_CODE);

        ErrorCodeException exception = new ErrorCodeException(errorCode, CHAIN_ID, SNAPSHOT_ID);

        ErrorEntry result = exception.buildResponseObject();

        assertEquals(FORMATTED_CODE, result.getCode());
        assertEquals(COMPILED_MESSAGE, result.getMessage());
        assertEquals(REASON, result.getReason());
        assertEquals(Map.of(
            "chainId", CHAIN_ID,
            "snapshotId", SNAPSHOT_ID
        ), result.getExtra());
    }

    @Test
    void shouldAddAdditionalExtraParamsToResponseObject() {
        ErrorCode errorCode = errorCodeWithPayload(List.of());

        when(errorCode.compileMessage()).thenReturn(COMPILED_MESSAGE);
        when(errorCode.getFormattedCode()).thenReturn(FORMATTED_CODE);

        ErrorCodeException exception = new ErrorCodeException(
            errorCode,
            Map.of("deploymentId", "deployment-id")
        );

        ErrorEntry result = exception.buildResponseObject();

        assertEquals(FORMATTED_CODE, result.getCode());
        assertEquals(COMPILED_MESSAGE, result.getMessage());
        assertEquals(REASON, result.getReason());
        assertEquals(Map.of("deploymentId", "deployment-id"), result.getExtra());
    }

    @Test
    void shouldLetAdditionalExtraParamsOverrideMappedParams() {
        ErrorCode errorCode = errorCodeWithPayload(List.of("chainId"));

        when(errorCode.compileMessage(CHAIN_ID)).thenReturn(COMPILED_MESSAGE);
        when(errorCode.getFormattedCode()).thenReturn(FORMATTED_CODE);

        ErrorCodeException exception = new ErrorCodeException(
            errorCode,
            null,
            Map.of("chainId", OVERRIDDEN_CHAIN_ID),
            CHAIN_ID
        );

        ErrorEntry result = exception.buildResponseObject();

        assertEquals(Map.of("chainId", OVERRIDDEN_CHAIN_ID), result.getExtra());
    }

    @Test
    void shouldRemoveRequestIdFromResponseExtraParams() {
        ErrorCode errorCode = errorCodeWithPayload(List.of(ContextHeaders.REQUEST_ID, "chainId"));

        when(errorCode.compileMessage(REQUEST_ID, CHAIN_ID)).thenReturn(COMPILED_MESSAGE);
        when(errorCode.getFormattedCode()).thenReturn(FORMATTED_CODE);

        ErrorCodeException exception = new ErrorCodeException(
            errorCode,
            REQUEST_ID,
            CHAIN_ID
        );

        ErrorEntry result = exception.buildResponseObject();

        assertEquals(Map.of("chainId", CHAIN_ID), result.getExtra());
    }

    @Test
    void shouldSkipExtraKeyWhenMessageParamIsMissing() {
        ErrorCode errorCode = errorCodeWithPayload(List.of("chainId", "snapshotId"));

        when(errorCode.compileMessage(CHAIN_ID)).thenReturn(COMPILED_MESSAGE);
        when(errorCode.getFormattedCode()).thenReturn(FORMATTED_CODE);

        ErrorCodeException exception = new ErrorCodeException(errorCode, CHAIN_ID);

        ErrorEntry result = exception.buildResponseObject();

        assertEquals(Map.of("chainId", CHAIN_ID), result.getExtra());
    }

    @Test
    void shouldSkipExtraKeyWhenMessageParamEqualsStringNull() {
        ErrorCode errorCode = errorCodeWithPayload(List.of("chainId", "snapshotId"));

        when(errorCode.compileMessage("null", SNAPSHOT_ID)).thenReturn(COMPILED_MESSAGE);
        when(errorCode.getFormattedCode()).thenReturn(FORMATTED_CODE);

        ErrorCodeException exception = new ErrorCodeException(errorCode, "null", SNAPSHOT_ID);

        ErrorEntry result = exception.buildResponseObject();

        assertEquals(Map.of("snapshotId", SNAPSHOT_ID), result.getExtra());
    }

    @Test
    void shouldIgnoreExtraMessageParamsWhenExtraKeysAreMissing() {
        ErrorCode errorCode = errorCodeWithPayload(List.of("chainId"));

        when(errorCode.compileMessage(CHAIN_ID, SNAPSHOT_ID)).thenReturn(COMPILED_MESSAGE);
        when(errorCode.getFormattedCode()).thenReturn(FORMATTED_CODE);

        ErrorCodeException exception = new ErrorCodeException(errorCode, CHAIN_ID, SNAPSHOT_ID);

        ErrorEntry result = exception.buildResponseObject();

        assertEquals(Map.of("chainId", CHAIN_ID), result.getExtra());
    }

    @Test
    void shouldUseEmptyAdditionalExtraParamsWhenCreatedWithMessageParamsConstructor() {
        ErrorCode errorCode = mock(ErrorCode.class);

        when(errorCode.compileMessage(CHAIN_ID)).thenReturn(COMPILED_MESSAGE);

        ErrorCodeException exception = new ErrorCodeException(errorCode, CHAIN_ID);

        assertEquals(Map.of(), exception.getAdditionalExtraParams());
    }

    private static ErrorCode errorCodeWithPayload(List<String> extraKeys) {
        ErrorCode errorCode = mock(ErrorCode.class);
        ErrorCodePayload payload = mock(ErrorCodePayload.class);

        when(errorCode.getPayload()).thenReturn(payload);
        when(payload.getReason()).thenReturn(REASON);
        when(payload.getExtraKeys()).thenReturn(extraKeys);

        return errorCode;
    }

    private static ErrorCode errorCodeWithReasonOnly() {
        ErrorCode errorCode = mock(ErrorCode.class);
        ErrorCodePayload payload = mock(ErrorCodePayload.class);

        when(errorCode.getPayload()).thenReturn(payload);
        when(payload.getReason()).thenReturn(REASON);

        return errorCode;
    }
}
