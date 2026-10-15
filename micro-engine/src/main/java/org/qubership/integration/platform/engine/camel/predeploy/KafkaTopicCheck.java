package org.qubership.integration.platform.engine.camel.predeploy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Endpoint;
import org.apache.camel.component.kafka.KafkaConfiguration;
import org.apache.camel.component.kafka.KafkaEndpoint;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.AuthorizationException;
import org.qubership.integration.platform.engine.camel.components.kafka.KafkaCustomEndpoint;
import org.qubership.integration.platform.engine.camel.components.kafka.configuration.KafkaCustomConfiguration;
import org.qubership.integration.platform.engine.configuration.PredeployCheckKafkaConfiguration;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

@Slf4j
@ApplicationScoped
public class KafkaTopicCheck implements PredeployCheck {
    private static final Set<String> SCHEMES = Set.of("kafka", "kafka-custom");

    private final PredeployCheckKafkaConfiguration configuration;

    @Inject
    public KafkaTopicCheck(PredeployCheckKafkaConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public boolean applicableTo(String scheme) {
        return configuration.isCamelKafkaPredeployCheckEnabled() && SCHEMES.contains(scheme);
    }

    @Override
    public void check(Endpoint endpoint, boolean consumer) throws InterruptedException {
        if (endpoint instanceof KafkaCustomEndpoint custom) {
            KafkaCustomConfiguration config = custom.getConfiguration();
            checkTopics(config.getBrokers(), config.getTopic(), config.getSecurityProtocol(),
                    config.getSaslMechanism(), config.getSaslJaasConfig());
        } else {
            KafkaConfiguration config = ((KafkaEndpoint) endpoint).getConfiguration();
            checkTopics(config.getBrokers(), config.getTopic(), config.getSecurityProtocol(),
                    config.getSaslMechanism(), config.getSaslJaasConfig());
        }
    }

    private void checkTopics(
            String brokers,
            String topics,
            String securityProtocol,
            String saslMechanism,
            String saslJaasConfig
    ) throws InterruptedException {
        Set<String> missing = new HashSet<>(List.of(topics.split(",")));
        try (AdminClient client = AdminClient.create(configuration.createValidationKafkaAdminConfig(
                brokers, securityProtocol, saslMechanism, saslJaasConfig))) {
            missing.removeAll(client.listTopics().names().get());
        } catch (ExecutionException | KafkaException e) {
            if (e instanceof AuthorizationException || e.getCause() instanceof AuthorizationException) {
                log.warn("Kafka predeploy check skipped: brokers {} refused to list topics", brokers, e);
                return;
            }
            throw new IllegalStateException("Kafka predeploy check failed for brokers " + brokers
                    + ": connection configuration is invalid or the broker is unavailable. "
                    + "Check the brokers and the security settings of the element.", e);
        }

        if (!missing.isEmpty()) {
            throw new IllegalStateException("Kafka topics (" + String.join(", ", missing)
                    + ") not found on brokers " + brokers + ". Create the topics or change the element.");
        }
    }
}
