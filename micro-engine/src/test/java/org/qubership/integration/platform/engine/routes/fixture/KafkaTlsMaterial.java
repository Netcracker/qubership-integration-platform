package org.qubership.integration.platform.engine.routes.fixture;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Uses public test keys and a dedicated CA without changing JVM trust settings. */
final class KafkaTlsMaterial implements AutoCloseable {
    private static final String RESOURCE_DIRECTORY = "/snapshot-fixtures/kafka/";
    private static final String STORE_TYPE = "PKCS12";
    private static final String STORE_PASSWORD = "kafka-fixture-password";

    private final Path temporaryDirectory;
    private final Path truststoreFile;
    private final Path brokerKeyStoreFile;
    private final KeyStore truststore;
    private final Map<String, KeyStore> serverKeyStores;

    private KafkaTlsMaterial(Path temporaryDirectory, KeyStore truststore, Map<String, KeyStore> serverKeyStores) {
        this.temporaryDirectory = temporaryDirectory;
        this.truststoreFile = temporaryDirectory.resolve("truststore.p12");
        this.brokerKeyStoreFile = temporaryDirectory.resolve("broker.p12");
        this.truststore = truststore;
        this.serverKeyStores = serverKeyStores;
    }

    static KafkaTlsMaterial create() throws GeneralSecurityException, IOException {
        Certificate trustedCa = readCertificate("ca-cert.pem");
        Certificate untrustedCa = readCertificate("untrusted-ca-cert.pem");
        Map<String, KeyStore> keyStores = Map.of(
                "valid", createServerKeyStore("valid", trustedCa),
                "rotated", createServerKeyStore("rotated", trustedCa),
                "untrusted", createServerKeyStore("untrusted", untrustedCa),
                "expired", createServerKeyStore("expired", trustedCa),
                "wrong-hostname", createServerKeyStore("wrong-hostname", trustedCa));
        KeyStore truststore = KeyStore.getInstance(STORE_TYPE);
        truststore.load(null, null);
        truststore.setCertificateEntry("kafka-fixture-ca", trustedCa);

        KafkaTlsMaterial material = new KafkaTlsMaterial(Files.createTempDirectory("kafka-tls-fixture-"),
                truststore, keyStores);
        try {
            writeKeyStore(truststore, material.truststoreFile);
            writeKeyStore(keyStores.get("valid"), material.brokerKeyStoreFile);
        } catch (GeneralSecurityException | IOException exception) {
            try {
                material.close();
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
        return material;
    }

    SSLContext serverContext(String profile) throws GeneralSecurityException {
        KeyStore keyStore = serverKeyStores.get(profile);
        if (keyStore == null) {
            throw new IllegalArgumentException("Unsupported Kafka TLS certificate profile: " + profile);
        }
        KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagers.init(keyStore, STORE_PASSWORD.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagers.getKeyManagers(), null, null);
        return context;
    }

    SSLContext clientContext() throws GeneralSecurityException {
        TrustManagerFactory trustManagers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagers.init(truststore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trustManagers.getTrustManagers(), null);
        return context;
    }

    Path brokerKeyStore() {
        return brokerKeyStoreFile;
    }

    String keyStorePassword() {
        return STORE_PASSWORD;
    }

    Properties senderProperties() {
        Properties properties = new Properties();
        properties.setProperty("ssl.truststore.location", truststoreFile.toString());
        properties.setProperty("ssl.truststore.type", STORE_TYPE);
        properties.setProperty("ssl.truststore.password", STORE_PASSWORD);
        return properties;
    }

    Map<String, String> endpointOptions() {
        return Map.of(
                "sslTruststoreLocation", truststoreFile.toString(),
                "sslTruststoreType", STORE_TYPE,
                "sslTruststorePassword", STORE_PASSWORD);
    }

    @Override
    public void close() throws IOException {
        Files.deleteIfExists(truststoreFile);
        Files.deleteIfExists(brokerKeyStoreFile);
        Files.deleteIfExists(temporaryDirectory);
    }

    private static KeyStore createServerKeyStore(String profile, Certificate ca)
            throws GeneralSecurityException, IOException {
        KeyStore keyStore = KeyStore.getInstance(STORE_TYPE);
        keyStore.load(null, null);
        keyStore.setKeyEntry("kafka-fixture-server", readPrivateKey(profile + "-key.pem"),
                STORE_PASSWORD.toCharArray(), new Certificate[] {readCertificate(profile + "-cert.pem"), ca});
        return keyStore;
    }

    private static void writeKeyStore(KeyStore keyStore, Path path) throws GeneralSecurityException, IOException {
        try (OutputStream output = Files.newOutputStream(path)) {
            keyStore.store(output, STORE_PASSWORD.toCharArray());
        }
    }

    private static Certificate readCertificate(String resource) throws GeneralSecurityException, IOException {
        try (InputStream input = openResource(resource)) {
            return CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
    }

    private static PrivateKey readPrivateKey(String resource) throws GeneralSecurityException, IOException {
        String pem;
        try (InputStream input = openResource(resource)) {
            pem = new String(input.readAllBytes(), StandardCharsets.US_ASCII);
        }
        String encoded = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
    }

    private static InputStream openResource(String resource) throws IOException {
        InputStream input = KafkaTlsMaterial.class.getResourceAsStream(RESOURCE_DIRECTORY + resource);
        if (input == null) {
            throw new IOException("Kafka TLS fixture resource is missing: " + resource);
        }
        return input;
    }
}
