package org.qubership.integration.platform.engine.configuration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class TruststoreConfigurationTest {

    private static final String JAVA_HOME_PROPERTY = "java.home";
    private static final String TRUSTSTORE_PROPERTY = "javax.net.ssl.trustStore";
    private static final String TRUSTSTORE_PASSWORD_PROPERTY = "javax.net.ssl.trustStorePassword";

    private static final String CERTIFICATE_PEM = """
            -----BEGIN CERTIFICATE-----
            MIIC/zCCAeegAwIBAgIUAZx+h2xitktRCo4qdJJBiqDsxBMwDQYJKoZIhvcNAQEL
            BQAwDzENMAsGA1UEAwwEdGVzdDAeFw0yNjA0MTYxMTQ1NTNaFw0yNjA0MTcxMTQ1
            NTNaMA8xDTALBgNVBAMMBHRlc3QwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAwggEK
            AoIBAQCZ1zUo4wiB03ML+8SFMvr4zW80x4IHk3+9B42d8yY6cWMb98hLHQ2VA36H
            PMqmL40r9xvbUn/94/7T3aP7CPR6n0v1W0bswFjfmUBbK2tPyC+u8qEyIJcGfQ1W
            oIGKZYcl5J4FshL4sN77qDcX8TBemB53U2zccS4k71+0gt7gig7zKzHO65u2KF0N
            ZeoTUNkD4hGKx40jweNr8HbbB8/aFEUstqFV1h9pMd46D/VmlKAKoHpdsepOEGdT
            Dj39G31MjxftN8+1lpPmIGQrBTLn7TgB4FgTm9tbF2KSLQVs84LeSP0aQ+SSUL5r
            zkttrcJcm70vlMWG1hacbtE5CPk/AgMBAAGjUzBRMB0GA1UdDgQWBBRQwhzUMekc
            c7SiQBCkLAAHGNYydDAfBgNVHSMEGDAWgBRQwhzUMekcc7SiQBCkLAAHGNYydDAP
            BgNVHRMBAf8EBTADAQH/MA0GCSqGSIb3DQEBCwUAA4IBAQBmlkYdTAkqRBz6O3Xu
            Qu/Mqh0h88WeK93bfykSy4dakHdDXHWQMaJj8rgxjKx5Fzis8zUW4CuepFkN5KhT
            DtfVDL0JtvaDYEAenQASm8dIQV2GBUuSLGo+Ji+MLbAuAIdnv18sPaKWQtbjGf/p
            cKX04oP9oKeo2H7gl2QRD4cJVpMW9olXpaNwJyb+Tt6MXmNPGlrr1nACIVvCNRms
            yIly78KtacpewnzBPt9YI4lv0S+Cfy88sZRHSxZQLPgnOI6/k9mspIfR6rR+38yE
            5BTUd+1PGsSyty+BPEtrhsVa12XicSKM8XtY9JNjIKHCexgSNioSArLvLtZF3EHN
            BZRo
            -----END CERTIFICATE-----
            """;

    private final TruststoreConfiguration configuration = new TruststoreConfiguration();

    private String originalTrustStore;
    private String originalTrustStorePassword;
    private String originalJavaHome;

    @BeforeEach
    void setUp() {
        originalTrustStore = System.getProperty(TRUSTSTORE_PROPERTY);
        originalTrustStorePassword = System.getProperty(TRUSTSTORE_PASSWORD_PROPERTY);
        originalJavaHome = System.getProperty(JAVA_HOME_PROPERTY);
    }

    @AfterEach
    void tearDown() {
        restoreSystemProperty(TRUSTSTORE_PROPERTY, originalTrustStore);
        restoreSystemProperty(TRUSTSTORE_PASSWORD_PROPERTY, originalTrustStorePassword);
        restoreSystemProperty(JAVA_HOME_PROPERTY, originalJavaHome);
    }

    @Test
    void shouldBuildTruststoreAndImportCertificatesWhenCertificateFilesExist(@TempDir Path tempDir) throws Exception {
        Path sourceTruststore = createEmptyKeyStore(tempDir.resolve("source.jks"), "source-password");
        Path certsDir = Files.createDirectories(tempDir.resolve("certs"));
        Path outputTruststore = tempDir.resolve("result").resolve("truststore.jks");

        Files.writeString(certsDir.resolve("trusted.crt"), CERTIFICATE_PEM);

        System.setProperty(TRUSTSTORE_PROPERTY, sourceTruststore.toString());
        System.setProperty(TRUSTSTORE_PASSWORD_PROPERTY, "source-password");

        configuration.storeFilePath = outputTruststore.toString();
        configuration.storePassword = Optional.of("target-password");
        configuration.certsLocation = certsDir.toString();

        configuration.buildTruststore();

        assertTrue(Files.exists(outputTruststore));

        KeyStore result = loadKeyStore(outputTruststore, "target-password");
        assertTrue(result.containsAlias("trusted.crt"));
    }

    @Test
    void shouldBuildTruststoreWhenCertificatesFolderDoesNotExist(@TempDir Path tempDir) throws Exception {
        Path sourceTruststore = createEmptyKeyStore(tempDir.resolve("source.jks"), "source-password");
        Path outputTruststore = tempDir.resolve("result").resolve("truststore.jks");

        System.setProperty(TRUSTSTORE_PROPERTY, sourceTruststore.toString());
        System.setProperty(TRUSTSTORE_PASSWORD_PROPERTY, "source-password");

        configuration.storeFilePath = outputTruststore.toString();
        configuration.storePassword = Optional.empty();
        configuration.certsLocation = tempDir.resolve("missing-certs").toString();

        configuration.buildTruststore();

        assertTrue(Files.exists(outputTruststore));

        KeyStore result = loadKeyStore(outputTruststore, "");
        assertEquals(0, result.size());
    }

    @Test
    void shouldBuildTruststoreUsingJavaHomeDefaultStoreWhenExplicitTruststorePropertiesAreAbsent(@TempDir Path tempDir)
            throws Exception {
        Path fakeJavaHome = tempDir.resolve("fake-java-home");
        Path securityDir = Files.createDirectories(fakeJavaHome.resolve("lib").resolve("security"));
        Path defaultTruststore = securityDir.resolve("cacerts");
        Path outputTruststore = tempDir.resolve("result").resolve("truststore.jks");

        createEmptyKeyStore(defaultTruststore, "changeit");

        System.clearProperty(TRUSTSTORE_PROPERTY);
        System.clearProperty(TRUSTSTORE_PASSWORD_PROPERTY);
        System.setProperty(JAVA_HOME_PROPERTY, fakeJavaHome.toString());

        configuration.storeFilePath = outputTruststore.toString();
        configuration.storePassword = Optional.of("target-password");
        configuration.certsLocation = tempDir.resolve("missing-certs").toString();

        configuration.buildTruststore();

        assertTrue(Files.exists(outputTruststore));

        KeyStore result = loadKeyStore(outputTruststore, "target-password");
        assertEquals(0, result.size());
    }

    @Test
    void shouldIgnoreInvalidCertificateFilesAndStillSaveTruststore(@TempDir Path tempDir) throws Exception {
        Path sourceTruststore = createEmptyKeyStore(tempDir.resolve("source.jks"), "source-password");
        Path certsDir = Files.createDirectories(tempDir.resolve("certs"));
        Path outputTruststore = tempDir.resolve("result").resolve("truststore.jks");

        Files.writeString(certsDir.resolve("broken.crt"), "not a certificate");

        System.setProperty(TRUSTSTORE_PROPERTY, sourceTruststore.toString());
        System.setProperty(TRUSTSTORE_PASSWORD_PROPERTY, "source-password");

        configuration.storeFilePath = outputTruststore.toString();
        configuration.storePassword = Optional.of("target-password");
        configuration.certsLocation = certsDir.toString();

        configuration.buildTruststore();

        assertTrue(Files.exists(outputTruststore));

        KeyStore result = loadKeyStore(outputTruststore, "target-password");
        assertFalse(result.containsAlias("broken.crt"));
        assertEquals(0, result.size());
    }

    private static Path createEmptyKeyStore(Path path, String password) throws Exception {
        Files.createDirectories(path.getParent());

        KeyStore keyStore = KeyStore.getInstance("JKS");
        keyStore.load(null, password.toCharArray());

        try (OutputStream outputStream = Files.newOutputStream(path)) {
            keyStore.store(outputStream, password.toCharArray());
        }

        return path;
    }

    private static KeyStore loadKeyStore(Path path, String password) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("JKS");
        try (InputStream inputStream = Files.newInputStream(path)) {
            keyStore.load(inputStream, password.toCharArray());
        }
        return keyStore;
    }

    private static void restoreSystemProperty(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
