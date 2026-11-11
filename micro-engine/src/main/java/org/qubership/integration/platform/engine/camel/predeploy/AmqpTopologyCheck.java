package org.qubership.integration.platform.engine.camel.predeploy;

import com.rabbitmq.client.Channel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Endpoint;
import org.apache.camel.component.springrabbit.SpringRabbitMQEndpoint;
import org.apache.camel.component.springrabbit.SpringRabbitMQHelper;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.springframework.amqp.rabbit.connection.Connection;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeoutException;

// Declares passively, so the check creates nothing.
@ApplicationScoped
public class AmqpTopologyCheck implements PredeployCheck {
    private final boolean enabled;

    @Inject
    public AmqpTopologyCheck(
            @ConfigProperty(name = "cip.camel.component.rabbitmq.predeploy-check-enabled") boolean enabled
    ) {
        this.enabled = enabled;
    }

    @Override
    public boolean applicableTo(String scheme) {
        return enabled && "rabbitmq-custom".equals(scheme);
    }

    @Override
    public void check(Endpoint endpoint, boolean consumer) throws IOException, TimeoutException {
        SpringRabbitMQEndpoint rabbit = (SpringRabbitMQEndpoint) endpoint;
        try (Connection connection = rabbit.getConnectionFactory().createConnection();
                Channel channel = connection.createChannel(false)) {
            if (consumer) {
                for (String queue : queueNames(rabbit.getQueues())) {
                    try {
                        channel.queueDeclarePassive(queue);
                    } catch (IOException e) {
                        // Quoted because a space around a comma is part of the name the consumer asks for.
                        throw new IllegalStateException("AMQP queue '" + queue
                                + "' not found. Declare the queue or change the element.", e);
                    }
                }
            } else if (!SpringRabbitMQHelper.isDefaultExchange(rabbit.getExchangeName())) {
                try {
                    channel.exchangeDeclarePassive(rabbit.getExchangeName());
                } catch (IOException e) {
                    throw new IllegalStateException("AMQP exchange '" + rabbit.getExchangeName()
                            + "' not found. Declare the exchange or change the element.", e);
                }
            }
        }
    }

    // Splits the list the way the consumer does, without trimming.
    private static List<String> queueNames(String queues) {
        return queues == null ? List.of() : List.of(queues.split(","));
    }
}
