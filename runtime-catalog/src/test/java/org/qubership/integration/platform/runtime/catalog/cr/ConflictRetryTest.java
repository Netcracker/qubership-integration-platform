package org.qubership.integration.platform.runtime.catalog.cr;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiConflictException;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConflictRetryTest {

    @DisplayName("Returns after the first attempt when it succeeds")
    @Test
    void returnsAfterTheFirstSuccessfulAttempt() {
        AtomicInteger attempts = new AtomicInteger();

        ConflictRetry.run("Deploy", attempts::incrementAndGet);

        assertEquals(1, attempts.get());
    }

    @DisplayName("Retries after a conflict and returns when the next attempt succeeds")
    @Test
    void retriesAfterAConflictAndReturnsWhenTheNextAttemptSucceeds() {
        AtomicInteger attempts = new AtomicInteger();

        ConflictRetry.run("Deploy", () -> {
            if (attempts.incrementAndGet() == 1) {
                throw new KubeApiConflictException("conflict", null);
            }
        });

        assertEquals(2, attempts.get());
    }

    @DisplayName("Rethrows the same conflict once the budget is exhausted")
    @Test
    void rethrowsTheSameConflictAfterTheBudgetIsExhausted() {
        KubeApiConflictException conflict = new KubeApiConflictException("conflict", null);
        AtomicInteger attempts = new AtomicInteger();

        KubeApiConflictException thrown = assertThrows(KubeApiConflictException.class,
                () -> ConflictRetry.run("Deploy", () -> {
                    attempts.incrementAndGet();
                    throw conflict;
                }));

        assertSame(conflict, thrown);
        assertEquals(ConflictRetry.MAX_ATTEMPTS, attempts.get());
    }

    @DisplayName("Does not retry a failure that is not a conflict")
    @Test
    void doesNotRetryAFailureThatIsNotAConflict() {
        MicroDomainDeployError failure = new MicroDomainDeployError("boom", null);
        AtomicInteger attempts = new AtomicInteger();

        MicroDomainDeployError thrown = assertThrows(MicroDomainDeployError.class,
                () -> ConflictRetry.run("Deploy", () -> {
                    attempts.incrementAndGet();
                    throw failure;
                }));

        assertSame(failure, thrown);
        assertEquals(1, attempts.get());
    }
}
