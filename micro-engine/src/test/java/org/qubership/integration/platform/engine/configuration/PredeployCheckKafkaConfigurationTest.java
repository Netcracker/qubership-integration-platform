package org.qubership.integration.platform.engine.configuration;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class PredeployCheckKafkaConfigurationTest {

    private final PredeployCheckKafkaConfiguration configuration = new PredeployCheckKafkaConfiguration();

    @Test
    void shouldCreateValidationKafkaAdminConfigWithDefaultsWhenOptionalKafkaParametersAreEmpty() {
        configuration.truststoreLocation = "/tmp/truststore/defaulttruststore.jks";
        configuration.truststorePassword = Optional.empty();

        Map<String, Object> result = configuration.createValidationKafkaAdminConfig(
                "broker1:9092,broker2:9092",
                "",
                "",
                null
        );

        assertEquals("broker1:9092,broker2:9092", result.get(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG));
        assertEquals("PLAINTEXT", result.get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
        assertEquals("PLAIN", result.get(SaslConfigs.SASL_MECHANISM));
        assertFalse(result.containsKey(SaslConfigs.SASL_JAAS_CONFIG));

        assertEquals(5000L, result.get(CommonClientConfigs.SOCKET_CONNECTION_SETUP_TIMEOUT_MS_CONFIG));
        assertEquals(5000L, result.get(CommonClientConfigs.SOCKET_CONNECTION_SETUP_TIMEOUT_MAX_MS_CONFIG));
        assertEquals(5000, result.get(CommonClientConfigs.REQUEST_TIMEOUT_MS_CONFIG));
        assertEquals(5000, result.get(CommonClientConfigs.DEFAULT_API_TIMEOUT_MS_CONFIG));

        assertEquals("/tmp/truststore/defaulttruststore.jks", result.get(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG));
        assertEquals("", result.get(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG));
        assertEquals("JKS", result.get(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG));
    }

    @Test
    void shouldCreateValidationKafkaAdminConfigWithExplicitKafkaSecurityParameters() {
        configuration.truststoreLocation = "/tmp/custom-truststore.jks";
        configuration.truststorePassword = Optional.of("secret");

        Map<String, Object> result = configuration.createValidationKafkaAdminConfig(
                "kafka1:9093",
                "SASL_SSL",
                "SCRAM-SHA-512",
                "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"user\" password=\"pass\";"
        );

        assertEquals("kafka1:9093", result.get(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG));
        assertEquals("SASL_SSL", result.get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
        assertEquals("SCRAM-SHA-512", result.get(SaslConfigs.SASL_MECHANISM));
        assertEquals(
                "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"user\" password=\"pass\";",
                result.get(SaslConfigs.SASL_JAAS_CONFIG)
        );

        assertEquals(5000L, result.get(CommonClientConfigs.SOCKET_CONNECTION_SETUP_TIMEOUT_MS_CONFIG));
        assertEquals(5000L, result.get(CommonClientConfigs.SOCKET_CONNECTION_SETUP_TIMEOUT_MAX_MS_CONFIG));
        assertEquals(5000, result.get(CommonClientConfigs.REQUEST_TIMEOUT_MS_CONFIG));
        assertEquals(5000, result.get(CommonClientConfigs.DEFAULT_API_TIMEOUT_MS_CONFIG));

        assertEquals("/tmp/custom-truststore.jks", result.get(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG));
        assertEquals("secret", result.get(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG));
        assertEquals("JKS", result.get(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG));
    }
}
