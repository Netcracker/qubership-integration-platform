package org.qubership.integration.platform.engine.routes.fixture;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.acl.AccessControlEntry;
import org.apache.kafka.common.acl.AccessControlEntryFilter;
import org.apache.kafka.common.acl.AclBinding;
import org.apache.kafka.common.acl.AclBindingFilter;
import org.apache.kafka.common.acl.AclOperation;
import org.apache.kafka.common.acl.AclPermissionType;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.resource.PatternType;
import org.apache.kafka.common.resource.ResourcePattern;
import org.apache.kafka.common.resource.ResourceType;
import org.testcontainers.kafka.KafkaContainer;

import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class KafkaSaslSupport {
    static final String ADMIN_USERNAME = "admin";
    private static final long ADMIN_TIMEOUT_SECONDS = 10;
    private static final String ADMIN_JAAS =
            "org.apache.kafka.common.security.plain.PlainLoginModule required "
                    + "username=\"admin\" password=\"admin-password\";";
    private static final String BROKER_JAAS =
            "org.apache.kafka.common.security.plain.PlainLoginModule required "
                    + "username=\"admin\" password=\"admin-password\" "
                    + "user_admin=\"admin-password\" user_writer=\"writer-password\" "
                    + "user_reader=\"reader-password\";";

    private KafkaSaslSupport() {
    }

    static void configure(KafkaContainer container) {
        container.withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP",
                        "BROKER:PLAINTEXT,PLAINTEXT:SASL_PLAINTEXT,CONTROLLER:PLAINTEXT")
                .withEnv("KAFKA_SASL_ENABLED_MECHANISMS", "PLAIN")
                .withEnv("KAFKA_AUTHORIZER_CLASS_NAME", "org.apache.kafka.metadata.authorizer.StandardAuthorizer")
                .withEnv("KAFKA_ALLOW_EVERYONE_IF_NO_ACL_FOUND", "false")
                // Internal broker and controller listeners use anonymous principals.
                .withEnv("KAFKA_SUPER_USERS", "User:admin;User:ANONYMOUS");
        configureListener(container, "PLAINTEXT");
    }

    static void configureListener(KafkaContainer container, String listenerName) {
        container.withEnv("KAFKA_LISTENER_NAME_" + listenerName + "_PLAIN_SASL_JAAS_CONFIG", BROKER_JAAS);
    }

    static Properties adminProperties(String bootstrapServers) {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_PLAINTEXT");
        properties.put(SaslConfigs.SASL_MECHANISM, "PLAIN");
        properties.put(SaslConfigs.SASL_JAAS_CONFIG, ADMIN_JAAS);
        properties.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, (int) TimeUnit.SECONDS.toMillis(ADMIN_TIMEOUT_SECONDS));
        properties.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, (int) TimeUnit.SECONDS.toMillis(ADMIN_TIMEOUT_SECONDS));
        return properties;
    }

    static void applyTopicAcl(Admin admin, String topic, String username, boolean allowWrite) throws Exception {
        ResourcePattern resource = new ResourcePattern(ResourceType.TOPIC, topic, PatternType.LITERAL);
        String principal = "User:" + username;
        AclBinding describe = new AclBinding(resource,
                new AccessControlEntry(principal, "*", AclOperation.DESCRIBE, AclPermissionType.ALLOW));
        AclBinding write = new AclBinding(resource,
                new AccessControlEntry(principal, "*", AclOperation.WRITE, AclPermissionType.ALLOW));
        admin.createAcls(allowWrite ? List.of(describe, write) : List.of(describe))
                .all().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!allowWrite) {
            admin.deleteAcls(List.of(write.toFilter())).all().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }

        AclBindingFilter filter = new AclBindingFilter(resource.toFilter(),
                new AccessControlEntryFilter(principal, "*", AclOperation.ANY, AclPermissionType.ANY));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ADMIN_TIMEOUT_SECONDS);
        Collection<AclBinding> observed;
        do {
            // Describe reads the broker's authorizer after the controller processes the mutation.
            observed = admin.describeAcls(filter).values().get(ADMIN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (observed.contains(describe) && observed.contains(write) == allowWrite) {
                return;
            }
        } while (System.nanoTime() < deadline);
        assertTrue(observed.contains(describe), "Kafka did not apply Describe permission for " + principal + '.');
        assertEquals(allowWrite, observed.contains(write), "Kafka did not apply Write permission for " + principal + '.');
    }
}
