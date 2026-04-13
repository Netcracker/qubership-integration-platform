package org.qubership.integration.platform.engine.camel.components.kafka.producer;

import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.component.kafka.producer.support.KeyValueHolderIterator;
import org.apache.camel.util.KeyValueHolder;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.qubership.integration.platform.engine.camel.components.kafka.configuration.KafkaCustomConfiguration;

import java.util.Iterator;
import java.util.List;

import static org.apache.camel.component.kafka.producer.support.ProducerUtil.tryConvertToSerializedType;

/**
 * Based on {@link KeyValueHolderIterator}
 */
public class KeyValueHolderCustomIterator implements Iterator<KeyValueHolder<Object, ProducerRecord<Object, Object>>> {

    private final Iterator<Object> msgList;
    private final Exchange exchange;
    private final KafkaCustomConfiguration kafkaConfiguration;
    private final String msgTopic;
    private final List<Header> propagatedHeaders;

    public KeyValueHolderCustomIterator(Iterator<Object> msgList, Exchange exchange, KafkaCustomConfiguration kafkaConfiguration,
        String msgTopic, List<Header> propagatedHeaders) {
        this.msgList = msgList;
        this.exchange = exchange;
        this.kafkaConfiguration = kafkaConfiguration;
        this.msgTopic = msgTopic;
        this.propagatedHeaders = propagatedHeaders;
    }

    @Override
    public boolean hasNext() {
        return msgList.hasNext();
    }

    @Override
    public KeyValueHolder<Object, ProducerRecord<Object, Object>> next() {
        // must convert each entry of the iterator into the value
        // according to the serializer
        final Object body = msgList.next();

        if (body instanceof Exchange || body instanceof Message) {
            final Message innerMessage = getInnerMessage(body);
            final Exchange innerExchange = getInnerExchange(body);

            final String innerTopic = getInnerTopic(innerMessage);
            final Integer innerPartitionKey = getInnerPartitionKey(innerMessage);
            final Object innerKey = getInnerKey(innerExchange, innerMessage);
            final Long innerTimestamp = getOverrideTimestamp(innerMessage);

            final Exchange ex = innerExchange == null ? exchange : innerExchange;

            final Object value = tryConvertToSerializedType(ex, innerMessage.getBody(),
                kafkaConfiguration.getValueSerializer());

            return new KeyValueHolder<>(
                body,
                new ProducerRecord<>(
                    innerTopic, innerPartitionKey, innerTimestamp, innerKey, value, propagatedHeaders));
        }

        return new KeyValueHolder<>(
            body,
            new ProducerRecord<>(
                msgTopic, null, null, null, body, propagatedHeaders));
    }

    private Message getInnerMessage(Object body) {
        if (body instanceof Exchange) {
            return ((Exchange) body).getIn();
        }

        return (Message) body;
    }

    private Exchange getInnerExchange(Object body) {
        if (body instanceof Exchange) {
            return (Exchange) body;
        }

        return null;
    }

    private boolean hasValidTimestampHeader(Object timeStamp) {
        if (timeStamp != null) {
            return timeStamp instanceof Long;
        }

        return false;
    }

    private Long getOverrideTimestamp(Message innerMessage) {
        Long timeStamp = null;
        Object overrideTimeStamp = innerMessage.removeHeader(KafkaConstants.OVERRIDE_TIMESTAMP);
        if (overrideTimeStamp != null) {
            timeStamp = exchange.getContext().getTypeConverter().convertTo(Long.class, exchange, overrideTimeStamp);
        }
        return timeStamp;
    }

    private String getInnerTopic(Message innerMessage) {
        if (innerMessage.getHeader(KafkaConstants.OVERRIDE_TOPIC) != null) {
            return (String) innerMessage.removeHeader(KafkaConstants.OVERRIDE_TOPIC);
        }

        return msgTopic;
    }

    private Object getInnerKey(Exchange innerExchange, Message innerMessage) {
        Object innerKey = innerMessage.getHeader(KafkaConstants.KEY);
        if (innerKey != null) {

            innerKey = kafkaConfiguration.getKey() != null ? kafkaConfiguration.getKey() : innerKey;

            if (innerKey != null) {
                innerKey = tryConvertToSerializedType(innerExchange, innerKey,
                    kafkaConfiguration.getKeySerializer());
            }

            return innerKey;
        }

        return null;
    }

    private Integer getInnerPartitionKey(Message innerMessage) {
        Integer partitionKey = innerMessage.getHeader(KafkaConstants.PARTITION_KEY, Integer.class);

        return kafkaConfiguration.getPartitionKey() != null
            ? kafkaConfiguration.getPartitionKey()
            : partitionKey;
    }

    @Override
    public void remove() {
        msgList.remove();
    }
}
