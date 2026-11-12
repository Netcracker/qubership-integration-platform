package org.qubership.integration.platform.engine.routes.fixture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jcraft.jsch.SftpATTRS;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.component.file.FileComponent;
import org.apache.camel.component.file.GenericFile;
import org.apache.camel.component.file.remote.FtpConstants;
import org.apache.camel.component.file.remote.SftpEndpoint;
import org.apache.camel.support.DefaultExchange;
import org.apache.camel.support.processor.idempotent.MemoryIdempotentRepository;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureExchangeExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureInteraction;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureRequestExpectation;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.support.SnapshotFailureAssertions;
import org.qubership.integration.platform.engine.routes.support.SnapshotValueAssertions;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;
import org.rnorth.ducttape.ratelimits.RateLimiterBuilder;
import org.rnorth.ducttape.unreliables.Unreliables;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class SftpTriggerSnapshotRuntime {
    private static final ObjectMapper MAPPER = ObjectMappers.getObjectMapper();
    private static final String SOURCE_BODY = "snapshotSftpSourceBody";
    private static final String COMPLETION_ID = "snapshotSftpCompletionId";
    private static final String COMPLETION_CORRELATION_ID = "snapshotSftpCompletionCorrelationId";
    private static final Set<String> PARAMETERS = Set.of("nodeId", "fileName", "bodyBase64", "bodyRepeat", "mtimeSeconds",
            "files", "publish", "expectedCompletions");
    private static final Set<String> FILE_PARAMETERS = Set.of("fileName", "body", "bodyBase64", "bodyRepeat", "mtimeSeconds");
    private final SftpTriggerSnapshotServer server;
    private final CamelContext context;
    private final SftpTriggerSnapshotPollObserver polls;
    private final List<Exchange> received = new CopyOnWriteArrayList<>();
    private final List<Exchange> results = new CopyOnWriteArrayList<>();
    private final List<Throwable> transferFailures = new CopyOnWriteArrayList<>();
    private final List<Throwable> nativeRouteFailures = new CopyOnWriteArrayList<>();
    private final Map<String, Throwable> nativeFailuresByExchangeId = new ConcurrentHashMap<>();
    private final Set<String> observedBodyTypes = ConcurrentHashMap.newKeySet();
    private final BlockingQueue<Exchange> completed = new LinkedBlockingQueue<>();
    private final Map<String, byte[]> publications = new ConcurrentHashMap<>();
    private final AtomicInteger downstreamFailures = new AtomicInteger();
    private final AtomicBoolean heldOnce = new AtomicBoolean();
    private SftpEndpoint endpoint;
    private Map<String, Object> controls = Map.of();
    private AfterPublish afterPublish = () -> { };
    private volatile CountDownLatch held;
    private volatile CountDownLatch release;
    private boolean transferInterruptionRequested;
    private long requiredInterruption;
    private boolean firstInvocation = true;
    private String routeId;
    private long invocationPolls;
    private volatile int completionLimit;
    private volatile Map<String, Object> faultEvidence = Map.of();

    @FunctionalInterface
    interface AfterPublish {
        void run() throws Exception;
    }

    SftpTriggerSnapshotRuntime(SftpTriggerSnapshotServer server, CamelContext context, SftpEndpoint endpoint,
                              SftpTriggerSnapshotPollObserver polls, String routeId, Map<String, Object> initialControls) {
        this.server = server;
        this.context = context;
        this.endpoint = endpoint;
        this.polls = polls;
        this.routeId = routeId;
        controls = initialControls;
        downstreamFailures.set(number(initialControls, "controlledDownstreamFailures", 0));
    }

    public static String registryName(String nodeId) {
        return "snapshot-sftp-trigger:" + nodeId;
    }

    public static void validateParameters(Map<?, ?> options, boolean file) {
        if (!(file ? FILE_PARAMETERS : PARAMETERS).containsAll(options.keySet())) {
            throw new IllegalArgumentException("Unsupported SFTP trigger publication parameters: " + options.keySet());
        }
        for (String name : List.of("fileName", "bodyBase64")) {
            if (options.containsKey(name) && !(options.get(name) instanceof String)) {
                throw new IllegalArgumentException("SFTP trigger " + name + " must be a string.");
            }
        }
        if (options.get("fileName") instanceof String name && name.isBlank()) {
            throw new IllegalArgumentException("SFTP trigger fileName must not be blank.");
        }
        if (options.get("bodyBase64") instanceof String encoded) {
            Base64.getDecoder().decode(encoded);
            if (options.containsKey("bodyRepeat")) {
                throw new IllegalArgumentException("SFTP trigger bodyBase64 and bodyRepeat cannot be combined.");
            }
        }
        for (String name : List.of("bodyRepeat", "expectedCompletions", "mtimeSeconds")) {
            if (options.containsKey(name) && (!(options.get(name) instanceof Integer count)
                    || count < ("bodyRepeat".equals(name) ? 1 : 0))) {
                throw new IllegalArgumentException("SFTP trigger " + name + " must be a valid nonnegative integer.");
            }
        }
        if (options.containsKey("publish") && !(options.get("publish") instanceof Boolean)) {
            throw new IllegalArgumentException("SFTP trigger publish must be a boolean.");
        }
        if (options.containsKey("files")) {
            if (!(options.get("files") instanceof List<?> files) || files.isEmpty()) {
                throw new IllegalArgumentException("SFTP trigger files must be a nonempty array.");
            }
            for (Object item : files) {
                if (!(item instanceof Map<?, ?> values)) {
                    throw new IllegalArgumentException("SFTP trigger files must contain objects.");
                }
                validateParameters(values, true);
                if (!values.containsKey("fileName")) {
                    throw new IllegalArgumentException("SFTP trigger files require fileName.");
                }
            }
        }
    }

    public Exchange send(SnapshotScenarioInvocation invocation, Map<String, Object> parameters) throws Exception {
        try {
            int expected = number(parameters, "expectedCompletions", 1);
            if (Boolean.FALSE.equals(endpoint.getIdempotent())) {
                completionLimit = expected;
            }
            if (!Boolean.FALSE.equals(parameters.get("publish"))) {
                publish(parameters, invocation.getBody(), invocation.getId() + ".txt");
            }
            long barrier = polls.completed();
            afterPublish.run();
            polls.resume();
            Exchange result = null;
            for (int index = 0; index < expected; index++) {
                result = completed.poll(20, TimeUnit.SECONDS);
                if (result == null) {
                    throw new AssertionError("SFTP trigger did not complete the expected file. " + diagnostics() + nativeThreadStacks());
                }
            }
            if (Boolean.FALSE.equals(endpoint.getIdempotent())) {
                context.getRouteController().stopRoute(routeId);
            } else if (!isStopped()) {
                await(() -> polls.completed() >= barrier + 2, "SFTP polls after publication");
            }
            if (result == null) {
                result = new DefaultExchange(context);
                result.getMessage().setBody(invocation.getBody());
            }
            return result;
        } finally {
            polls.resume();
            polls.releaseFollowingPolls();
            releaseHeld();
        }
    }

    void beforeInvocation(Map<String, Object> options) throws Exception {
        polls.pause();
        try {
            if (!firstInvocation) {
                received.clear();
                results.clear();
                completed.clear();
                publications.clear();
                transferFailures.clear();
                nativeRouteFailures.clear();
                nativeFailuresByExchangeId.clear();
                observedBodyTypes.clear();
            }
            firstInvocation = false;
            controls = options;
            downstreamFailures.set(number(options, "controlledDownstreamFailures", 0));
            polls.clearFailures();
            invocationPolls = polls.completed();
            held = null;
            release = null;
            heldOnce.set(false);
            afterPublish = () -> { };
            transferInterruptionRequested = false;
            completionLimit = 0;
        } finally {
            polls.resume();
        }
    }

    void publish(Map<?, ?> options, Object defaultBody, String defaultName) throws Exception {
        if (options.get("files") instanceof List<?> files) {
            publishFiles(files);
        } else {
            upload(options, defaultBody, defaultName);
        }
    }

    void publishFiles(List<?> files) throws Exception {
        for (Object item : files) {
            Map<?, ?> options = assertInstanceOf(Map.class, item);
            validateParameters(options, true);
            upload(options, options.getOrDefault("body", null), "file.txt");
        }
    }

    private void upload(Map<?, ?> options, Object defaultBody, String defaultName) throws Exception {
        String fileName = options.get("fileName") instanceof String name ? name : defaultName;
        byte[] bytes;
        if (options.get("bodyBase64") instanceof String encoded) {
            bytes = Base64.getDecoder().decode(encoded);
        } else {
            Object configuredBody = options.containsKey("body") ? options.get("body") : defaultBody;
            String text = configuredBody == null ? "" : String.valueOf(configuredBody);
            bytes = text.repeat(number(options, "bodyRepeat", 1)).getBytes(StandardCharsets.UTF_8);
        }
        publications.put(fileName, bytes);
        server.upload(fileName, bytes, options.get("mtimeSeconds") instanceof Integer value ? value : null);
    }

    void received(Exchange exchange) throws Exception {
        Object body = exchange.getMessage().getBody();
        GenericFile<?> source = exchange.getProperty(FileComponent.FILE_EXCHANGE_FILE, GenericFile.class);
        assertNotNull(source, "Native SFTP delivery must retain its remote file metadata.");
        Object nativeBody = source.getBody();
        observedBodyTypes.add("native=" + nativeBody.getClass().getName() + ", message=" + body.getClass().getName());
        boolean streaming = nativeBody instanceof InputStream;
        if (controls.get("expectedStreamDownload") instanceof Boolean expected) {
            assertEquals(expected, streaming, "SFTP native download body representation");
            assertEquals(expected, exchange.getMessage().getHeader(FtpConstants.REMOTE_FILE_INPUT_STREAM) instanceof InputStream,
                    "SFTP native streaming resource header");
        }
        byte[] bytes;
        if (body instanceof InputStream stream) {
            try {
                bytes = stream.readAllBytes();
            } catch (IOException exception) {
                transferFailures.add(exception);
                throw exception;
            }
            exchange.getMessage().setBody(new ByteArrayInputStream(bytes));
        } else {
            bytes = assertInstanceOf(byte[].class, nativeBody);
        }
        exchange.setProperty(SOURCE_BODY, bytes.clone());
        Exchange snapshot = copy(exchange);
        snapshot.getMessage().setBody(bytes.clone());
        received.add(snapshot);
        if (Boolean.TRUE.equals(controls.get("verifyFileHeaders"))) {
            verifyMetadata(snapshot, bytes);
        }
        if (downstreamFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
            exchange.setProperty("snapshotFail", true);
        }
        if (held != null && heldOnce.compareAndSet(false, true)) {
            held.countDown();
            assertTrue(release.await(25, TimeUnit.SECONDS), "SFTP fixture did not release the held file.");
        }
    }

    void completed(Exchange exchange) {
        Exchange snapshot = copy(exchange);
        snapshot.setProperty(COMPLETION_ID, exchange.getExchangeId());
        snapshot.setProperty(COMPLETION_CORRELATION_ID, exchange.getProperty(Exchange.CORRELATION_ID, String.class));
        if (snapshot.getException() == null && !exchange.getExchangeExtension().isFailureHandled()) {
            snapshot.setException(exchange.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class));
        }
        results.add(snapshot);
        if (completionLimit > 0 && results.size() >= completionLimit) {
            polls.holdFollowingPolls();
        }
        completed.add(snapshot);
    }

    void failed(Exchange exchange) {
        if (exchange.getFromEndpoint() == endpoint || routeId.equals(exchange.getFromRouteId())) {
            Throwable failure = exchange.getException();
            if (failure != null) {
                nativeRouteFailures.add(failure);
                nativeFailuresByExchangeId.put(exchange.getExchangeId(), failure);
            }
        }
    }

    private static Exchange copy(Exchange exchange) {
        Exchange copy = exchange.copy();
        if (copy.getMessage().getBody() instanceof InputStream) {
            byte[] bytes = exchange.getProperty(SOURCE_BODY, byte[].class);
            copy.getMessage().setBody(bytes == null ? new byte[0] : bytes.clone());
        }
        return copy;
    }

    private void verifyMetadata(Exchange exchange, byte[] bytes) throws Exception {
        String name = exchange.getMessage().getHeader(Exchange.FILE_NAME_ONLY, String.class);
        SftpATTRS attributes = server.stat(name);
        assertEquals(name, exchange.getMessage().getHeader(Exchange.FILE_NAME), "SFTP relative filename");
        assertEquals(attributes.getSize(), exchange.getMessage().getHeader(Exchange.FILE_LENGTH, Long.class), "SFTP file length header");
        assertEquals(attributes.getSize(), bytes.length, "SFTP downloaded content length");
        assertEquals(attributes.getMTime() * 1000L, exchange.getMessage().getHeader(Exchange.FILE_LAST_MODIFIED, Long.class),
                "SFTP file modification time header");
        String path = exchange.getMessage().getHeader(Exchange.FILE_PATH, String.class);
        assertTrue(path.endsWith(server.directory().substring(1) + '/' + name), "SFTP file path header must retain its directory.");
    }

    void verify(SnapshotFixtureInteraction interaction, String invocationId) throws Exception {
        verifyState(interaction, invocationId);
        if (hasRemoteFiles()) {
            try (SftpTriggerSnapshotServer.Inspection inspection = server.inspect()) {
                Map<String, byte[]> remoteContents = new LinkedHashMap<>();
                verifyInputs(interaction, invocationId, inspection, remoteContents);
                verifyRemoteFiles(invocationId, inspection, remoteContents);
            }
        }
    }

    void verifyFinal(SnapshotFixtureInteraction interaction, String invocationId) throws Exception {
        verifyState(interaction, invocationId);
        if (hasRemoteFiles()) {
            try (SftpTriggerSnapshotServer.Inspection inspection = server.inspect()) {
                verifyRemoteFiles(invocationId, inspection, new LinkedHashMap<>());
            }
        }
    }

    private boolean hasRemoteFiles() {
        return !received.isEmpty() || !publications.isEmpty()
                || controls.get("expectedRemoteFiles") instanceof Map<?, ?> files && !files.isEmpty();
    }

    private void verifyState(SnapshotFixtureInteraction interaction, String invocationId) {
        String description = "SFTP trigger invocation '" + invocationId + "'";
        SnapshotFixtureRequestExpectation request = interaction.getExpectedRequest();
        assertEquals(request.getCount(), received.size(), description + " native input count");
        assertCount("expectedBusinessCount", results.stream().filter(item -> item.getProperty("snapshotSftpScriptLength") instanceof Number
                        && resolveFailure(item) == null).count(),
                request.getCount(), description);
        assertCount("expectedFailureCompletions", results.stream().filter(item -> resolveFailure(item) != null).count(), 0, description);
        if (controls.get("expectedPollFailures") instanceof Number expected) {
            if (expected.intValue() == 0) {
                assertEquals(0, polls.failures().size(), description + " native polling failures");
            } else {
                assertTrue(polls.failures().size() >= expected.intValue(), description + " native polling failures: " + polls.failures());
            }
        }
        if (controls.get("expectedRetainedKeyCount") instanceof Number expected) {
            MemoryIdempotentRepository repository = assertInstanceOf(MemoryIdempotentRepository.class, endpoint.getIdempotentRepository());
            assertEquals(expected.intValue(), repository.getCacheSize(), description + " native retained file keys");
        }
        if (controls.get("expectedFileNames") instanceof List<?> expected) {
            List<String> actual = received.stream().map(input -> input.getMessage().getHeader(Exchange.FILE_NAME_ONLY, String.class)).sorted().toList();
            assertEquals(expected.stream().map(String::valueOf).sorted().toList(), actual, description + " exact file-name multiset");
        }
        if (controls.get("expectedInputsByFile") instanceof Map<?, ?> expected) {
            List<String> actual = received.stream().map(input -> input.getMessage().getHeader(Exchange.FILE_NAME_ONLY, String.class)).sorted().toList();
            assertEquals(expected.keySet().stream().map(String::valueOf).sorted().toList(), actual,
                    description + " per-file input expectation multiset");
        }
        if (interaction.hasExpectedExchanges()) {
            List<SnapshotFixtureExchangeExpectation> expectations = new ArrayList<>();
            interaction.getExpectedExchanges().forEach(value -> {
                for (int index = 0; index < value.getCount(); index++) {
                    expectations.add(value);
                }
            });
            assertEquals(expectations.size(), results.size(), description + " completion expectation count");
            for (int index = 0; index < expectations.size(); index++) {
                SnapshotFixtureExchangeExpectation expected = expectations.get(index);
                Exchange actual = results.get(index);
                if (expected.hasBody()) {
                    assertBody(expected.getBody(), actual.getMessage().getBody(), description + " completion body");
                }
                SnapshotValueAssertions.assertMapValues(expected.getHeaders(), actual.getMessage().getHeaders(), description + " completion header");
                SnapshotValueAssertions.assertMapValues(expected.getProperties(), actual.getAllProperties(), description + " completion property");
                SnapshotFailureAssertions.assertMatches(expected.getExpectedFailure(), resolveFailure(actual), description + " completion");
            }
        }
        for (Exchange result : results) {
            if (result.getProperty(SOURCE_BODY) instanceof byte[] input) {
                Number consumed = result.getProperty("snapshotSftpScriptLength", Number.class);
                assertNotNull(consumed, description + " must execute the exported script's input read");
                assertEquals(input.length, consumed.longValue(), description + " exported script input byte length");
            }
        }
        if (transferInterruptionRequested || Boolean.TRUE.equals(controls.get("expectedTransferFailure"))) {
            assertTrue(server.interruptionCount() >= requiredInterruption,
                    description + " must interrupt the actual consumer transport during download");
            assertEquals(65536, server.interruptedServerBytes(), description + " exact external transfer interruption byte budget");
            List<Throwable> failures = new ArrayList<>(transferFailures);
            failures.addAll(nativeRouteFailures);
            failures.addAll(polls.failures());
            assertTrue(failures.stream().anyMatch(SftpTriggerSnapshotRuntime::hasIoCause),
                    description + " must observe an actual native stream failure: " + failures);
        }
    }

    private void verifyInputs(SnapshotFixtureInteraction interaction, String invocationId,
                              SftpTriggerSnapshotServer.Inspection inspection, Map<String, byte[]> remoteContents) throws Exception {
        String description = "SFTP trigger invocation '" + invocationId + "'";
        SnapshotFixtureRequestExpectation request = interaction.getExpectedRequest();
        for (Exchange input : received) {
            byte[] bytes = input.getMessage().getBody(byte[].class);
            String name = input.getMessage().getHeader(Exchange.FILE_NAME_ONLY, String.class);
            assertArrayEquals(read(inspection, remoteContents, name), bytes, description + " independently observed input bytes for " + name);
            if (request.hasBody()) {
                assertBody(request.getBody(), bytes, description + " input body");
            }
            if (controls.get("expectedBodyBase64") instanceof String encoded) {
                assertArrayEquals(Base64.getDecoder().decode(encoded), bytes, description + " binary input");
            }
            if (controls.get("expectedBodyLength") instanceof Number expected) {
                assertEquals(expected.longValue(), bytes.length, description + " input byte length");
            }
            if (controls.get("expectedBodySha256") instanceof String expected) {
                assertEquals(expected, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                        description + " input digest");
            }
            SnapshotValueAssertions.assertMapValues(request.getHeaders(), input.getMessage().getHeaders(), description + " header");
            SnapshotValueAssertions.assertMapValues(request.getProperties(), input.getAllProperties(), description + " property");
            if (controls.get("expectedInputsByFile") instanceof Map<?, ?> expected) {
                Map<?, ?> file = assertInstanceOf(Map.class, expected.get(name), description + " input expectation for " + name);
                if (file.containsKey("body")) {
                    assertBody(file.get("body"), bytes, description + " input body for " + name);
                }
                if (file.get("bodyLength") instanceof Number length) {
                    assertEquals(length.longValue(), bytes.length, description + " input byte length for " + name);
                }
                if (file.get("bodySha256") instanceof String digest) {
                    assertEquals(digest, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)),
                            description + " input digest for " + name);
                }
            }
        }
    }

    private void verifyRemoteFiles(String invocationId, SftpTriggerSnapshotServer.Inspection inspection,
                                   Map<String, byte[]> remoteContents) throws Exception {
        String description = "SFTP trigger invocation '" + invocationId + "'";
        for (Map.Entry<String, byte[]> file : publications.entrySet()) {
            assertTrue(inspection.exists(file.getKey()), description + " remote file must remain: " + file.getKey());
            assertArrayEquals(file.getValue(), read(inspection, remoteContents, file.getKey()),
                    description + " unchanged remote content: " + file.getKey());
        }
        for (Exchange input : received) {
            String name = input.getMessage().getHeader(Exchange.FILE_NAME_ONLY, String.class);
            assertArrayEquals(input.getMessage().getBody(byte[].class), read(inspection, remoteContents, name),
                    description + " retained remote input bytes for " + name);
        }
        if (controls.get("expectedRemoteFiles") instanceof Map<?, ?> files) {
            for (Map.Entry<?, ?> file : files.entrySet()) {
                assertArrayEquals(String.valueOf(file.getValue()).getBytes(StandardCharsets.UTF_8),
                        read(inspection, remoteContents, String.valueOf(file.getKey())),
                        description + " independently observed remote file");
            }
        }
    }

    private static byte[] read(SftpTriggerSnapshotServer.Inspection inspection, Map<String, byte[]> remoteContents,
                               String name) throws Exception {
        byte[] bytes = remoteContents.get(name);
        if (bytes == null) {
            bytes = inspection.read(name);
            remoteContents.put(name, bytes);
        }
        return bytes;
    }

    private void assertCount(String name, long actual, int defaultValue, String description) {
        assertEquals(number(controls, name, defaultValue), actual, () -> description + ' ' + name + ' ' + diagnostics());
    }

    private static void assertBody(Object expected, Object actual, String description) {
        Object value = actual instanceof byte[] bytes ? new String(bytes, StandardCharsets.UTF_8) : actual;
        SnapshotValueAssertions.assertMatches(MAPPER.valueToTree(expected), MAPPER.valueToTree(value), description);
    }

    void afterPublish(AfterPublish action) {
        afterPublish = action;
    }

    void holdFirst() {
        held = new CountDownLatch(1);
        release = new CountDownLatch(1);
    }

    void awaitHeld() throws InterruptedException {
        assertNotNull(held, "SFTP delivery gate must be configured.");
        assertTrue(held.await(20, TimeUnit.SECONDS), "SFTP native delivery did not enter the processing gate.");
    }

    void releaseHeld() {
        if (release != null) {
            release.countDown();
        }
    }

    void interruptNextDownload() {
        transferInterruptionRequested = true;
        requiredInterruption = server.interruptionCount() + 1;
        server.interruptAfterServerBytes(65536);
    }

    void endpoint(SftpEndpoint replacement) {
        endpoint = replacement;
    }

    void assertNothingDelivered() {
        assertTrue(received.isEmpty() && results.isEmpty(), "SFTP trigger processed a file while its consumer was unavailable.");
    }

    List<String> publishedNames() {
        return List.copyOf(publications.keySet());
    }

    long invocationPolls() {
        return invocationPolls;
    }

    boolean isStopped() {
        return context.getRouteController().getRouteStatus(routeId).isStopped();
    }

    String diagnostics() {
        return "polls=" + polls.completed() + ", received=" + received.size() + ", completions=" + results.size()
                + ", nativeFailures=" + polls.failures() + ", transferFailures=" + transferFailures
                + ", retainedKeys=" + retainedKeys() + ", publications=" + publications.entrySet().stream()
                .map(entry -> entry.getKey() + ':' + entry.getValue().length).toList() + ", permissionEvidence=" + faultEvidence
                + ", bodyTypes=" + observedBodyTypes + ", nativeRouteFailures=" + nativeRouteFailures
                + ", interruptions=" + server.interruptionCount() + ", interruptedBytes=" + server.interruptedServerBytes()
                + ", inspectorConnections=" + server.inspectorConnections()
                + ", failedEventIds=" + nativeFailuresByExchangeId + ", completionProvenance=" + results.stream()
                .map(result -> "id=" + result.getProperty(COMPLETION_ID) + ",correlation=" + result.getProperty(COMPLETION_CORRELATION_ID)
                        + ",failure=" + result.getException() + ",scriptLength=" + result.getProperty("snapshotSftpScriptLength")
                        + ",propertyKeys=" + result.getAllProperties().keySet()).toList();
    }

    private Throwable resolveFailure(Exchange completion) {
        String correlation = completion.getProperty(COMPLETION_CORRELATION_ID, String.class);
        String id = completion.getProperty(COMPLETION_ID, String.class);
        Throwable failure = correlation == null ? null : nativeFailuresByExchangeId.get(correlation);
        if (failure == null && id != null) {
            failure = nativeFailuresByExchangeId.get(id);
        }
        return failure == null ? completion.getException() : failure;
    }

    private static boolean hasIoCause(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof IOException) {
                return true;
            }
        }
        return false;
    }

    void faultEvidence(Map<String, Object> evidence) {
        faultEvidence = Map.copyOf(evidence);
    }

    private static String nativeThreadStacks() {
        StringBuilder result = new StringBuilder("\nSFTP native threads:");
        Thread.getAllStackTraces().forEach((thread, frames) -> {
            if (Arrays.stream(frames).anyMatch(frame -> frame.getClassName().contains("SftpOperations")
                    || frame.getClassName().contains("ChannelSftp") || frame.getClassName().contains("QuartzScheduledPollConsumerJob"))) {
                result.append('\n').append(thread.getName()).append(' ').append(thread.getState());
                Arrays.stream(frames).limit(30).forEach(frame -> result.append("\n  at ").append(frame));
            }
        });
        return result.toString();
    }

    Set<String> retainedKeys() {
        if (endpoint.getIdempotentRepository() instanceof MemoryIdempotentRepository repository) {
            return Set.copyOf(repository.getCache().keySet());
        }
        return Set.of();
    }

    static int number(Map<?, ?> options, String name, int fallback) {
        return options.get(name) instanceof Number value ? value.intValue() : fallback;
    }

    static void await(BooleanSupplier condition, String description) {
        var limiter = RateLimiterBuilder.newBuilder().withRate(25, TimeUnit.SECONDS).withConstantThroughput().build();
        try {
            Unreliables.retryUntilTrue(20, TimeUnit.SECONDS, () -> limiter.getWhenReady(condition::getAsBoolean));
        } catch (RuntimeException exception) {
            throw new AssertionError("Timed out waiting for " + description + '.', exception);
        }
    }
}
