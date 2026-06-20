package org.qubership.integration.platform.engine.camel.components.kafka.configuration;

import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.support.jsse.CipherSuitesParameters;
import org.apache.camel.support.jsse.KeyManagersParameters;
import org.apache.camel.support.jsse.KeyStoreParameters;
import org.apache.camel.support.jsse.SSLContextParameters;
import org.apache.camel.support.jsse.SecureSocketProtocolsParameters;
import org.apache.camel.support.jsse.TrustManagersParameters;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.common.security.auth.SecurityProtocol;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class KafkaCustomConfigurationTest {

    private final KafkaCustomConfiguration configuration = new KafkaCustomConfiguration();

    @Test
    void shouldCopyAdditionalPropertiesIndependently() {
        configuration.setTopic("topicA");
        configuration.setAdditionalProperties(new HashMap<>(Map.of("acks", "all")));

        KafkaCustomConfiguration copy = configuration.copy();
        copy.getAdditionalProperties().put("acks", "1");
        copy.getAdditionalProperties().put("custom.option", 42);

        assertEquals("topicA", copy.getTopic());
        assertEquals("all", configuration.getAdditionalProperties().get("acks"));
        assertFalse(configuration.getAdditionalProperties().containsKey("custom.option"));
    }

    @Test
    void shouldCreateProducerPropertiesWithDefaultsAndAdditionalOverrides() {
        Map<String, Object> additionalProperties = new HashMap<>();
        additionalProperties.put(ProducerConfig.ACKS_CONFIG, "0");
        additionalProperties.put("custom.option", 42);
        additionalProperties.put("ignored.null", null);

        configuration.setClientId("client-a");
        configuration.setRetries(3);
        configuration.setAdditionalProperties(additionalProperties);

        Properties props = configuration.createProducerProperties();

        assertEquals(KafkaConstants.KAFKA_DEFAULT_SERIALIZER,
                props.get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG));
        assertEquals(KafkaConstants.KAFKA_DEFAULT_SERIALIZER,
                props.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG));
        assertEquals("client-a", props.get(ProducerConfig.CLIENT_ID_CONFIG));
        assertEquals(3, props.get(ProducerConfig.RETRIES_CONFIG));
        assertEquals("0", props.get(ProducerConfig.ACKS_CONFIG));
        assertEquals(42, props.get("custom.option"));
        assertFalse(props.containsKey("ignored.null"));
    }

    @Test
    void shouldCreateConsumerPropertiesWithSpecificAvroReaderWhenEnabled() {
        configuration.setClientId("consumer-a");
        configuration.setMaxPollRecords(15);
        configuration.setIsolationLevel("read_committed");
        configuration.setSchemaRegistryURL("http://schema-registry:8081");
        configuration.setSpecificAvroReader(true);

        Properties props = configuration.createConsumerProperties();

        assertEquals(KafkaConstants.KAFKA_DEFAULT_DESERIALIZER,
                props.get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG));
        assertEquals(KafkaConstants.KAFKA_DEFAULT_DESERIALIZER,
                props.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG));
        assertEquals("consumer-a", props.get(ConsumerConfig.CLIENT_ID_CONFIG));
        assertEquals(15, props.get(ConsumerConfig.MAX_POLL_RECORDS_CONFIG));
        assertEquals("read_committed", props.get(ConsumerConfig.ISOLATION_LEVEL_CONFIG));
        assertEquals("http://schema-registry:8081", props.get("schema.registry.url"));
        assertEquals(true, props.get("specific.avro.reader"));
    }

    @Test
    void shouldSkipSpecificAvroReaderWhenDisabled() {
        Properties props = configuration.createConsumerProperties();

        assertFalse(props.containsKey("specific.avro.reader"));
    }

    @Test
    void shouldCreateSaslSslProducerPropertiesFromEndpointOptions() {
        configuration.setSecurityProtocol(SecurityProtocol.SASL_SSL.name());
        configuration.setSaslMechanism("SCRAM-SHA-512");
        configuration.setSaslJaasConfig("login config");
        configuration.setSaslKerberosServiceName("kafka");
        configuration.setSslTruststoreLocation("/tmp/truststore.jks");
        configuration.setSslTruststorePassword("secret");
        configuration.setSslEndpointAlgorithm("none");

        Properties props = configuration.createProducerProperties();

        assertEquals(SecurityProtocol.SASL_SSL.name(),
                props.get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
        assertEquals("SCRAM-SHA-512", props.get(SaslConfigs.SASL_MECHANISM));
        assertEquals("login config", props.get(SaslConfigs.SASL_JAAS_CONFIG));
        assertEquals("kafka", props.get(SaslConfigs.SASL_KERBEROS_SERVICE_NAME));
        assertEquals("/tmp/truststore.jks", props.get(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG));
        assertEquals("secret", props.get(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG));
        assertFalse(props.containsKey(SslConfigs.SSL_ENDPOINT_IDENTIFICATION_ALGORITHM_CONFIG));
    }

    @Test
    void shouldCreateConsumerPropertiesFromSslContextParametersWhenConfigured() {
        configuration.setSecurityProtocol(SecurityProtocol.SSL.name());
        configuration.setSslTruststoreLocation("/tmp/ignored-truststore.jks");
        configuration.setSslContextParameters(sslContextParameters());

        Properties props = configuration.createConsumerProperties();

        assertEquals(SecurityProtocol.SSL.name(), props.get(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG));
        assertEquals("TLSv1.3", props.get(SslConfigs.SSL_PROTOCOL_CONFIG));
        assertEquals("TestProvider", props.get(SslConfigs.SSL_PROVIDER_CONFIG));
        assertEquals("TLS_AES_128_GCM_SHA256,TLS_AES_256_GCM_SHA384",
                props.get(SslConfigs.SSL_CIPHER_SUITES_CONFIG));
        assertEquals("TLSv1.2,TLSv1.3", props.get(SslConfigs.SSL_ENABLED_PROTOCOLS_CONFIG));
        assertEquals("KeyManagerAlgorithm", props.get(SslConfigs.SSL_KEYMANAGER_ALGORITHM_CONFIG));
        assertEquals("key-password", props.get(SslConfigs.SSL_KEY_PASSWORD_CONFIG));
        assertEquals("PKCS12", props.get(SslConfigs.SSL_KEYSTORE_TYPE_CONFIG));
        assertEquals("file:/tmp/key-store.p12", props.get(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG));
        assertEquals("store-password", props.get(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG));
        assertEquals("TrustManagerAlgorithm", props.get(SslConfigs.SSL_TRUSTMANAGER_ALGORITHM_CONFIG));
        assertEquals("JKS", props.get(SslConfigs.SSL_TRUSTSTORE_TYPE_CONFIG));
        assertEquals("file:/tmp/trust-store.jks", props.get(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG));
        assertEquals("trust-password", props.get(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG));
    }

    private static SSLContextParameters sslContextParameters() {
        SSLContextParameters sslContextParameters = new SSLContextParameters();
        sslContextParameters.setSecureSocketProtocol("TLSv1.3");
        sslContextParameters.setProvider("TestProvider");

        CipherSuitesParameters cipherSuites = new CipherSuitesParameters();
        cipherSuites.setCipherSuite(List.of("TLS_AES_128_GCM_SHA256", "TLS_AES_256_GCM_SHA384"));
        sslContextParameters.setCipherSuites(cipherSuites);

        SecureSocketProtocolsParameters secureSocketProtocols = new SecureSocketProtocolsParameters();
        secureSocketProtocols.setSecureSocketProtocol(List.of("TLSv1.2", "TLSv1.3"));
        sslContextParameters.setSecureSocketProtocols(secureSocketProtocols);

        KeyManagersParameters keyManagers = new KeyManagersParameters();
        keyManagers.setAlgorithm("KeyManagerAlgorithm");
        keyManagers.setKeyPassword("key-password");
        keyManagers.setKeyStore(keyStore("PKCS12", "file:/tmp/key-store.p12", "store-password"));
        sslContextParameters.setKeyManagers(keyManagers);

        TrustManagersParameters trustManagers = new TrustManagersParameters();
        trustManagers.setAlgorithm("TrustManagerAlgorithm");
        trustManagers.setKeyStore(keyStore("JKS", "file:/tmp/trust-store.jks", "trust-password"));
        sslContextParameters.setTrustManagers(trustManagers);

        return sslContextParameters;
    }

    private static KeyStoreParameters keyStore(String type, String resource, String password) {
        KeyStoreParameters keyStore = new KeyStoreParameters();
        keyStore.setType(type);
        keyStore.setResource(resource);
        keyStore.setPassword(password);
        return keyStore;
    }
}
