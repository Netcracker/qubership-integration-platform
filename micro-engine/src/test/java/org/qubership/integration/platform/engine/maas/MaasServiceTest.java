package org.qubership.integration.platform.engine.maas;

import com.netcracker.cloud.maas.client.api.Classifier;
import com.netcracker.cloud.maas.client.api.kafka.KafkaMaaSClient;
import com.netcracker.cloud.maas.client.api.kafka.TopicAddress;
import com.netcracker.cloud.maas.client.api.kafka.TopicUserCredentials;
import com.netcracker.cloud.maas.client.api.rabbit.RabbitMaaSClient;
import com.netcracker.cloud.maas.client.api.rabbit.VHost;
import org.apache.camel.CamelContext;
import org.apache.camel.spi.Registry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.configuration.ApplicationConfiguration;
import org.qubership.integration.platform.engine.configuration.tenant.TenantConfiguration;
import org.qubership.integration.platform.engine.maas.kafka.AuthType;
import org.qubership.integration.platform.engine.metadata.MaasClassifierInfo;
import org.qubership.integration.platform.engine.service.VariablesService;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.engine.maas.MaasUtils.getMaasParamPlaceholder;
import static org.qubership.integration.platform.engine.model.ElementOptions.ADDRESSES;
import static org.qubership.integration.platform.engine.model.ElementOptions.BROKERS;
import static org.qubership.integration.platform.engine.model.ElementOptions.PASSWORD;
import static org.qubership.integration.platform.engine.model.ElementOptions.SASL_JAAS_CONFIG;
import static org.qubership.integration.platform.engine.model.ElementOptions.SASL_MECHANISM;
import static org.qubership.integration.platform.engine.model.ElementOptions.SECURITY_PROTOCOL;
import static org.qubership.integration.platform.engine.model.ElementOptions.SSL;
import static org.qubership.integration.platform.engine.model.ElementOptions.TOPICS;
import static org.qubership.integration.platform.engine.model.ElementOptions.USERNAME;
import static org.qubership.integration.platform.engine.model.ElementOptions.VHOST;
import static org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties.OPERATION_PATH_TOPIC;
import static org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties.OPERATION_PROTOCOL_TYPE_AMQP;
import static org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties.OPERATION_PROTOCOL_TYPE_KAFKA;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MaasServiceTest {

    private static final String KAFKA_ELEMENT_ID = "kafka-element";
    private static final String RABBIT_ELEMENT_ID = "rabbit-element";
    private static final String SASL_SSL = "SASL_SSL";
    private static final String AMQP_PROTOCOL = "amqp";
    private static final String AMQPS_PROTOCOL = "amqps";

    @Mock
    private CamelContext camelContext;

    @Mock
    private Registry registry;

    @Mock
    private VariablesService variablesService;

    @Mock
    private RabbitMaaSClient rabbitMaasClient;

    @Mock
    private KafkaMaaSClient kafkaMaasClient;

    @Mock
    private ApplicationConfiguration applicationConfiguration;

    @Mock
    private TenantConfiguration tenantConfiguration;

    private MaasService service;

    private MockedConstruction<Classifier> classifierConstruction;

    @BeforeEach
    void setUp() {
        classifierConstruction = mockConstruction(Classifier.class);

        service = new MaasService();
        service.camelContext = camelContext;
        service.variablesService = variablesService;
        service.rabbitMaasClient = rabbitMaasClient;
        service.kafkaMaasClient = kafkaMaasClient;
        service.applicationConfiguration = applicationConfiguration;
        service.tenantConfiguration = tenantConfiguration;
    }

    @AfterEach
    void tearDown() {
        classifierConstruction.close();
    }

    @Test
    void shouldResolveKafkaMaasParametersWithPlainAuth() {
        MaasClassifierInfo classifierInfo = MaasClassifierInfo.builder()
            .elementId(KAFKA_ELEMENT_ID)
            .protocol(OPERATION_PROTOCOL_TYPE_KAFKA)
            .classifier("orders-classifier")
            .namespace("orders-namespace")
            .tenantId("tenant-id")
            .tenantEnabled("true")
            .build();

        TopicAddress topicAddress = kafkaTopicWithName();
        TopicUserCredentials credentials = kafkaCredentials();

        stubRegistryWith(classifierInfo);
        stubKafkaVariables(classifierInfo);
        stubKafkaTopicWithPlainCredentials(topicAddress, credentials);

        String content = String.join("&",
            "brokers=" + placeholder(KAFKA_ELEMENT_ID, BROKERS),
            "securityProtocol=" + placeholder(KAFKA_ELEMENT_ID, SECURITY_PROTOCOL),
            "saslMechanism=" + placeholder(KAFKA_ELEMENT_ID, SASL_MECHANISM),
            "saslJaasConfig=" + placeholder(KAFKA_ELEMENT_ID, SASL_JAAS_CONFIG),
            "topics=" + placeholder(KAFKA_ELEMENT_ID, TOPICS),
            "operationPathTopic=" + placeholder(KAFKA_ELEMENT_ID, OPERATION_PATH_TOPIC)
        );

        String result = service.resolveMaasParameters(content);

        assertTrue(result.contains("brokers=kafka.example.com:9093"));
        assertTrue(result.contains("securityProtocol=SASL_SSL"));
        assertTrue(result.contains("saslMechanism=PLAIN"));
        assertTrue(result.contains("topics=orders-topic"));
        assertTrue(result.contains("operationPathTopic=orders-topic"));
        assertTrue(result.contains(
            "org.apache.kafka.common.security.plain.PlainLoginModule required "
                + "username=\"kafka-user\" password=\"kafka-password\";"
        ));
    }

    @Test
    void shouldRemoveKafkaAuthPlaceholdersWhenCredentialsAreMissing() {
        MaasClassifierInfo classifierInfo = MaasClassifierInfo.builder()
            .elementId(KAFKA_ELEMENT_ID)
            .protocol(OPERATION_PROTOCOL_TYPE_KAFKA)
            .classifier("orders-classifier")
            .namespace("orders-namespace")
            .tenantId("tenant-id")
            .tenantEnabled("false")
            .build();

        TopicAddress topicAddress = kafkaTopicWithName();

        stubRegistryWith(classifierInfo);
        stubKafkaVariables(classifierInfo);
        stubKafkaTopicWithoutCredentials(topicAddress);

        String content = String.join("",
            "brokers=", placeholder(KAFKA_ELEMENT_ID, BROKERS),
            "&amp;saslMechanism=", placeholder(KAFKA_ELEMENT_ID, SASL_MECHANISM),
            "&amp;saslJaasConfig=", placeholder(KAFKA_ELEMENT_ID, SASL_JAAS_CONFIG)
        );

        String result = service.resolveMaasParameters(content);

        assertTrue(result.contains("brokers=kafka.example.com:9093"));
        assertFalse(result.contains("saslMechanism"));
        assertFalse(result.contains("saslJaasConfig"));
        assertFalse(result.contains(placeholder(KAFKA_ELEMENT_ID, SASL_MECHANISM)));
        assertFalse(result.contains(placeholder(KAFKA_ELEMENT_ID, SASL_JAAS_CONFIG)));
    }

    @Test
    void shouldResolveRabbitMqMaasParametersWithSslEnabled() {
        MaasClassifierInfo classifierInfo = MaasClassifierInfo.builder()
            .elementId(RABBIT_ELEMENT_ID)
            .protocol(OPERATION_PROTOCOL_TYPE_AMQP)
            .classifier("rabbit-classifier")
            .namespace("rabbit-namespace")
            .build();

        VHost vHost = rabbitVHost(
            AMQPS_PROTOCOL + "://rabbit.example.com:5671/test-vhost"
        );

        stubRegistryWith(classifierInfo);
        when(variablesService.injectVariables("rabbit-classifier")).thenReturn("rabbit-classifier");
        when(variablesService.injectVariables("rabbit-namespace")).thenReturn("rabbit-namespace");
        when(rabbitMaasClient.getVirtualHost(any(Classifier.class))).thenReturn(vHost);

        String content = String.join("&",
            "addresses=" + placeholder(RABBIT_ELEMENT_ID, ADDRESSES),
            "vhost=" + placeholder(RABBIT_ELEMENT_ID, VHOST),
            "username=" + placeholder(RABBIT_ELEMENT_ID, USERNAME),
            "password=" + placeholder(RABBIT_ELEMENT_ID, PASSWORD),
            "sslProtocol=" + placeholder(RABBIT_ELEMENT_ID, SSL)
        );

        String result = service.resolveMaasParameters(content);

        assertTrue(result.contains("addresses=rabbit.example.com:5671"));
        assertTrue(result.contains("vhost=test-vhost"));
        assertTrue(result.contains("username=rabbit-user"));
        assertTrue(result.contains("password=rabbit-password"));
        assertTrue(result.contains("sslProtocol=true"));
    }

    @Test
    void shouldRemoveRabbitSslPlaceholderWhenSslIsDisabled() {
        MaasClassifierInfo classifierInfo = MaasClassifierInfo.builder()
            .elementId(RABBIT_ELEMENT_ID)
            .protocol(OPERATION_PROTOCOL_TYPE_AMQP)
            .classifier("rabbit-classifier")
            .namespace("rabbit-namespace")
            .build();

        VHost vHost = rabbitVHost(
            AMQP_PROTOCOL + "://rabbit.example.com:5672/test-vhost"
        );

        stubRegistryWith(classifierInfo);
        when(variablesService.injectVariables("rabbit-classifier")).thenReturn("rabbit-classifier");
        when(variablesService.injectVariables("rabbit-namespace")).thenReturn("rabbit-namespace");
        when(rabbitMaasClient.getVirtualHost(any(Classifier.class))).thenReturn(vHost);

        String content = String.join("",
            "addresses=", placeholder(RABBIT_ELEMENT_ID, ADDRESSES),
            "&amp;sslProtocol=", placeholder(RABBIT_ELEMENT_ID, SSL)
        );

        String result = service.resolveMaasParameters(content);

        assertTrue(result.contains("addresses=rabbit.example.com:5672"));
        assertFalse(result.contains("sslProtocol"));
        assertFalse(result.contains(placeholder(RABBIT_ELEMENT_ID, SSL)));
    }

    @Test
    void shouldKeepContentUnchangedWhenRabbitClassifierIsEmpty() {
        MaasClassifierInfo classifierInfo = MaasClassifierInfo.builder()
            .elementId(RABBIT_ELEMENT_ID)
            .protocol(OPERATION_PROTOCOL_TYPE_AMQP)
            .classifier("")
            .build();

        String content = "addresses=" + placeholder(RABBIT_ELEMENT_ID, ADDRESSES);

        stubRegistryWith(classifierInfo);
        when(variablesService.injectVariables("")).thenReturn("");

        String result = service.resolveMaasParameters(content);

        assertEquals(content, result);
    }

    @Test
    void shouldUseDefaultRabbitClassifierWhenInjectedClassifierIsNull() {
        MaasClassifierInfo classifierInfo = MaasClassifierInfo.builder()
            .elementId(RABBIT_ELEMENT_ID)
            .protocol(OPERATION_PROTOCOL_TYPE_AMQP)
            .classifier(null)
            .namespace("rabbit-namespace")
            .build();

        VHost vHost = rabbitVHost(
            AMQP_PROTOCOL + "://rabbit.example.com:5672/test-vhost"
        );

        stubRegistryWith(classifierInfo);
        when(variablesService.injectVariables(null)).thenReturn(null);
        when(variablesService.injectVariables("rabbit-namespace")).thenReturn("rabbit-namespace");
        when(rabbitMaasClient.getVirtualHost(any(Classifier.class))).thenReturn(vHost);

        String content = "addresses=" + placeholder(RABBIT_ELEMENT_ID, ADDRESSES);

        String result = service.resolveMaasParameters(content);

        assertTrue(result.contains("addresses=rabbit.example.com:5672"));
    }

    @Test
    void shouldThrowMaasExceptionWhenProtocolIsUnsupported() {
        MaasClassifierInfo classifierInfo = MaasClassifierInfo.builder()
            .elementId("unsupported-element")
            .protocol("unsupported")
            .build();

        stubRegistryWith(classifierInfo);

        assertThrows(MaasException.class, () -> service.resolveMaasParameters("content"));
    }

    @Test
    void shouldThrowMaasExceptionWhenKafkaTopicIsNotFound() {
        when(applicationConfiguration.getNamespace()).thenReturn("local");
        when(kafkaMaasClient.getTopic(any(Classifier.class))).thenReturn(Optional.empty());

        MaasException exception = assertThrows(
            MaasException.class,
            () -> service.getKafkaTopic("missing-classifier", "", "", false)
        );

        assertInstanceOf(TopicNotFoundException.class, exception.getCause());
    }

    @Test
    void shouldReturnKafkaTopicWhenTopicExists() {
        TopicAddress topicAddress = plainKafkaTopic();

        when(applicationConfiguration.getNamespace()).thenReturn("local");
        when(kafkaMaasClient.getTopic(any(Classifier.class))).thenReturn(Optional.of(topicAddress));

        TopicAddress result = service.getKafkaTopic("orders-classifier", "", "", false);

        assertSame(topicAddress, result);
    }

    @Test
    void shouldReturnKafkaTopicWhenTenantTopicIsEnabledAndTenantIdIsDefaulted() {
        TopicAddress topicAddress = plainKafkaTopic();

        when(applicationConfiguration.getNamespace()).thenReturn("local");
        when(tenantConfiguration.getDefaultTenant()).thenReturn("default-tenant");
        when(kafkaMaasClient.getTopic(any(Classifier.class))).thenReturn(Optional.of(topicAddress));

        TopicAddress result = service.getKafkaTopic("orders-classifier", "", "", true);

        assertSame(topicAddress, result);
    }

    @Test
    void shouldThrowMaasExceptionWhenRabbitVHostIsMissing() {
        when(applicationConfiguration.getNamespace()).thenReturn("local");
        when(rabbitMaasClient.getVirtualHost(any(Classifier.class))).thenReturn(null);

        MaasException exception = assertThrows(
            MaasException.class,
            () -> service.getRabbitVhost("missing-vhost", "")
        );

        assertInstanceOf(RuntimeException.class, exception.getCause());
    }

    @Test
    void shouldThrowMaasExceptionWhenRabbitVHostUriIsInvalid() {
        MaasClassifierInfo classifierInfo = MaasClassifierInfo.builder()
            .elementId(RABBIT_ELEMENT_ID)
            .protocol(OPERATION_PROTOCOL_TYPE_AMQP)
            .classifier("rabbit-classifier")
            .namespace("rabbit-namespace")
            .build();

        VHost vHost = rabbitVHostWithConnectionStringOnly();

        stubRegistryWith(classifierInfo);
        when(variablesService.injectVariables("rabbit-classifier")).thenReturn("rabbit-classifier");
        when(variablesService.injectVariables("rabbit-namespace")).thenReturn("rabbit-namespace");
        when(rabbitMaasClient.getVirtualHost(any(Classifier.class))).thenReturn(vHost);

        assertThrows(MaasException.class, () -> service.resolveMaasParameters("content"));
    }

    @Test
    void shouldThrowMaasExceptionWhenKafkaProtocolServersAreSelectedButResolvedServersAreMissing() {
        MaasClassifierInfo classifierInfo = MaasClassifierInfo.builder()
            .elementId(KAFKA_ELEMENT_ID)
            .protocol(OPERATION_PROTOCOL_TYPE_KAFKA)
            .classifier("orders-classifier")
            .namespace("orders-namespace")
            .tenantId("tenant-id")
            .tenantEnabled("false")
            .build();

        TopicAddress topicAddress = plainKafkaTopic();

        stubRegistryWith(classifierInfo);
        stubKafkaVariables(classifierInfo);
        when(kafkaMaasClient.getTopic(any(Classifier.class))).thenReturn(Optional.of(topicAddress));
        when(topicAddress.getBoostrapServers(SASL_SSL)).thenReturn("kafka.example.com:9093", null);
        stubNoCredentials(topicAddress);

        assertThrows(MaasException.class, () -> service.resolveMaasParameters("content"));
    }

    @Test
    void shouldNotChangeContentWhenThereAreNoMaaSClassifiersInRegistry() {
        stubRegistryWith();

        String result = service.resolveMaasParameters("content");

        assertEquals("content", result);
    }

    private void stubRegistryWith(MaasClassifierInfo... classifierInfos) {
        when(camelContext.getRegistry()).thenReturn(registry);
        when(registry.findByType(MaasClassifierInfo.class)).thenReturn(Set.of(classifierInfos));
    }

    private void stubKafkaVariables(MaasClassifierInfo classifierInfo) {
        when(variablesService.injectVariables(classifierInfo.getClassifier())).thenReturn(classifierInfo.getClassifier());
        when(variablesService.injectVariables(classifierInfo.getNamespace())).thenReturn(classifierInfo.getNamespace());
        when(variablesService.injectVariables(classifierInfo.getTenantId())).thenReturn(classifierInfo.getTenantId());
        when(variablesService.injectVariables(classifierInfo.getTenantEnabled())).thenReturn(classifierInfo.getTenantEnabled());
    }

    private void stubKafkaTopicWithPlainCredentials(
        TopicAddress topicAddress,
        TopicUserCredentials credentials
    ) {
        when(kafkaMaasClient.getTopic(any(Classifier.class))).thenReturn(Optional.of(topicAddress));
        when(topicAddress.getBoostrapServers(SASL_SSL)).thenReturn("kafka.example.com:9093");
        when(topicAddress.getCredentials(AuthType.SSL_CERT_PLUS_SCRAM.getName())).thenReturn(Optional.empty());
        when(topicAddress.getCredentials(AuthType.SCRAM.getName())).thenReturn(Optional.empty());
        when(topicAddress.getCredentials(AuthType.SSL_CERT_PLUS_PLAIN.getName())).thenReturn(Optional.empty());
        when(topicAddress.getCredentials(AuthType.PLAIN.getName())).thenReturn(Optional.of(credentials));
    }

    private void stubKafkaTopicWithoutCredentials(TopicAddress topicAddress) {
        when(kafkaMaasClient.getTopic(any(Classifier.class))).thenReturn(Optional.of(topicAddress));
        when(topicAddress.getBoostrapServers(SASL_SSL)).thenReturn("kafka.example.com:9093");
        stubNoCredentials(topicAddress);
    }

    private void stubNoCredentials(TopicAddress topicAddress) {
        when(topicAddress.getCredentials(AuthType.SSL_CERT_PLUS_SCRAM.getName())).thenReturn(Optional.empty());
        when(topicAddress.getCredentials(AuthType.SCRAM.getName())).thenReturn(Optional.empty());
        when(topicAddress.getCredentials(AuthType.SSL_CERT_PLUS_PLAIN.getName())).thenReturn(Optional.empty());
        when(topicAddress.getCredentials(AuthType.PLAIN.getName())).thenReturn(Optional.empty());
        when(topicAddress.getCredentials(AuthType.SSL_CERT.getName())).thenReturn(Optional.empty());
    }

    private static TopicAddress kafkaTopicWithName() {
        TopicAddress topicAddress = mock(TopicAddress.class);
        when(topicAddress.getTopicName()).thenReturn("orders-topic");
        return topicAddress;
    }

    private static TopicAddress plainKafkaTopic() {
        return mock(TopicAddress.class);
    }

    private static TopicUserCredentials kafkaCredentials() {
        TopicUserCredentials credentials = mock(TopicUserCredentials.class);
        when(credentials.getUsername()).thenReturn("kafka-user");
        when(credentials.getPassword()).thenReturn("kafka-password");
        return credentials;
    }

    private static VHost rabbitVHost(String connectionString) {
        VHost vHost = mock(VHost.class);
        when(vHost.getCnn()).thenReturn(connectionString);
        when(vHost.getUsername()).thenReturn("rabbit-user");
        when(vHost.getPassword()).thenReturn("rabbit-password");
        return vHost;
    }

    private static VHost rabbitVHostWithConnectionStringOnly() {
        VHost vHost = mock(VHost.class);
        when(vHost.getCnn()).thenReturn("not valid uri");
        return vHost;
    }

    private static String placeholder(String elementId, String paramName) {
        return getMaasParamPlaceholder(elementId, paramName);
    }
}
