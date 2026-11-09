package org.qubership.integration.platform.engine.routes.fixture;

import org.jboss.logmanager.Logger;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;

final class SftpTriggerSnapshotLogObserver implements AutoCloseable {
    private final Logger logger = Logger.getLogger("org.apache.camel.component.file.remote.RemoteFileConsumer");
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();
    private final Level originalLevel;
    private final Handler handler;

    SftpTriggerSnapshotLogObserver(int port) {
        originalLevel = logger.getLevel();
        String address = ":" + port;
        handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record.getThrown() != null && record.getMessage().contains(address)) {
                    failures.add(record.getThrown());
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        handler.setLevel(Level.ALL);
        logger.addHandler(handler);
        logger.setLevel(Level.FINE);
    }

    List<Throwable> failures() {
        return List.copyOf(failures);
    }

    void clear() {
        failures.clear();
    }

    @Override
    public void close() {
        logger.removeHandler(handler);
        logger.setLevel(originalLevel);
    }
}
