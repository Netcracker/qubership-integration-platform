package org.qubership.integration.platform.runtime.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.AutodiscoveryException;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeOperator;
import org.qubership.integration.platform.runtime.catalog.persistence.TransactionHandler;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.ConfigParameter;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.discovery.DiscoveryResultDTO;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscoveryServiceTest {

    @Mock
    private KubeOperator operator;
    @Mock
    private SystemService systemService;
    @Mock
    private ConfigParameterService configParameterService;
    @Mock
    private ActionsLogService actionLogger;
    @Mock
    private TransactionHandler transactionHandler;

    private DiscoveryService service;

    @BeforeEach
    void setUp() {
        service = new DiscoveryService(operator, systemService, null, null, null, null, null,
                new ObjectMapper(), null, null, configParameterService, actionLogger, transactionHandler);
    }

    @Test
    void resultAndCompleteProgressAreWrittenInOneTransaction() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> committed = new CompletableFuture<>();
        doAnswer(invocation -> {
            ConfigParameter written = invocation.getArgument(0);
            boolean isProgress = written.getName().equals("discoveryProgress");
            events.add(isProgress ? "progress=" + written.getString() : "result");
            return written;
        }).when(configParameterService).update(any(ConfigParameter.class));
        doAnswer(invocation -> {
            events.add("delete");
            return null;
        }).when(configParameterService).deleteByName("discovery", "discoveryProgress");
        doAnswer(invocation -> {
            events.add("begin");
            invocation.getArgument(0, Runnable.class).run();
            events.add("commit");
            if (events.contains("progress=100")) {
                committed.complete(null);
            }
            return null;
        }).when(transactionHandler).runInTransaction(any());

        service.runDiscovery();

        committed.get(5, TimeUnit.SECONDS);
        assertThat(events).containsExactly(
                "begin", "delete", "progress=0", "commit",
                "begin", "result", "progress=100", "commit");
    }

    @Test
    void failedResultWriteIsReplacedWithAnError() {
        Answer<Void> run = invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        };
        doAnswer(run).doThrow(new IllegalStateException("database is down")).doAnswer(run)
                .when(transactionHandler).runInTransaction(any());

        service.runDiscovery();

        ArgumentCaptor<ConfigParameter> writes = ArgumentCaptor.forClass(ConfigParameter.class);
        verify(configParameterService, timeout(5_000).times(3)).update(writes.capture());
        assertThat(writes.getAllValues().get(1).getString())
                .contains("\"errorMessage\":\"Unable to save the Autodiscovery result. See the runtime-catalog log for the cause.\"");
        assertThat(writes.getAllValues().get(2).getString()).isEqualTo("100");
    }

    @Test
    void abandonedRunIsReportedAsAnError() {
        when(configParameterService.findByName("discovery", "discoveryProgress")).thenReturn(progress("42", 16));

        assertThatThrownBy(() -> service.getDiscoveryResult())
                .isInstanceOf(AutodiscoveryException.class)
                .hasMessage("Autodiscovery did not complete: no progress for 15 minutes. Run it again.");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "{\"errorMessage\":\"kube API is down\"} | Error in Autodiscovery service: kube API is down",
        "null | Unknown error, no status available"
    })
    void storedErrorOrMissingStatusIsReportedAsAnError(String stored, String message) {
        when(configParameterService.findByName("discovery", "discoveryProgress")).thenReturn(null);
        when(configParameterService.findByName("discovery", "discoveryResult")).thenReturn(result(stored));

        assertThatThrownBy(() -> service.getDiscoveryResult())
                .isInstanceOf(AutodiscoveryException.class)
                .hasMessage(message);
    }

    @ParameterizedTest
    @CsvSource({"100, 16", "42, 1", "100.5, 16", ", 0"})
    void completeRunningOrNeverStartedDiscoveryReturnsTheStoredResult(String value, int minutesAgo) {
        when(configParameterService.findByName("discovery", "discoveryProgress"))
                .thenReturn(value == null ? null : progress(value, minutesAgo));
        when(configParameterService.findByName("discovery", "discoveryResult"))
                .thenReturn(result("{\"result\":{\"discoveredSystemIds\":[\"svc\"]}}"));

        DiscoveryResultDTO stored = service.getDiscoveryResult();

        assertThat(stored.getDiscoveredSystemIds()).containsExactly("svc");
    }

    private static ConfigParameter result(String value) {
        ConfigParameter result = new ConfigParameter("discovery", "discoveryResult");
        result.setString(value);
        return result;
    }

    private static ConfigParameter progress(String value, int minutesAgo) {
        ConfigParameter progress = new ConfigParameter("discovery", "discoveryProgress");
        progress.setString(value);
        progress.setModifiedWhen(Timestamp.valueOf(LocalDateTime.now().minusMinutes(minutesAgo)));
        return progress;
    }
}
