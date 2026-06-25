package org.qubership.integration.platform.engine.service.debugger.sessions;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.ErrorCause;
import org.opensearch.client.opensearch.core.BulkRequest;
import org.opensearch.client.opensearch.core.BulkResponse;
import org.opensearch.client.opensearch.core.bulk.BulkOperation;
import org.opensearch.client.opensearch.core.bulk.BulkResponseItem;
import org.opensearch.client.opensearch.core.bulk.IndexOperation;
import org.opensearch.client.opensearch.core.bulk.OperationType;
import org.qubership.integration.platform.engine.configuration.opensearch.OpenSearchProperties;
import org.qubership.integration.platform.engine.model.opensearch.ExceptionInfo;
import org.qubership.integration.platform.engine.model.opensearch.QueueElement;
import org.qubership.integration.platform.engine.model.opensearch.SessionElementElastic;
import org.qubership.integration.platform.engine.opensearch.OpenSearchClientSupplier;
import org.qubership.integration.platform.engine.service.ExecutionStatus;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class OpenSearchWriterDefaultTest {

    private static final String INDEX_NAME = "qip-elements-local";
    private static final String SESSION_ELEMENTS_INDEX = INDEX_NAME + "-session-elements";
    private static final String NORMALIZED_SESSION_ELEMENTS_INDEX = "normalized-session-elements";

    private TestableOpenSearchWriterDefault writer;

    @Mock
    private OpenSearchProperties openSearchProperties;

    @Mock
    private OpenSearchProperties.IndexProperties indexProperties;

    @Mock
    private OpenSearchProperties.ElementsProperties elementsProperties;

    @Mock
    private OpenSearchProperties.WriteProperties writeProperties;

    @Mock
    private OpenSearchProperties.BatchProperties batchProperties;

    @Mock
    private OpenSearchProperties.RetryProperties retryProperties;

    @Mock
    private OpenSearchProperties.TimeoutProperties timeoutProperties;

    @Mock
    private OpenSearchClientSupplier openSearchClientSupplier;

    @Mock
    private OpenSearchClient openSearchClient;

    private ObjectMapper mapper;
    private List<CapturedBulkRequest> capturedBulkRequests;

    @BeforeEach
    void setUp() {
        mapper = ObjectMappers.getObjectMapper();
        capturedBulkRequests = new ArrayList<>();
        when(openSearchProperties.index()).thenReturn(indexProperties);
        when(indexProperties.elements()).thenReturn(elementsProperties);
        when(elementsProperties.name()).thenReturn(INDEX_NAME);
    }

    @Test
    void shouldWriteSingleElementToOpenSearchWhenElementsCountDoesNotExceedThreshold() throws Exception {
        writer = createWriter(100);
        SessionElementElastic element = sessionElement("element-1");
        stubMinimumWriteTimeout(0);
        stubSuccessfulBulk();

        writer.saveElements(queueElements(element));

        CapturedBulkRequest request = captureSingleBulkRequest();
        assertRequestTargetsSessionElementsIndex(request);
        assertEquals(1, request.operations().size());
        assertIndexOperation(request.operations().getFirst(), element);
    }

    @Test
    void shouldBatchSmallElementsWhenElementsCountExceedsThreshold() throws Exception {
        writer = createWriter(100);
        SessionElementElastic firstElement = sessionElement("element-1");
        SessionElementElastic secondElement = sessionElement("element-2");
        stubMinimumWriteTimeout(0);
        stubSuccessfulBulk();

        writer.saveElements(queueElements(firstElement, secondElement));

        CapturedBulkRequest request = captureSingleBulkRequest();
        assertRequestTargetsSessionElementsIndex(request);
        assertEquals(2, request.operations().size());
        assertIndexOperation(request.operations().get(0), firstElement);
        assertIndexOperation(request.operations().get(1), secondElement);
    }

    @Test
    void shouldWriteLargePayloadsIndividuallyWhenPayloadThresholdIsExceeded() throws Exception {
        writer = createWriter(0);
        SessionElementElastic firstElement = sessionElement("element-1");
        SessionElementElastic secondElement = sessionElement("element-2");
        stubMinimumWriteTimeout(0);
        stubSuccessfulBulk();

        writer.saveElements(queueElements(firstElement, secondElement));

        verify(openSearchClient, times(2)).bulk(any(BulkRequest.class));
        assertEquals(2, capturedBulkRequests.size());
        assertIndexOperation(capturedBulkRequests.get(0).operations().getFirst(), firstElement);
        assertIndexOperation(capturedBulkRequests.get(1).operations().getFirst(), secondElement);
    }

    @Test
    void shouldRetrySingleElementWhenBulkRequestFailsOnce() throws Exception {
        writer = createWriter(100);
        SessionElementElastic element = sessionElement("element-1");
        stubRetryTimeout(1, 4);
        when(openSearchClientSupplier.normalize(SESSION_ELEMENTS_INDEX)).thenReturn(NORMALIZED_SESSION_ELEMENTS_INDEX);
        when(openSearchClientSupplier.getClient()).thenReturn(openSearchClient);
        when(openSearchClient.bulk(any(BulkRequest.class)))
                .thenAnswer(invocation -> {
                    capturedBulkRequests.add(capturedBulkRequest(invocation.getArgument(0)));
                    throw new IOException("bulk failed");
                })
                .thenAnswer(invocation -> {
                    capturedBulkRequests.add(capturedBulkRequest(invocation.getArgument(0)));
                    return successfulBulkResponse();
                });

        writer.saveElements(queueElements(element));

        verify(openSearchClient, times(2)).bulk(any(BulkRequest.class));
        assertEquals(2, capturedBulkRequests.size());
        assertEquals(1L, writer.currentWriteTimeout());
    }

    @Test
    void shouldStopRetryingWhenSingleElementBulkKeepsFailing() throws Exception {
        writer = createWriter(100);
        SessionElementElastic element = sessionElement("element-1");
        stubMaximumWriteTimeout();
        when(openSearchClientSupplier.normalize(SESSION_ELEMENTS_INDEX)).thenReturn(NORMALIZED_SESSION_ELEMENTS_INDEX);
        when(openSearchClientSupplier.getClient()).thenReturn(openSearchClient);
        when(openSearchClient.bulk(any(BulkRequest.class))).thenThrow(new IOException("bulk failed"));

        writer.saveElements(queueElements(element));

        verify(openSearchClient, times(6)).bulk(any(BulkRequest.class));
    }

    @Test
    void shouldSkipElementWhenMapperCannotSerializeIt() throws Exception {
        ObjectMapper failingMapper = mock(ObjectMapper.class);
        writer = createWriter(100, failingMapper);
        SessionElementElastic element = sessionElement("element-1");
        stubMinimumWriteTimeout(0);
        when(failingMapper.writeValueAsBytes(element)).thenThrow(new JsonProcessingException("serialization failed") {
        });

        writer.saveElements(queueElements(element));

        verifyNoInteractions(openSearchClientSupplier, openSearchClient);
    }

    @Test
    void shouldQueueElementAndTrackPayloadSizeWhenSchedulingElement() throws Exception {
        writer = createWriter(100);
        SessionElementElastic element = fullSessionElement("element-1");
        long expectedPayloadSize = expectedPayloadSize(element);

        writer.scheduleElementToLog(element);

        QueueElement queuedElement = writer.queue().peek();
        assertEquals(1, writer.queue().size());
        assert queuedElement != null;
        assertSame(element, queuedElement.getElement());
        assertEquals(expectedPayloadSize, queuedElement.getCalculatedPayloadSize());
        assertEquals(expectedPayloadSize, writer.queueTotalPayloadSize());
    }

    @Test
    void shouldTrackZeroPayloadWhenSchedulingEmptyElement() throws Exception {
        writer = createWriter(100);
        SessionElementElastic element = new SessionElementElastic();

        writer.scheduleElementToLog(element);

        QueueElement queuedElement = writer.queue().peek();
        assertEquals(1, writer.queue().size());
        assert queuedElement != null;
        assertSame(element, queuedElement.getElement());
        assertEquals(0L, queuedElement.getCalculatedPayloadSize());
        assertEquals(0L, writer.queueTotalPayloadSize());
    }

    @Test
    void shouldCacheElementWhenSchedulingWithCacheEnabled() {
        writer = createWriter(100);
        SessionElementElastic element = sessionElement("element-1");

        writer.scheduleElementToLog(element, true);

        assertSame(element, writer.getSessionElementFromCache(element.getSessionId(), element.getId()));
    }

    @Test
    void shouldNotQueueElementWhenQueueCapacityIsExceeded() throws Exception {
        writer = createWriter(1, 1, 100, mapper);
        SessionElementElastic firstElement = sessionElement("element-1");
        SessionElementElastic secondElement = sessionElement("element-2");

        writer.scheduleElementToLog(firstElement);
        writer.scheduleElementToLog(secondElement);

        assertEquals(1, writer.queue().size());
        assertSame(firstElement, writer.queue().peek().getElement());
    }

    @Test
    void shouldNotQueueElementWhenQueuePayloadLimitIsReached() throws Exception {
        writer = createWriter(10, 0, 100, mapper);

        writer.scheduleElementToLog(sessionElement("element-1"));

        assertTrue(writer.queue().isEmpty());
        assertEquals(0L, writer.queueTotalPayloadSize());
    }

    @Test
    void shouldCalculatePayloadSizeForAllAvailableFields() throws Exception {
        writer = createWriter(100);
        SessionElementElastic element = fullSessionElement("element-1");

        long payloadSize = writer.calculatePayloadSizeInBytes(element);

        assertEquals(expectedPayloadSize(element), payloadSize);
    }

    @Test
    void shouldCalculateAlignedElementSizeWhenValueIsNotNull() throws Exception {
        writer = createWriter(100);

        long size = writer.calculateElementSize("abcd");

        assertEquals(expectedElementSize("abcd"), size);
    }

    @Test
    void shouldReturnZeroElementSizeWhenValueIsNull() throws Exception {
        writer = createWriter(100);

        long size = writer.calculateElementSize(null);

        assertEquals(0L, size);
    }

    @Test
    void shouldIncreaseWriteTimeoutUntilMaximum() throws Exception {
        writer = createWriter(100);
        stubRetryTimeout(3, 10);

        writer.increaseWriteTimeout();
        assertEquals(6L, writer.currentWriteTimeout());

        writer.increaseWriteTimeout();
        assertEquals(10L, writer.currentWriteTimeout());

        writer.increaseWriteTimeout();
        assertEquals(10L, writer.currentWriteTimeout());
    }

    @Test
    void shouldReturnTrueWhenBulkResponseHasFailedElements() throws Exception {
        writer = createWriter(100);
        BulkResponse response = bulkResponse(
                successfulBulkResponseItem("element-0"),
                failedBulkResponseItem("element-1", "first reason")
        );

        boolean result = writer.checkAndLogFailedElements(response);

        assertTrue(result);
    }

    @Test
    void shouldLogOverflowSummaryWhenBulkResponseHasMoreFailedElementsThanThreshold() throws Exception {
        writer = createWriter(100);
        BulkResponse response = bulkResponse(
                failedBulkResponseItem("element-1", "first reason"),
                failedBulkResponseItem("element-2", "second reason"),
                failedBulkResponseItem("element-3", "third reason"),
                failedBulkResponseItem("element-4", "fourth reason")
        );

        boolean result = writer.checkAndLogFailedElements(response);

        assertTrue(result);
    }

    @Test
    void shouldExitRunWhenPayloadSizeUpdateFailsAndSleepIsInterrupted() throws Exception {
        writer = createWriter(100);
        QueueElement queueElement = mock(QueueElement.class);
        stubMinimumWriteTimeout(0);
        when(writeProperties.batch()).thenReturn(batchProperties);
        when(batchProperties.count()).thenReturn(1);
        when(queueElement.getCalculatedPayloadSize()).thenAnswer(invocation -> {
            Thread.currentThread().interrupt();
            throw new RuntimeException("payload size failed");
        });
        writer.queue().add(queueElement);

        RuntimeException exception = assertThrows(RuntimeException.class, writer::runLoop);

        assertInstanceOf(InterruptedException.class, exception.getCause());
    }

    private TestableOpenSearchWriterDefault createWriter(
            int bulkRequestPayloadSizeThresholdKb
    ) {
        return createWriter(bulkRequestPayloadSizeThresholdKb, mapper);
    }

    private TestableOpenSearchWriterDefault createWriter(
            int bulkRequestPayloadSizeThresholdKb,
            ObjectMapper mapper
    ) {
        return createWriter(
                10,
                1,
            bulkRequestPayloadSizeThresholdKb,
            mapper
        );
    }

    private TestableOpenSearchWriterDefault createWriter(
            int sessionBufferCapacity,
            int queueMaxSizeMb,
            int bulkRequestPayloadSizeThresholdKb,
            ObjectMapper mapper
    ) {
        return new TestableOpenSearchWriterDefault(
                sessionBufferCapacity,
                queueMaxSizeMb,
            100,
                bulkRequestPayloadSizeThresholdKb,
            1,
                openSearchProperties,
                openSearchClientSupplier,
                mapper
        );
    }

    private void stubMinimumWriteTimeout(int minimum) {
        when(openSearchProperties.write()).thenReturn(writeProperties);
        when(writeProperties.retry()).thenReturn(retryProperties);
        when(retryProperties.timeout()).thenReturn(timeoutProperties);
        when(timeoutProperties.minimum()).thenReturn(minimum);
    }

    private void stubMaximumWriteTimeout() {
        when(openSearchProperties.write()).thenReturn(writeProperties);
        when(writeProperties.retry()).thenReturn(retryProperties);
        when(retryProperties.timeout()).thenReturn(timeoutProperties);
        when(timeoutProperties.maximum()).thenReturn(0);
    }

    private void stubRetryTimeout(int minimum, int maximum) {
        stubMinimumWriteTimeout(minimum);
        when(timeoutProperties.maximum()).thenReturn(maximum);
    }

    private void stubSuccessfulBulk() throws Exception {
        when(openSearchClientSupplier.normalize(SESSION_ELEMENTS_INDEX)).thenReturn(NORMALIZED_SESSION_ELEMENTS_INDEX);
        when(openSearchClientSupplier.getClient()).thenReturn(openSearchClient);
        when(openSearchClient.bulk(any(BulkRequest.class))).thenAnswer(invocation -> {
            capturedBulkRequests.add(capturedBulkRequest(invocation.getArgument(0)));
            return successfulBulkResponse();
        });
    }

    private CapturedBulkRequest capturedBulkRequest(BulkRequest request) {
        return new CapturedBulkRequest(
                request.index(),
                request.requireAlias(),
                List.copyOf(request.operations())
        );
    }

    private CapturedBulkRequest captureSingleBulkRequest() throws Exception {
        verify(openSearchClient).bulk(any(BulkRequest.class));
        assertEquals(1, capturedBulkRequests.size());
        return capturedBulkRequests.getFirst();
    }

    private void assertRequestTargetsSessionElementsIndex(CapturedBulkRequest request) {
        assertEquals(NORMALIZED_SESSION_ELEMENTS_INDEX, request.index());
        assertEquals(Boolean.TRUE, request.requireAlias());
    }

    private void assertIndexOperation(BulkOperation operation, SessionElementElastic element) {
        assertTrue(operation.isIndex());
        IndexOperation<?> indexOperation = operation.index();
        assertEquals(NORMALIZED_SESSION_ELEMENTS_INDEX, indexOperation.index());
        assertEquals(element.getId(), indexOperation.id());
        assertEquals(Boolean.TRUE, indexOperation.requireAlias());
        assertSame(element, indexOperation.document());
    }

    private BulkResponse successfulBulkResponse() {
        return BulkResponse.of(response -> response
                .took(1)
                .errors(false)
                .items(List.of()));
    }

    private BulkResponse bulkResponse(BulkResponseItem... items) {
        return BulkResponse.of(response -> response
                .took(1)
                .errors(true)
                .items(List.of(items)));
    }

    private BulkResponseItem successfulBulkResponseItem(String id) {
        return BulkResponseItem.of(item -> item
                .operationType(OperationType.Index)
                .id(id)
                .index(NORMALIZED_SESSION_ELEMENTS_INDEX)
                .status(201));
    }

    private BulkResponseItem failedBulkResponseItem(String id, String reason) {
        return BulkResponseItem.of(item -> item
                .operationType(OperationType.Index)
                .id(id)
                .index(NORMALIZED_SESSION_ELEMENTS_INDEX)
                .status(500)
                .error(ErrorCause.of(error -> error
                        .type("test_error")
                        .reason(reason))));
    }

    private SessionElementElastic sessionElement(String id) {
        return SessionElementElastic.builder()
                .id(id)
                .sessionId("session-" + id)
                .chainId("chain-" + id)
                .bodyBefore("body-before-" + id)
                .bodyAfter("body-after-" + id)
                .build();
    }

    private SessionElementElastic fullSessionElement(String id) {
        return SessionElementElastic.builder()
                .id(id)
                .sessionId("session-" + id)
                .externalSessionId("external-session-" + id)
                .sessionStarted("2026-06-22T10:00:00Z")
                .sessionFinished("2026-06-22T10:00:01Z")
                .sessionExecutionStatus(ExecutionStatus.COMPLETED_WITH_ERRORS)
                .chainId("chain-" + id)
                .actualElementChainId("actual-chain-" + id)
                .chainName("chain name " + id)
                .domain("domain-" + id)
                .engineAddress("engine-" + id)
                .loggingLevel("DEBUG")
                .snapshotName("snapshot-" + id)
                .correlationId("correlation-" + id)
                .chainElementId("chain-element-" + id)
                .elementName("element name " + id)
                .camelElementName("camel element " + id)
                .prevElementId("previous-" + id)
                .parentElementId("parent-element-" + id)
                .parentSessionId("parent-session-" + id)
                .bodyBefore("body-before-" + id)
                .bodyAfter("body-after-" + id)
                .headersBefore("headers-before-" + id)
                .headersAfter("headers-after-" + id)
                .propertiesBefore("properties-before-" + id)
                .propertiesAfter("properties-after-" + id)
                .contextBefore("context-before-" + id)
                .contextAfter("context-after-" + id)
                .exceptionInfo(ExceptionInfo.builder()
                        .message("failure-" + id)
                        .stackTrace("stack-" + id)
                        .build())
                .build();
    }

    private long expectedPayloadSize(SessionElementElastic element) {
        long size = 0;
        size += expectedElementSize(element.getSessionId());
        size += expectedElementSize(element.getExternalSessionId());
        size += expectedElementSize(element.getSessionStarted());
        size += expectedElementSize(element.getSessionFinished());
        size += expectedElementSize(element.getChainId());
        size += expectedElementSize(element.getActualElementChainId());
        size += expectedElementSize(element.getChainName());
        size += expectedElementSize(element.getDomain());
        size += expectedElementSize(element.getEngineAddress());
        size += expectedElementSize(element.getLoggingLevel());
        size += expectedElementSize(element.getSnapshotName());
        size += expectedElementSize(element.getCorrelationId());
        size += expectedElementSize(element.getChainElementId());
        size += expectedElementSize(element.getElementName());
        size += expectedElementSize(element.getCamelElementName());
        size += expectedElementSize(element.getPrevElementId());
        size += expectedElementSize(element.getParentElementId());
        size += expectedElementSize(element.getParentSessionId());
        size += expectedElementSize(element.getBodyBefore());
        size += expectedElementSize(element.getBodyAfter());
        size += expectedElementSize(element.getHeadersBefore());
        size += expectedElementSize(element.getHeadersAfter());
        size += expectedElementSize(element.getPropertiesBefore());
        size += expectedElementSize(element.getPropertiesAfter());
        size += expectedElementSize(element.getContextBefore());
        size += expectedElementSize(element.getContextAfter());
        size += expectedElementSize(element.getSessionExecutionStatus().name());
        size += expectedElementSize(element.getExceptionInfo().toString());
        return size;
    }

    private long expectedElementSize(String value) {
        if (value == null) {
            return 0;
        }
        return ((16L + (long) value.length() * 2L + 38L + 7L) / 8L) * 8L;
    }

    private LinkedHashSet<QueueElement> queueElements(SessionElementElastic... elements) {
        LinkedHashSet<QueueElement> queueElements = new LinkedHashSet<>();
        for (SessionElementElastic element : elements) {
            queueElements.add(QueueElement.builder()
                    .element(element)
                    .calculatedPayloadSize(1)
                    .build());
        }
        return queueElements;
    }

    @SuppressWarnings("unchecked")
    private static <T> T getField(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        if (!field.trySetAccessible()) {
            throw new IllegalStateException("Failed to access field: " + fieldName);
        }
        return (T) field.get(target);
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private record CapturedBulkRequest(String index, Boolean requireAlias, List<BulkOperation> operations) {
    }

    private static class TestableOpenSearchWriterDefault extends OpenSearchWriterDefault {

        TestableOpenSearchWriterDefault(
                int sessionBufferCapacity,
                int queueMaxSizeMb,
                int bulkRequestMaxSizeKb,
                int bulkRequestPayloadSizeThresholdKb,
                int bulkRequestElementsCountThreshold,
                OpenSearchProperties openSearchProperties,
                OpenSearchClientSupplier openSearchClientSupplier,
                ObjectMapper mapper
        ) {
            super(
                    sessionBufferCapacity,
                    queueMaxSizeMb,
                    bulkRequestMaxSizeKb,
                    bulkRequestPayloadSizeThresholdKb,
                    bulkRequestElementsCountThreshold,
                    openSearchProperties,
                    openSearchClientSupplier,
                    mapper
            );
        }

        @Override
        public void run() {
        }

        void runLoop() {
            super.run();
        }

        void saveElements(LinkedHashSet<QueueElement> sessionElements) throws Exception {
            invoke("saveElements", new Class<?>[]{LinkedHashSet.class}, sessionElements);
        }

        long calculatePayloadSizeInBytes(SessionElementElastic element) throws Exception {
            return invoke("calculatePayloadSizeInBytes", new Class<?>[]{SessionElementElastic.class}, element);
        }

        long calculateElementSize(String value) throws Exception {
            return invoke("calculateElementSize", new Class<?>[]{String.class}, value);
        }

        void increaseWriteTimeout() throws Exception {
            invoke("increaseWriteTimeout", new Class<?>[0]);
        }

        boolean checkAndLogFailedElements(BulkResponse response) throws Exception {
            return invoke("checkAndLogFailedElements", new Class<?>[]{BulkResponse.class}, response);
        }

        BlockingQueue<QueueElement> queue() throws Exception {
            return getField(this, "sessionElementsQueue");
        }

        long queueTotalPayloadSize() throws Exception {
            AtomicLong queueTotalPayloadSize = getField(this, "queueTotalPayloadSize");
            return queueTotalPayloadSize.get();
        }

        long currentWriteTimeout() throws Exception {
            return getField(this, "currentWriteTimeout");
        }

        @SuppressWarnings("unchecked")
        private <T> T invoke(String methodName, Class<?>[] parameterTypes, Object... args) throws Exception {
            Method method = OpenSearchWriterDefault.class.getDeclaredMethod(methodName, parameterTypes);
            if (!method.trySetAccessible()) {
                throw new IllegalStateException("Failed to access method: " + methodName);
            }
            try {
                return (T) method.invoke(this, args);
            } catch (InvocationTargetException exception) {
                Throwable cause = exception.getCause();
                if (cause instanceof Exception ex) {
                    throw ex;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new RuntimeException(cause);
            }
        }
    }
}
