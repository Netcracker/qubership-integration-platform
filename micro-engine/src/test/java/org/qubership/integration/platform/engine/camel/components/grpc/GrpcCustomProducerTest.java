package org.qubership.integration.platform.engine.camel.components.grpc;

import io.grpc.InternalChannelz;
import io.grpc.InternalInstrumented;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.stub.ServerCalls;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.grpc.MetricCollectingClientInterceptor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.camel.CamelContext;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.spi.ClassResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.engine.camel.CipCustomClassResolver;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class GrpcCustomProducerTest {
    private static final String JWT_TARGET = "localhost:50052";
    private static final MethodDescriptor.Marshaller<String> MARSHALLER = new StringMarshaller();
    private static final MethodDescriptor<String, String> ECHO = MethodDescriptor.<String, String>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName("test.grpc1170.Echo/Echo")
            .setRequestMarshaller(MARSHALLER)
            .setResponseMarshaller(MARSHALLER)
            .build();

    @TempDir
    static Path tempDir;

    private static Server server;
    private static ClassLoader v1;
    private static ClassLoader v2;

    private MeterRegistry meterRegistry;
    private CamelContext context;
    private ProducerTemplate template;

    @BeforeAll
    static void startServerAndCompileLibraries() throws Exception {
        server = ServerBuilder.forPort(0)
                .addService(ServerServiceDefinition.builder("test.grpc1170.Echo")
                        .addMethod(ECHO, ServerCalls.asyncUnaryCall((request, observer) -> {
                            observer.onNext(request);
                            observer.onCompleted();
                        }))
                        .build())
                .build()
                .start();
        v1 = compileLibrary("v1");
        v2 = compileLibrary("v2");
    }

    @AfterAll
    static void stopServer() {
        server.shutdownNow();
    }

    @BeforeEach
    void setUp() throws Exception {
        meterRegistry = new SimpleMeterRegistry();
        context = new DefaultCamelContext();
        context.addComponent("grpc", new GrpcCustomComponent());
        // Bound before the routes, as a micro domain binds the interceptor of each gRPC element.
        context.getRegistry().bind("element-v1", metricInterceptor("element-v1"));
        context.getRegistry().bind("element-v2", metricInterceptor("element-v2"));
        context.getRegistry().bind("element-undiscovered", metricInterceptor("element-undiscovered"));
        String uri = "grpc://localhost:" + server.getPort() + "/test.grpc1170.Echo?method=echo";
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:v1").routeId("v1").routeGroup("chain-v1")
                        .step("element-v1").to(uri + "&synchronous=true").end();
                from("direct:v2").routeId("v2").routeGroup("chain-v2")
                        .step("element-v2").to(uri + "&synchronous=true").end();
                from("direct:undiscovered").routeId("undiscovered").routeGroup("chain-undiscovered")
                        .step("element-undiscovered")
                        .to(uri + "&synchronous=true&autoDiscoverClientInterceptors=false")
                        .end();
                from("direct:async").routeId("async").routeGroup("chain-async")
                        .step("element-async").to(uri + "&synchronous=false").end();
                from("direct:missing").routeId("missing").routeGroup("chain-missing")
                        .step("element-missing").to(uri + "&synchronous=true").end();
                from("direct:jwt").routeId("jwt").routeGroup("chain-jwt")
                        .step("element-jwt")
                        .to("grpc://" + JWT_TARGET + "/test.grpc1170.Echo?method=echo&synchronous=true"
                                + "&authenticationType=JWT")
                        .end();
            }
        });
        context.start();

        // Bound after start, as AddClassResolverAction binds the chain's resolver on RouteAddedEvent.
        bindChainClassResolver("v1", v1);
        bindChainClassResolver("v2", v2);
        bindChainClassResolver("undiscovered", v1);
        bindChainClassResolver("async", v1);
        bindChainClassResolver("missing", new URLClassLoader(new URL[0], GrpcCustomProducerTest.class.getClassLoader()));
        bindChainClassResolver("jwt", v1);
        template = context.createProducerTemplate();
    }

    @AfterEach
    void tearDown() {
        context.stop();
    }

    @Test
    void shouldCallTheStubOfTheChainLibraryWhenTheResolverIsBoundAfterStart() {
        assertEquals("v1:hi", template.requestBody("direct:v1", "hi"));
    }

    @Test
    void shouldCallEachChainItsOwnLibraryVersion() {
        assertEquals("v1:hi", template.requestBody("direct:v1", "hi"));
        assertEquals("v2:hi", template.requestBody("direct:v2", "hi"));
        assertEquals("v1:again", template.requestBody("direct:v1", "again"));
    }

    @Test
    void shouldCallTheAsynchronousStubOfTheChainLibrary() {
        assertEquals(List.of("v1:hi"), template.requestBody("direct:async", "hi"));
    }

    @Test
    void shouldFailOnlyTheCallWhenTheChainLibraryLacksTheStub() {
        CamelExecutionException exception = assertThrows(CamelExecutionException.class,
                () -> template.requestBody("direct:missing", "hi"));

        assertTrue(exception.getCause().getMessage().contains("test.grpc1170.EchoGrpc"),
                exception.getCause().getMessage());
        assertEquals("v1:hi", template.requestBody("direct:v1", "hi"));
    }

    @Test
    void shouldRecordTheCallOnlyInTheMetricsOfTheCallingElement() {
        assertEquals("v1:hi", template.requestBody("direct:v1", "hi"));

        assertEquals(1, requestsSent("element-v1").count());
        assertNull(meterRegistry.find("grpc.client.requests.sent").tag("element", "element-v2").counter());
    }

    @Test
    void shouldRecordNoMetricsWhenInterceptorDiscoveryIsOff() {
        assertEquals("v1:hi", template.requestBody("direct:undiscovered", "hi"));

        assertNull(meterRegistry.find("grpc.client.requests.sent").counter());
    }

    @Test
    void shouldOpenOneChannelWhenTheStockProducerFailsToStart() throws Exception {
        long channelsBefore = channelsTo(JWT_TARGET);

        assertThrows(CamelExecutionException.class, () -> template.requestBody("direct:jwt", "hi"));
        assertThrows(CamelExecutionException.class, () -> template.requestBody("direct:jwt", "hi"));

        assertEquals(channelsBefore + 1, channelsTo(JWT_TARGET));
    }

    private static long channelsTo(String target) throws Exception {
        long count = 0;
        for (InternalInstrumented<InternalChannelz.ChannelStats> channel
                : InternalChannelz.instance().getRootChannels(0, Integer.MAX_VALUE).channels) {
            if (channel.getStats().get().target.contains(target)) {
                count++;
            }
        }
        return count;
    }

    private Counter requestsSent(String element) {
        return meterRegistry.get("grpc.client.requests.sent").tag("element", element).counter();
    }

    private MetricCollectingClientInterceptor metricInterceptor(String element) {
        return new MetricCollectingClientInterceptor(meterRegistry,
                counter -> counter.tag("element", element), timer -> timer.tag("element", element), Status.Code.OK);
    }

    private void bindChainClassResolver(String routeId, ClassLoader classLoader) {
        MetadataUtil.addBean(context.getRoute(routeId), ClassResolver.class, new CipCustomClassResolver(classLoader));
    }

    private static ClassLoader compileLibrary(String version) throws Exception {
        Path sources = Files.createDirectories(tempDir.resolve(version).resolve("src/test/grpc1170"));
        Path classes = Files.createDirectories(tempDir.resolve(version).resolve("classes"));
        Path source = Files.writeString(sources.resolve("EchoGrpc.java"), """
                package test.grpc1170;

                import io.grpc.CallOptions;
                import io.grpc.Channel;
                import io.grpc.MethodDescriptor;
                import io.grpc.stub.ClientCalls;
                import io.grpc.stub.StreamObserver;

                import java.io.ByteArrayInputStream;
                import java.io.IOException;
                import java.io.InputStream;
                import java.io.UncheckedIOException;
                import java.nio.charset.StandardCharsets;

                public final class EchoGrpc {
                    private static final MethodDescriptor.Marshaller<String> MARSHALLER =
                            new MethodDescriptor.Marshaller<>() {
                                public InputStream stream(String value) {
                                    return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
                                }

                                public String parse(InputStream stream) {
                                    try {
                                        return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                                    } catch (IOException exception) {
                                        throw new UncheckedIOException(exception);
                                    }
                                }
                            };
                    private static final MethodDescriptor<String, String> ECHO =
                            MethodDescriptor.<String, String>newBuilder()
                                    .setType(MethodDescriptor.MethodType.UNARY)
                                    .setFullMethodName("test.grpc1170.Echo/Echo")
                                    .setRequestMarshaller(MARSHALLER)
                                    .setResponseMarshaller(MARSHALLER)
                                    .build();

                    public static EchoBlockingStub newBlockingStub(Channel channel) {
                        return new EchoBlockingStub(channel);
                    }

                    public static EchoStub newStub(Channel channel) {
                        return new EchoStub(channel);
                    }

                    public abstract static class EchoImplBase {
                    }

                    public static final class EchoBlockingStub {
                        private final Channel channel;

                        EchoBlockingStub(Channel channel) {
                            this.channel = channel;
                        }

                        public String echo(String request) {
                            return "%1$s:" + ClientCalls.blockingUnaryCall(channel, ECHO, CallOptions.DEFAULT, request);
                        }
                    }

                    public static final class EchoStub {
                        private final Channel channel;

                        EchoStub(Channel channel) {
                            this.channel = channel;
                        }

                        public void echo(String request, StreamObserver<String> observer) {
                            ClientCalls.asyncUnaryCall(channel.newCall(ECHO, CallOptions.DEFAULT), request,
                                    new StreamObserver<>() {
                                        public void onNext(String value) {
                                            observer.onNext("%1$s:" + value);
                                        }

                                        public void onError(Throwable error) {
                                            observer.onError(error);
                                        }

                                        public void onCompleted() {
                                            observer.onCompleted();
                                        }
                                    });
                        }
                    }
                }
                """.formatted(version));
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        int exitCode = compiler.run(null, null, null, "-classpath", System.getProperty("java.class.path"),
                "-d", classes.toString(), source.toString());
        assertEquals(0, exitCode, "the test library did not compile");
        return new URLClassLoader(new URL[] {classes.toUri().toURL()}, GrpcCustomProducerTest.class.getClassLoader());
    }

    private static final class StringMarshaller implements MethodDescriptor.Marshaller<String> {
        @Override
        public InputStream stream(String value) {
            return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public String parse(InputStream stream) {
            try {
                return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }
}
