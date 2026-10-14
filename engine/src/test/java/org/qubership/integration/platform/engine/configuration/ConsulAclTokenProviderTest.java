package org.qubership.integration.platform.engine.configuration;

import com.ecwid.consul.v1.ConsulClient;
import com.ecwid.consul.v1.QueryParams;
import com.ecwid.consul.v1.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.engine.secrets.ConsulAdminTokenEnvironmentPostProcessor;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.consul.config.ConfigWatch;
import org.springframework.cloud.consul.config.ConsulConfigProperties;
import org.springframework.scheduling.TaskScheduler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsulAclTokenProviderTest {

    private static final String CONTEXT = "config/application/";

    @TempDir
    Path mount;

    @AfterEach
    void forgetTheMount() {
        System.clearProperty(ConsulAdminTokenEnvironmentPostProcessor.TOKEN_FILE_VARIABLE);
        System.clearProperty(ConsulAdminTokenEnvironmentPostProcessor.TOKEN_VARIABLE);
    }

    @Test
    void isThePrimaryConsulConfigPropertiesBean() {
        new ApplicationContextRunner()
                .withUserConfiguration(ConsulAclTokenProvider.class)
                .withBean("defaultConsulConfigProperties", ConsulConfigProperties.class)
                .run(context -> assertSame(
                        context.getBean(ConsulAclTokenProvider.class),
                        context.getBean(ConsulConfigProperties.class)));
    }

    @Test
    void configWatchUsesTheRotatedToken() throws IOException {
        Path tokenFile = mount.resolve("consul_admin_token");
        System.setProperty(ConsulAdminTokenEnvironmentPostProcessor.TOKEN_FILE_VARIABLE, tokenFile.toString());
        Files.writeString(tokenFile, "before-the-rotation");

        ConsulAclTokenProvider properties = new ConsulAclTokenProvider();
        ConsulClient consul = mock(ConsulClient.class);
        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> watchFuture = mock(ScheduledFuture.class);
        doReturn(watchFuture).when(taskScheduler).scheduleWithFixedDelay(any(Runnable.class), anyLong());
        when(consul.getKVValues(eq(CONTEXT), any(), any(QueryParams.class)))
                .thenReturn(new Response<>(List.of(), null, null, null));

        ConfigWatch configWatch = new ConfigWatch(
                properties,
                consul,
                new LinkedHashMap<>(Map.of(CONTEXT, -1L)),
                taskScheduler);
        configWatch.start();

        try {
            configWatch.watchConfigKeyValues();
            Files.writeString(tokenFile, "after-the-rotation");
            configWatch.watchConfigKeyValues();
        } finally {
            configWatch.stop();
        }

        ArgumentCaptor<String> tokens = ArgumentCaptor.forClass(String.class);
        verify(consul, times(2)).getKVValues(eq(CONTEXT), tokens.capture(), any(QueryParams.class));
        assertEquals(List.of("before-the-rotation", "after-the-rotation"), tokens.getAllValues());
    }
}
