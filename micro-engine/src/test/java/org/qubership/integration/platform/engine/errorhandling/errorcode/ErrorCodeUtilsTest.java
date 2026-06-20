package org.qubership.integration.platform.engine.errorhandling.errorcode;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class ErrorCodeUtilsTest {

    @Test
    void shouldReturnEmptyListWhenMessageDoesNotContainExtraKeys() {
        List<String> result = ErrorCodeUtils.parseExtraKeys("Message without placeholders");

        assertEquals(List.of(), result);
    }

    @Test
    void shouldParseSingleExtraKey() {
        List<String> result = ErrorCodeUtils.parseExtraKeys("Message with ${tenantId}");

        assertEquals(List.of("tenantId"), result);
    }

    @Test
    void shouldParseMultipleExtraKeysInOriginalOrder() {
        List<String> result = ErrorCodeUtils.parseExtraKeys(
            "Message with ${tenantId}, ${chainId} and ${snapshotId}"
        );

        assertEquals(List.of("tenantId", "chainId", "snapshotId"), result);
    }

    @Test
    void shouldParseRepeatedExtraKeys() {
        List<String> result = ErrorCodeUtils.parseExtraKeys(
            "Message with ${tenantId} and repeated ${tenantId}"
        );

        assertEquals(List.of("tenantId", "tenantId"), result);
    }

    @Test
    void shouldParseEmptyExtraKey() {
        List<String> result = ErrorCodeUtils.parseExtraKeys("Message with ${}");

        assertEquals(List.of(""), result);
    }

    @Test
    void shouldParseExtraKeysContainingDotsDashesAndUnderscores() {
        List<String> result = ErrorCodeUtils.parseExtraKeys(
            "Message with ${tenant.id}, ${chain-id} and ${snapshot_id}"
        );

        assertEquals(List.of("tenant.id", "chain-id", "snapshot_id"), result);
    }

    @Test
    void shouldIgnoreIncompletePlaceholderWithoutClosingBrace() {
        List<String> result = ErrorCodeUtils.parseExtraKeys("Message with ${tenantId");

        assertEquals(List.of(), result);
    }

    @Test
    void shouldIgnoreTextWithClosingBraceOnly() {
        List<String> result = ErrorCodeUtils.parseExtraKeys("Message with tenantId}");

        assertEquals(List.of(), result);
    }

    @Test
    void shouldUseFirstClosingBraceForNestedLikePlaceholder() {
        List<String> result = ErrorCodeUtils.parseExtraKeys("Message with ${outer${inner}}");

        assertEquals(List.of("outer${inner"), result);
    }

    @Test
    void shouldThrowNullPointerExceptionWhenMessageIsNull() {
        assertThrows(NullPointerException.class, () -> ErrorCodeUtils.parseExtraKeys(null));
    }
}
