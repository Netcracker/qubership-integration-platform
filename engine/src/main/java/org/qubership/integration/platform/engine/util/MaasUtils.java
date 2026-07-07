package org.qubership.integration.platform.engine.util;

import com.netcracker.cloud.maas.client.api.kafka.TopicAddress;
import com.netcracker.cloud.maas.client.api.kafka.TopicUserCredentials;
import org.qubership.integration.platform.engine.cloudcore.maas.MaasException;
import org.qubership.integration.platform.engine.model.maas.kafka.AuthType;
import org.qubership.integration.platform.engine.model.maas.kafka.Protocol;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public class MaasUtils {

    public static final String JAAS_CONFIG_TEMPLATE_PLAIN =
        "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"{username}\" password=\"{password}\";";
    public static final String JAAS_CONFIG_TEMPLATE_SCRAM =
        "org.apache.kafka.common.security.scram.ScramLoginModule required username=\"{username}\" password=\"{password}\";";

    private static final Protocol[] PROTOCOLS_PRIORITY_LIST = {
            Protocol.SASL_SSL,
            Protocol.SASL_PLAINTEXT,
            Protocol.PLAINTEXT,
            Protocol.SSL
    };

    private static final AuthType[] CRED_TYPE_PRIORITY_LIST = {
            AuthType.SSL_CERT_PLUS_SCRAM,
            AuthType.SCRAM,
            AuthType.SSL_CERT_PLUS_PLAIN,
            AuthType.PLAIN,
            AuthType.SSL_CERT
    };

    public static String buildJaasConfig(TopicUserCredentials auth, AuthType authType) {
        if (auth == null || authType == null) {
            return null;
        }

        return switch (authType) {
            case PLAIN, SSL_CERT_PLUS_PLAIN -> JAAS_CONFIG_TEMPLATE_PLAIN
                .replace("{username}", auth.getUsername())
                .replace("{password}", auth.getPassword());
            case SCRAM, SSL_CERT_PLUS_SCRAM -> JAAS_CONFIG_TEMPLATE_SCRAM
                .replace("{username}", auth.getUsername())
                .replace("{password}", auth.getPassword());
            default -> null;
        };
    }

    public static String convertToSaslMechanism(AuthType authType) {
        if (authType == null) {
            return null;
        }

        return switch (authType) {
            case SSL_CERT_PLUS_PLAIN, PLAIN -> "PLAIN";
            case SSL_CERT_PLUS_SCRAM, SCRAM -> "SCRAM-SHA-512";
            default -> null;
        };
    }

    public static String selectProtocol(TopicAddress kafkaTopic) {
        for (Protocol protocol : PROTOCOLS_PRIORITY_LIST) {
            String servers = kafkaTopic.getBoostrapServers(protocol.toString());
            if (servers != null) {
                return protocol.toString();
            }
        }
        throw new MaasException("Can't find supported protocol types in MaaS response. Supported types: "
                + Arrays.toString(PROTOCOLS_PRIORITY_LIST));
    }

    /**
     *
     * @return empty optional - no auth
     */
    public static Optional<AuthType> selectCredType(TopicAddress kafkaTopic) {
        for (AuthType auth : CRED_TYPE_PRIORITY_LIST) {
            Optional<TopicUserCredentials> credentials = kafkaTopic.getCredentials(auth.getName());
            if (credentials.isPresent()) {
                return Optional.of(auth);
            }
        }
        return Optional.empty();
    }

    public static Map<String, Object> merge(Map<String, Object> mapA, Map<String, Object> mapB) {
        Map<String, Object> mergedMap = new HashMap<>();
        mergedMap.putAll(mapA);
        mergedMap.putAll(mapB);
        return mergedMap;
    }

    public static String getMaasParamPlaceholder(String elementId, String paramName) {
        return String.format("%%%%{%s_%s}", elementId, paramName);
    }
}
