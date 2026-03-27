package org.qubership.integration.platform.engine.camel.idempotency;

import org.apache.camel.Exchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.service.IdempotencyRecordService;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.MockExchanges;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.engine.model.constants.CamelConstants.SYSTEM_PROPERTY_PREFIX;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class PostgresIdempotentRepositoryTest {

    private static final String EXPIRY_PROPERTY = SYSTEM_PROPERTY_PREFIX + "keyExpiry";

    private PostgresIdempotentRepository repository;

    @Mock
    private IdempotencyRecordService idempotencyRecordService;

    @BeforeEach
    void setUp() {
        repository = new PostgresIdempotentRepository(idempotencyRecordService);
    }

    @Test
    void shouldAddKeyWithDefaultTtlWhenAddCalled() {
        when(idempotencyRecordService.insertIfNotExists("key-1", 600)).thenReturn(true);

        boolean result = repository.add("key-1");

        assertTrue(result);
        verify(idempotencyRecordService).insertIfNotExists("key-1", 600);
    }

    @Test
    void shouldAddKeyWithTtlFromExchangeWhenAddWithExchangeCalled() {
        Exchange exchange = MockExchanges.basic();
        exchange.setProperty(EXPIRY_PROPERTY, 120);

        when(idempotencyRecordService.insertIfNotExists("key-1", 120)).thenReturn(true);

        boolean result = repository.add(exchange, "key-1");

        assertTrue(result);
        verify(idempotencyRecordService).insertIfNotExists("key-1", 120);
    }

    @Test
    void shouldAddKeyWithDefaultTtlWhenExchangeDoesNotContainTtl() {
        Exchange exchange = MockExchanges.basic();

        when(idempotencyRecordService.insertIfNotExists("key-1", 600)).thenReturn(true);

        boolean result = repository.add(exchange, "key-1");

        assertTrue(result);
        verify(idempotencyRecordService).insertIfNotExists("key-1", 600);
    }

    @Test
    void shouldThrowWhenTtlIsZeroOrNegative() {
        Exchange exchange = MockExchanges.basic();
        exchange.setProperty(EXPIRY_PROPERTY, 0);

        assertThrows(IllegalArgumentException.class, () -> repository.add(exchange, "key-1"));
    }

    @Test
    void shouldReturnTrueWhenContainsFindsKey() {
        when(idempotencyRecordService.exists("key-1")).thenReturn(true);

        boolean result = repository.contains("key-1");

        assertTrue(result);
        verify(idempotencyRecordService).exists("key-1");
    }

    @Test
    void shouldReturnFalseWhenContainsDoesNotFindKey() {
        when(idempotencyRecordService.exists("key-1")).thenReturn(false);

        boolean result = repository.contains("key-1");

        assertFalse(result);
        verify(idempotencyRecordService).exists("key-1");
    }

    @Test
    void shouldReturnDeleteResultWhenRemoveCalled() {
        when(idempotencyRecordService.delete("key-1")).thenReturn(true);

        boolean result = repository.remove("key-1");

        assertTrue(result);
        verify(idempotencyRecordService).delete("key-1");
    }

    @Test
    void shouldDoNothingWhenClearCalled() {
        assertDoesNotThrow(() -> repository.clear());
    }

    @Test
    void shouldAlwaysReturnTrueWhenConfirmCalled() {
        assertTrue(repository.confirm("key-1"));
    }
}
