package org.qubership.integration.platform.engine.maas;

import com.netcracker.cloud.maas.client.api.kafka.TopicAddress;
import com.netcracker.cloud.maas.client.api.kafka.TopicUserCredentials;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.maas.kafka.AuthType;
import org.qubership.integration.platform.engine.maas.kafka.Protocol;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class MaasUtilsTest {

    @ParameterizedTest
    @MethodSource("plainAuthTypes")
    void shouldBuildPlainJaasConfigWhenAuthTypeIsPlain(AuthType authType) {
        TopicUserCredentials credentials = credentials("plain-user", "plain-password");

        String result = MaasUtils.buildJaasConfig(credentials, authType);

        assertEquals(
            "org.apache.kafka.common.security.plain.PlainLoginModule required "
                + "username=\"plain-user\" password=\"plain-password\";",
            result
        );
    }

    @ParameterizedTest
    @MethodSource("scramAuthTypes")
    void shouldBuildScramJaasConfigWhenAuthTypeIsScram(AuthType authType) {
        TopicUserCredentials credentials = credentials("scram-user", "scram-password");

        String result = MaasUtils.buildJaasConfig(credentials, authType);

        assertEquals(
            "org.apache.kafka.common.security.scram.ScramLoginModule required "
                + "username=\"scram-user\" password=\"scram-password\";",
            result
        );
    }

    @Test
    void shouldReturnNullJaasConfigWhenAuthTypeDoesNotRequireJaasConfig() {
        TopicUserCredentials credentials = mock(TopicUserCredentials.class);

        String result = MaasUtils.buildJaasConfig(credentials, AuthType.SSL_CERT);

        assertNull(result);
    }

    @ParameterizedTest
    @MethodSource("plainAuthTypes")
    void shouldConvertPlainAuthTypeToPlainSaslMechanism(AuthType authType) {
        String result = MaasUtils.convertToSaslMechanism(authType);

        assertEquals("PLAIN", result);
    }

    @ParameterizedTest
    @MethodSource("scramAuthTypes")
    void shouldConvertScramAuthTypeToScramSaslMechanism(AuthType authType) {
        String result = MaasUtils.convertToSaslMechanism(authType);

        assertEquals("SCRAM-SHA-512", result);
    }

    @Test
    void shouldReturnNullSaslMechanismWhenAuthTypeDoesNotUseSasl() {
        String result = MaasUtils.convertToSaslMechanism(AuthType.SSL_CERT);

        assertNull(result);
    }

    @Test
    void shouldSelectSaslSslProtocolWhenItHasBootstrapServers() {
        TopicAddress topicAddress = mock(TopicAddress.class);
        when(topicAddress.getBoostrapServers(Protocol.SASL_SSL.toString())).thenReturn("kafka.example.com:9093");

        String result = MaasUtils.selectProtocol(topicAddress);

        assertEquals(Protocol.SASL_SSL.toString(), result);
    }

    @Test
    void shouldSelectFirstAvailableProtocolByPriority() {
        TopicAddress topicAddress = mock(TopicAddress.class);

        when(topicAddress.getBoostrapServers(Protocol.SASL_SSL.toString())).thenReturn(null);
        when(topicAddress.getBoostrapServers(Protocol.SASL_PLAINTEXT.toString()))
            .thenReturn("kafka.example.com:9092");

        String result = MaasUtils.selectProtocol(topicAddress);

        assertEquals(Protocol.SASL_PLAINTEXT.toString(), result);
    }

    @Test
    void shouldThrowMaasExceptionWhenNoSupportedProtocolHasBootstrapServers() {
        TopicAddress topicAddress = mock(TopicAddress.class);

        MaasException exception = assertThrows(MaasException.class, () -> MaasUtils.selectProtocol(topicAddress));

        assertEquals(
            "Can't find supported protocol types in MaaS response. Supported types: "
                + "[SASL_SSL, SASL_PLAINTEXT, PLAINTEXT, SSL]",
            exception.getMessage()
        );
    }

    @Test
    void shouldSelectFirstAvailableCredentialTypeByPriority() {
        TopicAddress topicAddress = mock(TopicAddress.class);
        TopicUserCredentials credentials = mock(TopicUserCredentials.class);

        when(topicAddress.getCredentials(AuthType.SSL_CERT_PLUS_SCRAM.getName())).thenReturn(Optional.empty());
        when(topicAddress.getCredentials(AuthType.SCRAM.getName())).thenReturn(Optional.of(credentials));

        Optional<AuthType> result = MaasUtils.selectCredType(topicAddress);

        assertEquals(Optional.of(AuthType.SCRAM), result);
    }

    @Test
    void shouldReturnEmptyCredentialTypeWhenCredentialsAreMissing() {
        TopicAddress topicAddress = mock(TopicAddress.class);

        when(topicAddress.getCredentials(AuthType.SSL_CERT_PLUS_SCRAM.getName())).thenReturn(Optional.empty());
        when(topicAddress.getCredentials(AuthType.SCRAM.getName())).thenReturn(Optional.empty());
        when(topicAddress.getCredentials(AuthType.SSL_CERT_PLUS_PLAIN.getName())).thenReturn(Optional.empty());
        when(topicAddress.getCredentials(AuthType.PLAIN.getName())).thenReturn(Optional.empty());
        when(topicAddress.getCredentials(AuthType.SSL_CERT.getName())).thenReturn(Optional.empty());

        Optional<AuthType> result = MaasUtils.selectCredType(topicAddress);

        assertEquals(Optional.empty(), result);
    }

    @Test
    void shouldMergeMapsAndOverrideValuesFromSecondMap() {
        Map<String, Object> firstMap = Map.of(
            "first", "first-value",
            "shared", "old-value"
        );
        Map<String, Object> secondMap = Map.of(
            "second", "second-value",
            "shared", "new-value"
        );

        Map<String, Object> result = MaasUtils.merge(firstMap, secondMap);

        assertEquals(3, result.size());
        assertEquals("first-value", result.get("first"));
        assertEquals("second-value", result.get("second"));
        assertEquals("new-value", result.get("shared"));
    }

    @Test
    void shouldCreateMaasParameterPlaceholder() {
        String result = MaasUtils.getMaasParamPlaceholder("element-id", "brokers");

        assertEquals("%%{element-id_brokers}", result);
    }

    private static Stream<Arguments> plainAuthTypes() {
        return Stream.of(
            Arguments.of(AuthType.PLAIN),
            Arguments.of(AuthType.SSL_CERT_PLUS_PLAIN)
        );
    }

    private static Stream<Arguments> scramAuthTypes() {
        return Stream.of(
            Arguments.of(AuthType.SCRAM),
            Arguments.of(AuthType.SSL_CERT_PLUS_SCRAM)
        );
    }

    private static TopicUserCredentials credentials(String username, String password) {
        TopicUserCredentials credentials = mock(TopicUserCredentials.class);
        when(credentials.getUsername()).thenReturn(username);
        when(credentials.getPassword()).thenReturn(password);
        return credentials;
    }
}
