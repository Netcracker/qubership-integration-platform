package org.qubership.integration.platform.runtime.catalog.cr;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiConflictException;

@Slf4j
final class ConflictRetry {
    static final int MAX_ATTEMPTS = 3;

    private ConflictRetry() {
    }

    static void run(String subject, Runnable attempt) {
        for (int attemptNumber = 1; ; attemptNumber++) {
            try {
                attempt.run();
                return;
            } catch (KubeApiConflictException conflict) {
                if (attemptNumber == MAX_ATTEMPTS) {
                    throw conflict;
                }
                log.warn("{} lost a concurrency race on attempt {}/{}; retrying against current state",
                        subject, attemptNumber, MAX_ATTEMPTS);
            }
        }
    }
}
