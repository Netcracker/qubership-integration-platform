package org.qubership.integration.platform.engine.camel.components.sftp;

import org.apache.camel.Exchange;
import org.apache.camel.Service;
import org.apache.camel.component.file.GenericFileOperationFailedException;
import org.apache.camel.component.file.GenericFileProcessStrategy;
import org.apache.camel.component.file.remote.RemoteFile;
import org.apache.camel.component.file.remote.RemoteFileOperations;
import org.apache.camel.component.file.remote.SftpConfiguration;
import org.apache.camel.component.file.remote.SftpConsumer;
import org.apache.camel.component.file.remote.SftpEndpoint;
import org.apache.camel.component.file.remote.SftpRemoteFile;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.spi.ExceptionHandler;
import org.apache.camel.spi.IdempotentRepository;
import org.apache.camel.support.DefaultExchange;
import org.apache.camel.support.processor.idempotent.MemoryIdempotentRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class SftpConsumerLifecycleStrategyTest {

    private final SftpConsumerLifecycleStrategy strategy = new SftpConsumerLifecycleStrategy();

    @Mock
    private ExceptionHandler originalHandler;
    @Mock
    private RemoteFileOperations<SftpRemoteFile> operations;
    @Mock
    private GenericFileProcessStrategy<SftpRemoteFile> processStrategy;

    private DefaultCamelContext context;
    private SftpEndpoint endpoint;
    private SftpConsumer consumer;
    private IdempotentRepository repository;
    private RemoteFile<SftpRemoteFile> file;
    private Exchange exchange;

    @BeforeEach
    void setUp() {
        context = new DefaultCamelContext();
        endpoint = new SftpEndpoint();
        endpoint.setCamelContext(context);
        SftpConfiguration configuration = new SftpConfiguration();
        configuration.setDirectory("upload");
        endpoint.setConfiguration(configuration);
        endpoint.setIdempotent(true);
        endpoint.setIdempotentEager(true);
        repository = MemoryIdempotentRepository.memoryIdempotentRepository(100);
        endpoint.setIdempotentRepository(repository);
        consumer = new SftpConsumer(endpoint, ignored -> { }, operations, processStrategy);
        consumer.setExceptionHandler(originalHandler);
        file = new RemoteFile<>();
        file.setAbsoluteFilePath("/upload/report.txt");
        file.setRelativeFilePath("report.txt");
        file.setFileName("report.txt");
        file.setFileLength(25);
        exchange = endpoint.createExchange(file);
        strategy.onServiceAdd(context, consumer, null);
    }

    @AfterEach
    void tearDown() throws Exception {
        context.close();
    }

    @Test
    void shouldReleaseDefaultKeyWhenDownloadFails() {
        repository.add(file.getAbsoluteFilePath());
        repository.add("other-file");
        GenericFileOperationFailedException failure = new GenericFileOperationFailedException("Cannot retrieve file");

        consumer.getExceptionHandler().handleException("download failed", exchange, failure);

        assertFalse(repository.contains(file.getAbsoluteFilePath()));
        assertTrue(repository.contains("other-file"));
        verify(originalHandler).handleException("download failed", exchange, failure);
    }

    @Test
    void shouldEvaluateCustomKeyFromFileHeadersWhenDownloadFails() {
        endpoint.setIdempotentKey(context.resolveLanguage("simple").createExpression("${file:onlyname}-${file:size}"));
        repository.add("report.txt-25");
        repository.add(file.getAbsoluteFilePath());
        GenericFileOperationFailedException failure = new GenericFileOperationFailedException("Permission denied");

        consumer.getExceptionHandler().handleException("download failed", exchange, failure);

        assertFalse(repository.contains("report.txt-25"));
        assertTrue(repository.contains(file.getAbsoluteFilePath()));
        verify(originalHandler).handleException("download failed", exchange, failure);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldKeepReservationForNativeCompletionWhenBodyWasDownloaded(boolean streamed) {
        file.setBody(streamed ? new ByteArrayInputStream(new byte[0]) : new byte[0]);
        repository.add(file.getAbsoluteFilePath());
        GenericFileOperationFailedException failure = new GenericFileOperationFailedException("Route failed");

        consumer.getExceptionHandler().handleException("processing failed", exchange, failure);

        assertTrue(repository.contains(file.getAbsoluteFilePath()));
        verify(originalHandler).handleException("processing failed", exchange, failure);
    }

    @ParameterizedTest
    @CsvSource({"false,true", "true,false"})
    void shouldKeepKeysWhenEagerIdempotencyIsDisabled(boolean idempotent, boolean eager) {
        endpoint.setIdempotent(idempotent);
        endpoint.setIdempotentEager(eager);
        repository.add(file.getAbsoluteFilePath());
        GenericFileOperationFailedException failure = new GenericFileOperationFailedException("Cannot retrieve file");

        consumer.getExceptionHandler().handleException("download failed", exchange, failure);

        assertTrue(repository.contains(file.getAbsoluteFilePath()));
        verify(originalHandler).handleException("download failed", exchange, failure);
    }

    @Test
    void shouldKeepReservationWhenConsumerOnlyRetrievesMetadata() {
        consumer.setRetrieveFile(false);
        repository.add(file.getAbsoluteFilePath());
        GenericFileOperationFailedException failure = new GenericFileOperationFailedException("Route failed");

        consumer.getExceptionHandler().handleException("processing failed", exchange, failure);

        assertTrue(repository.contains(file.getAbsoluteFilePath()));
        verify(originalHandler).handleException("processing failed", exchange, failure);
    }

    @Test
    void shouldPreserveNonDownloadExceptions() {
        repository.add(file.getAbsoluteFilePath());
        IllegalStateException failure = new IllegalStateException("Processing failed");

        consumer.getExceptionHandler().handleException("processing failed", exchange, failure);

        assertTrue(repository.contains(file.getAbsoluteFilePath()));
        verify(originalHandler).handleException("processing failed", exchange, failure);
    }

    @Test
    void shouldForwardPollErrorsWithoutFileMetadata() {
        repository.add(file.getAbsoluteFilePath());
        Exchange pollExchange = new DefaultExchange(context);
        GenericFileOperationFailedException failure = new GenericFileOperationFailedException("Cannot change directory");

        consumer.getExceptionHandler().handleException("poll failed", pollExchange, failure);
        consumer.getExceptionHandler().handleException("poll failed", null, failure);
        consumer.getExceptionHandler().handleException("poll failed", failure);
        consumer.getExceptionHandler().handleException(failure);

        assertTrue(repository.contains(file.getAbsoluteFilePath()));
        verify(originalHandler).handleException("poll failed", pollExchange, failure);
        verify(originalHandler).handleException("poll failed", null, failure);
        verify(originalHandler).handleException("poll failed", failure);
        verify(originalHandler).handleException(failure);
        verifyNoMoreInteractions(originalHandler);
    }

    @Test
    void shouldKeepExistingObserverWhenConsumerIsAddedAgain() {
        IdempotentRepository observedRepository = spy(repository);
        endpoint.setIdempotentRepository(observedRepository);
        observedRepository.add(file.getAbsoluteFilePath());
        ExceptionHandler downloadHandler = consumer.getExceptionHandler();
        ExceptionHandler observer = mock(ExceptionHandler.class);
        GenericFileOperationFailedException failure = new GenericFileOperationFailedException("Cannot retrieve file");
        doAnswer(invocation -> {
            downloadHandler.handleException("download failed", exchange, failure);
            return null;
        }).when(observer).handleException("download failed", exchange, failure);
        consumer.setExceptionHandler(observer);

        strategy.onServiceAdd(context, consumer, null);
        consumer.getExceptionHandler().handleException("download failed", exchange, failure);

        assertSame(observer, consumer.getExceptionHandler());
        assertFalse(observedRepository.contains(file.getAbsoluteFilePath()));
        verify(observedRepository).remove(file.getAbsoluteFilePath());
        verify(originalHandler).handleException("download failed", exchange, failure);
    }

    @Test
    void shouldIgnoreServicesOtherThanSftpConsumers() {
        Service service = mock(Service.class);

        strategy.onServiceAdd(context, service, null);

        verifyNoInteractions(service);
    }
}
