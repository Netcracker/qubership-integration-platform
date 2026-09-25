package org.qubership.integration.platform.maven.plugin.domain.util;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.function.FailableConsumer;
import org.apache.commons.lang3.function.FailableFunction;

import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class SkippableFailableOperationWrapper {
    private final boolean rethrow;
    private final AtomicInteger errorCount;

    public SkippableFailableOperationWrapper(boolean rethrow) {
        this.rethrow = rethrow;
        this.errorCount = new AtomicInteger(0);
    }

    public int getErrorCount() {
        return errorCount.get();
    }

    public <T, R, E extends Throwable> FailableFunction<T, R, E> wrapFunction(FailableFunction<T, R, E> operation) {
        return (arg) -> {
            try {
                return operation.apply(arg);
            } catch (Throwable e) {
                errorCount.incrementAndGet();
                if (rethrow) {
                    throw e;
                } else {
                    log.error(e.getMessage(), e);
                    return null;
                }
            }
        };
    }

    public <T, E extends Throwable> FailableConsumer<T, E> wrapConsumer(FailableConsumer<T, E> consumer) {
        return (arg) -> {
            try {
                consumer.accept(arg);
            } catch (Throwable e) {
                errorCount.incrementAndGet();
                if (rethrow) {
                    throw e;
                } else {
                    log.error(e.getMessage(), e);
                }
            }
        };
    }
}
