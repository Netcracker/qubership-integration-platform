package org.qubership.integration.platform.engine.camel.components.kafka.consumer;

import com.netcracker.cloud.maas.bluegreen.kafka.Record;
import lombok.Getter;
import org.apache.kafka.common.KafkaException;

@Getter
public class ProcessRecordsException extends KafkaException {

    private final Record<?, ?> currentRecord;

    public ProcessRecordsException(Throwable cause, Record<?, ?> currentRecord) {
        super(cause);
        this.currentRecord = currentRecord;
    }
}
