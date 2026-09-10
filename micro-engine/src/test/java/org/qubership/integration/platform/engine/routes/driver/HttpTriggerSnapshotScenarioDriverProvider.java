package org.qubership.integration.platform.engine.routes.driver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.AdviceWith;
import org.apache.camel.model.ModelCamelContext;
import org.apache.camel.model.OnCompletionDefinition;
import org.apache.camel.model.ProcessDefinition;
import org.apache.camel.model.ProcessorDefinition;
import org.apache.camel.model.RouteDefinition;
import org.apache.camel.support.DefaultExchange;
import org.qubership.integration.platform.engine.camel.CorrelationIdSetter;
import org.qubership.integration.platform.engine.camel.JsonMessageValidator;
import org.qubership.integration.platform.engine.camel.components.servlet.exception.ChainGlobalExceptionHandler;
import org.qubership.integration.platform.engine.camel.processors.ChainExceptionResponseHandlerProcessor;
import org.qubership.integration.platform.engine.camel.processors.HttpTriggerProcessor;
import org.qubership.integration.platform.engine.camel.processors.InterruptExchangeProcessor;
import org.qubership.integration.platform.engine.camel.processors.session.ChainStartProcessor;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.ChainProperties;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Headers;
import org.qubership.integration.platform.engine.model.constants.CamelConstants.Properties;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExchangeHeaders;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionScenario;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioDriverDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotScenarioInvocation;
import org.qubership.integration.platform.engine.routes.fixture.SnapshotFixtureRouteScope;
import org.qubership.integration.platform.engine.service.debugger.util.ChainExceptionResponseHandlerService;
import org.qubership.integration.platform.engine.testutils.ObjectMappers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class HttpTriggerSnapshotScenarioDriverProvider implements SnapshotScenarioDriverProvider {
    private static final String PROVIDER_ID = "http-trigger";

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public Map<String, Object> configurationParameters(SnapshotScenarioDriverDefinition definition) {
        return Map.of();
    }

    @Override
    public SnapshotScenarioDriver create(
            SnapshotExecutionScenario scenario,
            SnapshotScenarioDriverDefinition definition,
            List<SnapshotScenarioInvocation> invocations
    ) {
        return new HttpTriggerSnapshotScenarioDriver(scenario, definition, invocations);
    }

    private static final class HttpTriggerSnapshotScenarioDriver implements SnapshotScenarioDriver {
        private static final String SERVLET_ENDPOINT_PREFIX = "servlet-custom:";
        private static final String PUBLIC_ROUTES_PREFIX = "/routes/";
        private static final String METHOD_PARAMETER = "method";
        private static final String PATH_PARAMETER = "path";
        private static final String HTTP_METHOD_RESTRICT_PARAMETER = "httpMethodRestrict";
        private static final String HTTP_BINDING_PARAMETER = "httpBinding";
        private static final String TAGS_PROVIDER_PARAMETER = "tagsProvider";
        private static final String PRODUCTION_HTTP_BINDING = "handlingHttpBinding";
        private static final Pattern PATH_VARIABLE_PATTERN = Pattern.compile("\\{[^/{}]+}");
        private static final long SERVER_TERMINATION_TIMEOUT_SECONDS = 5;
        private static final long COMPLETION_PROCESSOR_TIMEOUT_SECONDS = 5;
        private static final String SERVER_THREAD_NAME = "snapshot-http-trigger";

        private final SnapshotExecutionScenario scenario;
        private final ObjectMapper objectMapper = ObjectMappers.getObjectMapper();
        private final Map<String, RequestTarget> requestsByInvocationId = new LinkedHashMap<>();
        private final Map<String, TriggerBinding> bindingsByInvocationId = new LinkedHashMap<>();
        private final List<TriggerBinding> bindings = new ArrayList<>();
        private final AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
        private final AtomicReference<Exchange> routedExchange = new AtomicReference<>();
        private final Object lifecycleMonitor = new Object();

        private HttpServer server;
        private ExecutorService serverExecutor;
        private boolean stopping;

        private HttpTriggerSnapshotScenarioDriver(
                SnapshotExecutionScenario scenario,
                SnapshotScenarioDriverDefinition definition,
                List<SnapshotScenarioInvocation> invocations
        ) {
            this.scenario = scenario;
            invocations.forEach(invocation -> {
                SnapshotScenarioDriverDefinition requestDefinition = invocation.getDriver() == null
                        ? definition : invocation.getDriver();
                String method = requiredParameter(requestDefinition, METHOD_PARAMETER).toUpperCase(Locale.ROOT);
                String path = requiredParameter(requestDefinition, PATH_PARAMETER);
                validateRequestPath(scenario.getId(), path);
                requestsByInvocationId.put(invocation.getId(), new RequestTarget(method, path));
                if (!invocation.getProperties().isEmpty()) {
                    throw new IllegalArgumentException(
                            "HTTP trigger scenario '" + scenario.getId() + "' invocation '"
                                    + invocation.getId() + "' cannot define Camel exchange properties."
                    );
                }
            });
        }

        @Override
        public void configure(CamelContext camelContext) throws Exception {
            configure(camelContext, ((ModelCamelContext) camelContext).getRouteDefinitions());
        }

        @Override
        public void configure(CamelContext camelContext, List<RouteDefinition> routes) throws Exception {
            List<TriggerRoute> candidates = findTriggerRoutes(routes);
            Map<RouteDefinition, TriggerBinding> bindingsByRoute = new LinkedHashMap<>();
            requestsByInvocationId.forEach((invocationId, request) -> {
                TriggerRoute triggerRoute = selectTriggerRoute(candidates, request);
                TriggerBinding binding = bindingsByRoute.computeIfAbsent(
                        triggerRoute.route(), ignored -> new TriggerBinding(triggerRoute)
                );
                bindingsByInvocationId.put(invocationId, binding);
            });
            bindings.addAll(bindingsByRoute.values());
            Map<String, Processor> runtimeProcessors = createRuntimeProcessors();
            for (TriggerBinding binding : bindings) {
                TriggerRoute triggerRoute = binding.trigger();
                validateHttpBinding(scenario.getId(), triggerRoute.httpBinding(), triggerRoute.tagsProvider());
                Map<String, Processor> processors = new LinkedHashMap<>(runtimeProcessors);
                processors.put("chainFinishProcessor", binding.chainFinishRecorder());
                processors.put("httpTriggerFinishProcessor", binding.httpTriggerFinishRecorder());
                SnapshotFixtureRouteScope.bind(
                        camelContext,
                        List.of(triggerRoute.route()),
                        binding.directUri(),
                        processors
                );
                AdviceWith.adviceWith(camelContext, triggerRoute.route(), false, advice ->
                        advice.replaceFromWith(binding.directUri()));
            }
        }

        @Override
        public Exchange execute(
                ProducerTemplate producerTemplate,
                SnapshotScenarioInvocation invocation
        ) throws Exception {
            handlerFailure.set(null);
            routedExchange.set(null);
            HttpServer startedServer = startServer(producerTemplate);
            TriggerBinding binding = bindingsByInvocationId.get(invocation.getId());
            binding.chainFinishRecorder().reset();
            binding.httpTriggerFinishRecorder().reset();
            HttpResponse<String> response = sendRequest(startedServer, invocation);
            Throwable failure = handlerFailure.get();
            if (failure != null) {
                throw new IllegalStateException(
                        "HTTP trigger scenario '" + scenario.getId() + "' failed to handle the request.",
                        failure
                );
            }
            assertCompletionProcessorInvocation("chainFinishProcessor", binding.chainFinishRecorder());
            if (binding.requiresHttpTriggerFinish()) {
                assertCompletionProcessorInvocation("httpTriggerFinishProcessor", binding.httpTriggerFinishRecorder());
            }
            return toResultExchange(producerTemplate.getCamelContext(), response);
        }

        private HttpServer startServer(ProducerTemplate producerTemplate) throws IOException {
            synchronized (lifecycleMonitor) {
                if (stopping) {
                    throw new IllegalStateException(
                            "HTTP trigger scenario '" + scenario.getId()
                                    + "' cannot execute because its driver has stopped."
                    );
                }
                if (server != null) {
                    return server;
                }

                HttpServer createdServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
                ExecutorService createdExecutor = Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, SERVER_THREAD_NAME);
                    thread.setContextClassLoader(contextClassLoader);
                    thread.setDaemon(true);
                    return thread;
                });
                createdServer.setExecutor(createdExecutor);
                int boundPort = createdServer.getAddress().getPort();
                createdServer.createContext(
                        "/",
                        httpExchange -> handleRequest(httpExchange, producerTemplate, boundPort)
                );
                server = createdServer;
                serverExecutor = createdExecutor;
                try {
                    createdServer.start();
                } catch (RuntimeException exception) {
                    server = null;
                    serverExecutor = null;
                    createdServer.stop(0);
                    createdExecutor.shutdownNow();
                    throw exception;
                }
                return createdServer;
            }
        }

        private HttpResponse<String> sendRequest(
                HttpServer startedServer,
                SnapshotScenarioInvocation invocation
        ) throws Exception {
            RequestTarget target = requestsByInvocationId.get(invocation.getId());
            URI requestUri = URI.create(
                    "http://127.0.0.1:" + startedServer.getAddress().getPort() + target.path()
            );
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(requestUri)
                    .timeout(Duration.ofSeconds(10));
            invocation.getHeaders().forEach((name, value) ->
                    requestBuilder.header(name, String.valueOf(value)));
            requestBuilder.method(target.method(), requestBodyPublisher(invocation));

            return HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build()
                    .send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        private HttpRequest.BodyPublisher requestBodyPublisher(
                SnapshotScenarioInvocation invocation
        ) throws IOException {
            Object body = invocation.getBody();
            if (body == null) {
                return HttpRequest.BodyPublishers.noBody();
            }
            if (body instanceof String string) {
                return HttpRequest.BodyPublishers.ofString(string, StandardCharsets.UTF_8);
            }
            return HttpRequest.BodyPublishers.ofString(
                    objectMapper.writeValueAsString(body),
                    StandardCharsets.UTF_8
            );
        }

        private void handleRequest(
                com.sun.net.httpserver.HttpExchange httpExchange,
                ProducerTemplate producerTemplate,
                int serverPort
        ) throws IOException {
            try {
                byte[] requestBody = httpExchange.getRequestBody().readAllBytes();
                RequestTarget target = new RequestTarget(
                        httpExchange.getRequestMethod(), httpExchange.getRequestURI().toString()
                );
                TriggerBinding binding = bindings.stream()
                        .filter(candidate -> matchesRequest(candidate.trigger(), target))
                        .findFirst().orElseThrow();
                Exchange exchange = producerTemplate.request(binding.directUri(), request ->
                        populateRequestExchange(
                                request,
                                httpExchange,
                                requestBody,
                                serverPort,
                                binding.trigger().path()
                        ));
                routedExchange.set(exchange);
                writeResponse(httpExchange, exchange);
            } catch (Throwable throwable) {
                handlerFailure.compareAndSet(null, throwable);
                writeFailureResponse(httpExchange, throwable);
            } finally {
                httpExchange.close();
            }
        }

        private void populateRequestExchange(
                Exchange exchange,
                com.sun.net.httpserver.HttpExchange httpExchange,
                byte[] requestBody,
                int serverPort,
                String triggerPath
        ) {
            Message message = exchange.getMessage();
            message.setBody(requestBody.length == 0
                    ? null
                    : new String(requestBody, StandardCharsets.UTF_8));
            exchange.setProperty(Properties.SESSION_ID, UUID.randomUUID().toString());
            httpExchange.getRequestHeaders().forEach((name, values) ->
                    message.setHeader(name, values.size() == 1 ? values.getFirst() : List.copyOf(values)));

            URI requestUri = httpExchange.getRequestURI();
            String rawPath = requestUri.getRawPath();
            String rawQuery = requestUri.getRawQuery();
            String absoluteUrl = "http://127.0.0.1:" + serverPort + rawPath;
            if (rawQuery != null && !rawQuery.isEmpty()) {
                absoluteUrl += "?" + rawQuery;
            }
            message.setHeader(Exchange.HTTP_METHOD, httpExchange.getRequestMethod());
            message.setHeader(Exchange.HTTP_URL, absoluteUrl);
            message.setHeader(Exchange.HTTP_URI, removeLeadingSlash(rawPath));
            message.setHeader(Exchange.HTTP_PATH, publicPathToTriggerPath(rawPath));
            message.setHeader(Exchange.HTTP_QUERY, rawQuery);
            message.setHeader(Headers.URI_TEMPLATE, triggerPath);
        }

        private static void writeResponse(
                com.sun.net.httpserver.HttpExchange httpExchange,
                Exchange exchange
        ) throws IOException {
            Message message = exchange.getMessage();
            Integer configuredStatus = message.getHeader(Exchange.HTTP_RESPONSE_CODE, Integer.class);
            int status = exchange.getException() == null
                    ? configuredStatus == null ? 200 : configuredStatus
                    : 500;
            String contentType = message.getHeader(Exchange.CONTENT_TYPE, String.class);
            if (contentType != null) {
                httpExchange.getResponseHeaders().set(Exchange.CONTENT_TYPE, contentType);
            }
            String body = message.getBody(String.class);
            byte[] responseBody = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
            httpExchange.sendResponseHeaders(status, responseBody.length);
            httpExchange.getResponseBody().write(responseBody);
        }

        private static void writeFailureResponse(
                com.sun.net.httpserver.HttpExchange httpExchange,
                Throwable throwable
        ) throws IOException {
            byte[] responseBody = String.valueOf(throwable.getMessage()).getBytes(StandardCharsets.UTF_8);
            httpExchange.sendResponseHeaders(500, responseBody.length);
            httpExchange.getResponseBody().write(responseBody);
        }

        private Exchange toResultExchange(CamelContext camelContext, HttpResponse<String> response) {
            Exchange result = new DefaultExchange(camelContext);
            Exchange internalExchange = routedExchange.get();
            if (internalExchange != null) {
                internalExchange.getProperties().forEach(result::setProperty);
                result.setException(internalExchange.getException());
                SnapshotExchangeHeaders.capture(result, internalExchange);
            }
            result.getMessage().setBody(response.body());
            response.headers().map().forEach((name, values) ->
                    result.getMessage().setHeader(
                            name,
                            values.size() == 1 ? values.getFirst() : List.copyOf(values)
                    ));
            result.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, response.statusCode());
            return result;
        }

        @Override
        public void beforeContextStop() {
            synchronized (lifecycleMonitor) {
                if (stopping) {
                    return;
                }
                stopping = true;
                try {
                    if (server != null) {
                        server.stop(0);
                    }
                } finally {
                    if (serverExecutor != null) {
                        serverExecutor.shutdownNow();
                    }
                }
            }
        }

        @Override
        public void close() {
            beforeContextStop();
            ExecutorService executorToAwait;
            synchronized (lifecycleMonitor) {
                executorToAwait = serverExecutor;
            }
            if (executorToAwait == null) {
                return;
            }
            try {
                if (!executorToAwait.awaitTermination(SERVER_TERMINATION_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException(
                            "HTTP trigger scenario '" + scenario.getId()
                                    + "' handler did not stop within "
                                    + SERVER_TERMINATION_TIMEOUT_SECONDS + " seconds."
                    );
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while stopping HTTP trigger scenario '" + scenario.getId() + "' handler.",
                        exception
                );
            }
        }

        private static Map<String, Processor> createRuntimeProcessors() {
            Map<String, Processor> processors = new LinkedHashMap<>();
            ObjectMapper objectMapper = ObjectMappers.getObjectMapper();
            processors.put("chainStartProcessor", new ChainStartProcessor());
            processors.put(
                    "httpTriggerProcessor",
                    new HttpTriggerProcessor(
                            new CorrelationIdSetter(objectMapper),
                            new JsonMessageValidator(objectMapper)
                    )
            );
            processors.put("interruptExchangeProcessor", new InterruptExchangeProcessor());

            Processor exceptionResponseProcessor = new ChainExceptionResponseHandlerProcessor(
                    new ChainExceptionResponseHandlerService(
                            new ChainGlobalExceptionHandler(objectMapper)
                    )
            );
            processors.put("chainExceptionResponseHandlerProcessor", exchange -> {
                exchange.setProperty(
                        ChainProperties.FAILED_ELEMENT_ID,
                        exchange.getProperty(Properties.HTTP_TRIGGER_STEP_ID, String.class)
                );
                exceptionResponseProcessor.process(exchange);
            });
            return processors;
        }

        private static List<TriggerRoute> findTriggerRoutes(List<RouteDefinition> routes) {
            List<TriggerRoute> triggers = new ArrayList<>();
            for (RouteDefinition route : routes) {
                String endpointUri = route.getInput().getEndpointUri();
                if (endpointUri != null && endpointUri.startsWith(SERVLET_ENDPOINT_PREFIX)) {
                    triggers.add(new TriggerRoute(
                            route,
                            extractTriggerPath(endpointUri),
                            extractEndpointParameter(endpointUri, HTTP_METHOD_RESTRICT_PARAMETER),
                            extractEndpointParameter(endpointUri, HTTP_BINDING_PARAMETER),
                            extractEndpointParameter(endpointUri, TAGS_PROVIDER_PARAMETER)
                    ));
                }
            }
            return triggers;
        }

        private TriggerRoute selectTriggerRoute(List<TriggerRoute> candidates, RequestTarget request) {
            List<TriggerRoute> matches = candidates.stream()
                    .filter(candidate -> matchesRequest(candidate, request))
                    .toList();
            if (matches.size() != 1) {
                throw new IllegalArgumentException(
                        "HTTP trigger scenario '" + scenario.getId() + "' expected one servlet-custom route for "
                                + request.method() + " " + request.path() + ", but found " + matches.size() + "."
                );
            }
            return matches.getFirst();
        }

        private static boolean matchesRequest(TriggerRoute route, RequestTarget request) {
            return matchesPathTemplate(PUBLIC_ROUTES_PREFIX + route.path(), URI.create(request.path()).getPath())
                    && matchesMethod(route.methodRestriction(), request.method());
        }

        private static String extractTriggerPath(String endpointUri) {
            String pathAndParameters = endpointUri.substring(SERVLET_ENDPOINT_PREFIX.length());
            int parametersIndex = pathAndParameters.indexOf('?');
            String path = parametersIndex < 0
                    ? pathAndParameters
                    : pathAndParameters.substring(0, parametersIndex);
            return removeLeadingSlash(path);
        }

        private static boolean declaresCompletionProcessor(
                RouteDefinition route
        ) {
            return route.getOutputs().stream()
                    .filter(OnCompletionDefinition.class::isInstance)
                    .map(OnCompletionDefinition.class::cast)
                    .anyMatch(onCompletion -> containsProcessorReference(
                            onCompletion.getOutputs(),
                        "httpTriggerFinishProcessor"
                    ));
        }

        private static boolean containsProcessorReference(
                List<ProcessorDefinition<?>> definitions,
                String processorName
        ) {
            for (ProcessorDefinition<?> definition : definitions) {
                if (definition instanceof ProcessDefinition process
                        && processorName.equals(process.getRef())) {
                    return true;
                }
                if (containsProcessorReference(definition.getOutputs(), processorName)) {
                    return true;
                }
            }
            return false;
        }

        private static String requiredParameter(
                SnapshotScenarioDriverDefinition definition,
                String parameterName
        ) {
            Object value = definition.getParameters().get(parameterName);
            if (!(value instanceof String string) || string.isBlank()) {
                throw new IllegalArgumentException(
                        "HTTP trigger driver parameter '" + parameterName + "' is missing."
                );
            }
            return string.strip();
        }

        private static void validateRequestPath(String scenarioId, String requestPath) {
            URI uri = URI.create(requestPath);
            if (!requestPath.startsWith("/") || uri.isAbsolute() || uri.getRawPath() == null) {
                throw new IllegalArgumentException(
                        "HTTP trigger scenario '" + scenarioId
                                + "' request path must be an absolute HTTP path."
                );
            }
        }

        private static boolean matchesPathTemplate(String template, String path) {
            Matcher matcher = PATH_VARIABLE_PATTERN.matcher(template);
            StringBuilder regex = new StringBuilder("^");
            int previousEnd = 0;
            while (matcher.find()) {
                regex.append(Pattern.quote(template.substring(previousEnd, matcher.start())));
                regex.append("[^/]+");
                previousEnd = matcher.end();
            }
            regex.append(Pattern.quote(template.substring(previousEnd)));
            regex.append('$');
            return Pattern.matches(regex.toString(), path);
        }

        private static boolean matchesMethod(String methodRestriction, String method) {
            if (methodRestriction == null) {
                return true;
            }
            for (String allowedMethod : methodRestriction.split(",")) {
                if (method.equalsIgnoreCase(allowedMethod.strip())) {
                    return true;
                }
            }
            return false;
        }

        private static void validateHttpBinding(
                String scenarioId,
                String httpBinding,
                String tagsProvider
        ) {
            if (tagsProvider == null) {
                return;
            }
            if (PRODUCTION_HTTP_BINDING.equals(httpBinding)) {
                return;
            }
            String actualBinding = httpBinding == null || httpBinding.isBlank()
                    ? "missing"
                    : "'" + httpBinding + "'";
            throw new IllegalArgumentException(
                    "HTTP trigger scenario '" + scenarioId + "' requires servlet-custom route parameter '"
                            + HTTP_BINDING_PARAMETER + "=" + PRODUCTION_HTTP_BINDING
                            + "', but the parameter is " + actualBinding + "."
            );
        }

        private void assertCompletionProcessorInvocation(
                String processorName,
                InvocationRecorder recorder
        ) throws InterruptedException {
            if (!recorder.await()) {
                throw new IllegalStateException(
                        "HTTP trigger scenario '" + scenario.getId() + "' did not invoke '"
                                + processorName + "' within "
                                + COMPLETION_PROCESSOR_TIMEOUT_SECONDS + " seconds."
                );
            }
            int invocationCount = recorder.getInvocationCount();
            if (invocationCount != 1) {
                throw new IllegalStateException(
                        "HTTP trigger scenario '" + scenario.getId() + "' expected '"
                                + processorName + "' to run once, but it ran " + invocationCount + " times."
                );
            }
        }

        private static String extractEndpointParameter(String endpointUri, String parameterName) {
            int parametersIndex = endpointUri.indexOf('?');
            if (parametersIndex < 0) {
                return null;
            }
            for (String parameter : endpointUri.substring(parametersIndex + 1).split("&")) {
                int valueIndex = parameter.indexOf('=');
                String name = valueIndex < 0 ? parameter : parameter.substring(0, valueIndex);
                if (parameterName.equals(name)) {
                    return valueIndex < 0 ? "" : parameter.substring(valueIndex + 1);
                }
            }
            return null;
        }

        private static String publicPathToTriggerPath(String path) {
            return path.startsWith("/routes") ? path.substring("/routes".length()) : path;
        }

        private static String removeLeadingSlash(String value) {
            return value.startsWith("/") ? value.substring(1) : value;
        }

        private record TriggerRoute(
                RouteDefinition route,
                String path,
                String methodRestriction,
                String httpBinding,
                String tagsProvider
        ) {
        }

        private record RequestTarget(String method, String path) {
        }

        private record TriggerBinding(
                TriggerRoute trigger,
                String directUri,
                boolean requiresHttpTriggerFinish,
                InvocationRecorder chainFinishRecorder,
                InvocationRecorder httpTriggerFinishRecorder
        ) {
            private TriggerBinding(TriggerRoute trigger) {
                this(
                        trigger,
                        "direct:snapshot-http-trigger-" + UUID.randomUUID(),
                        declaresCompletionProcessor(trigger.route()),
                        new InvocationRecorder(),
                        new InvocationRecorder()
                );
            }
        }

        private static final class InvocationRecorder implements Processor {
            private final AtomicReference<InvocationRecording> recording =
                    new AtomicReference<>(new InvocationRecording());

            @Override
            public void process(Exchange exchange) {
                InvocationRecording activeRecording = recording.get();
                activeRecording.invocationCount().incrementAndGet();
                activeRecording.invocationRecorded().countDown();
            }

            private void reset() {
                recording.set(new InvocationRecording());
            }

            private boolean await() throws InterruptedException {
                return recording.get().invocationRecorded().await(HttpTriggerSnapshotScenarioDriver.COMPLETION_PROCESSOR_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            }

            private int getInvocationCount() {
                return recording.get().invocationCount().get();
            }
        }

        private record InvocationRecording(
                AtomicInteger invocationCount,
                CountDownLatch invocationRecorded
        ) {
            private InvocationRecording() {
                this(new AtomicInteger(), new CountDownLatch(1));
            }
        }
    }
}
