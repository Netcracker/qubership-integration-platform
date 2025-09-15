package org.qubership.integration.platform.engine.consul.updates;

import io.vertx.ext.consul.BlockingQueryOptions;
import io.vertx.ext.consul.KeyValue;
import io.vertx.ext.consul.KeyValueList;
import io.vertx.mutiny.ext.consul.ConsulClient;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.engine.consul.KVNotFoundException;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;

@Slf4j
public class UpdateGetterHelper<T> {
    private static final String WAIT_TIMEOUT = "20s";

    private final String key;
    private final ConsulClient consulClient;
    private final Function<List<KeyValue>, T> valueParser;

    private long lastIndex;
    private long previousIndex;

    public UpdateGetterHelper(
            String key,
            ConsulClient consulClient,
            Function<List<KeyValue>, T> valueParser
    ) {
        this.key = key;
        this.consulClient = consulClient;
        this.valueParser = valueParser;
        this.lastIndex = 0;
        this.previousIndex = 0;
    }

    private KeyValueList waitForKVChanges(String key, long index) throws KVNotFoundException {
        BlockingQueryOptions options = new BlockingQueryOptions()
                .setIndex(index)
                .setWait(WAIT_TIMEOUT);
        KeyValueList result = consulClient.getValuesWithOptions(key, options)
                .onFailure()
                .transform(failure -> {
                    log.error("Failed to get KV from consul: {}", failure.getMessage());
                    return failure;
                })
                .await()
                .indefinitely();
        if (!result.isPresent()) {
            throw new KVNotFoundException("Key not present in consul");
        }
        return result;
    }

    private Optional<T> getUpdates() throws KVNotFoundException {
        KeyValueList kvList = waitForKVChanges(key, lastIndex);
        boolean hasChanges = kvList.getIndex() != lastIndex;
        if (!hasChanges) {
            return Optional.empty();
        }
        try {
            T value = valueParser.apply(kvList.getList());
            previousIndex = lastIndex;
            lastIndex = kvList.getIndex();
            return Optional.of(value);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to parse response, target key in consul " +
                            "has invalid value format or value count: " +
                            kvList.getList(), e);
        }
    }

    private void rollbackLastIndex() {
        lastIndex = previousIndex;
    }

    public void checkForUpdates(
            Consumer<T> consumer
    ) {
        try {
            getUpdates().ifPresent(consumer);
        } catch (Exception e) {
            log.error("Failed to check for updates or process them", e);
            rollbackLastIndex();
            throw e;
        }
    }
}
