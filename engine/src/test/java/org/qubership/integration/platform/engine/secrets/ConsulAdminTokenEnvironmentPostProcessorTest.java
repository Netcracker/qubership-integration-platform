package org.qubership.integration.platform.engine.secrets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsulAdminTokenEnvironmentPostProcessorTest {

    private static final String CONSUL_TOKEN_PROPERTY = "consul.token";
    private static final String CONSUL_ACL_TOKEN_PROPERTY = "spring.cloud.consul.config.acl-token";

    @TempDir
    Path mount;

    private Path tokenFile;

    @BeforeEach
    void pointAtTheMount() {
        tokenFile = mount.resolve("consul_admin_token");
        System.setProperty(ConsulAdminTokenEnvironmentPostProcessor.TOKEN_FILE_VARIABLE, tokenFile.toString());
    }

    @AfterEach
    void forgetTheMount() {
        System.clearProperty(ConsulAdminTokenEnvironmentPostProcessor.TOKEN_FILE_VARIABLE);
        System.clearProperty(ConsulAdminTokenEnvironmentPostProcessor.TOKEN_VARIABLE);
    }

    @Test
    void readsTheTokenOfTheMountedFile() throws IOException {
        Files.writeString(tokenFile, "from-the-mount");

        assertEquals("from-the-mount", ConsulAdminTokenEnvironmentPostProcessor.readToken());
    }

    @Test
    void dropsTheWhitespaceAroundTheToken() throws IOException {
        Files.writeString(tokenFile, " from-the-mount \n");

        assertEquals("from-the-mount", ConsulAdminTokenEnvironmentPostProcessor.readToken());
    }

    @Test
    void prefersTheMountOverTheDeprecatedVariable() throws IOException {
        Files.writeString(tokenFile, "from-the-mount");
        System.setProperty(ConsulAdminTokenEnvironmentPostProcessor.TOKEN_VARIABLE, "from-the-environment");

        assertEquals("from-the-mount", ConsulAdminTokenEnvironmentPostProcessor.readToken());
    }

    @Test
    void treatsAnEmptyFileAsNoTokenAtAll() throws IOException {
        Files.writeString(tokenFile, "  \n");
        System.setProperty(ConsulAdminTokenEnvironmentPostProcessor.TOKEN_VARIABLE, "from-the-environment");

        assertEquals("from-the-environment", ConsulAdminTokenEnvironmentPostProcessor.readToken());
    }

    @Test
    void fallsBackToTheDeprecatedVariableWithoutAMount() {
        System.setProperty(ConsulAdminTokenEnvironmentPostProcessor.TOKEN_VARIABLE, "from-the-environment");

        assertEquals("from-the-environment", ConsulAdminTokenEnvironmentPostProcessor.readToken());
    }

    @Test
    void answersNullWhenNeitherSideCarriesAToken() {
        assertNull(ConsulAdminTokenEnvironmentPostProcessor.readToken());
    }

    @Test
    void seesTheRotatedContentOfTheFile() throws IOException {
        Files.writeString(tokenFile, "before-the-rotation");
        assertEquals("before-the-rotation", ConsulAdminTokenEnvironmentPostProcessor.readToken());

        Files.writeString(tokenFile, "after-the-rotation");
        assertEquals("after-the-rotation", ConsulAdminTokenEnvironmentPostProcessor.readToken());
    }

    @Test
    void feedsBothConsulPropertiesFromTheMount() throws IOException {
        Files.writeString(tokenFile, "from-the-mount");
        ConfigurableEnvironment environment = new StandardEnvironment();

        postProcess(environment);

        assertEquals("from-the-mount", environment.getProperty(CONSUL_TOKEN_PROPERTY));
        assertEquals("from-the-mount", environment.getProperty(CONSUL_ACL_TOKEN_PROPERTY));
    }

    @Test
    void leavesThePropertiesAloneWithoutAToken() {
        ConfigurableEnvironment environment = new StandardEnvironment();

        postProcess(environment);

        assertNull(environment.getProperty(CONSUL_TOKEN_PROPERTY));
        assertNull(environment.getProperty(CONSUL_ACL_TOKEN_PROPERTY));
    }

    @Test
    void runsBeforeTheConfigurationDataImport() {
        assertTrue(new ConsulAdminTokenEnvironmentPostProcessor().getOrder()
                < ConfigDataEnvironmentPostProcessor.ORDER);
    }

    private void postProcess(ConfigurableEnvironment environment) {
        new ConsulAdminTokenEnvironmentPostProcessor()
                .postProcessEnvironment(environment, new SpringApplication());
    }
}
