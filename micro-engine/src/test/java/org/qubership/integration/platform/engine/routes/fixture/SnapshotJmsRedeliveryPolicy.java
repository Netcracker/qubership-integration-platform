package org.qubership.integration.platform.engine.routes.fixture;

import org.apache.qpid.jms.JmsDestination;
import org.apache.qpid.jms.message.JmsMessageSupport;
import org.apache.qpid.jms.policy.JmsDefaultRedeliveryPolicy;
import org.apache.qpid.jms.policy.JmsRedeliveryPolicy;

import java.util.Map;

final class SnapshotJmsRedeliveryPolicy implements JmsRedeliveryPolicy {
    private final Map<String, Integer> deliveryLimits;

    SnapshotJmsRedeliveryPolicy(Map<String, Integer> deliveryLimits) {
        this.deliveryLimits = Map.copyOf(deliveryLimits);
    }

    @Override
    public JmsRedeliveryPolicy copy() {
        return new SnapshotJmsRedeliveryPolicy(deliveryLimits);
    }

    @Override
    public int getMaxRedeliveries(JmsDestination destination) {
        Integer attempts = deliveryLimits.get(destination.getAddress());
        return attempts == null ? JmsDefaultRedeliveryPolicy.DEFAULT_MAX_REDELIVERIES : attempts - 1;
    }

    @Override
    public int getOutcome(JmsDestination destination) {
        return deliveryLimits.containsKey(destination.getAddress())
                ? JmsMessageSupport.REJECTED : JmsDefaultRedeliveryPolicy.DEFAULT_OUTCOME;
    }
}
