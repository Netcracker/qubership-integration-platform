package org.qubership.integration.platform.engine.camel.components.grpc;

import io.grpc.InternalChannelz;
import io.grpc.InternalInstrumented;
import org.apache.camel.CamelContext;
import org.apache.camel.CamelExecutionException;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.spi.ClassResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.engine.camel.CipCustomClassResolver;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class GrpcCustomProducerTest {
    private static final String URI = "grpc://localhost:50051/test.grpc1170.Echo?method=echo";
    private static final String JWT_TARGET = "localhost:50052";

    @TempDir
    static Path tempDir;

    private static ClassLoader v1;
    private static ClassLoader v2;

    private CamelContext context;
    private ProducerTemplate template;

    @BeforeAll
    static void compileLibraries() throws Exception {
        v1 = compileLibrary("v1");
        v2 = compileLibrary("v2");
    }

    @BeforeEach
    void setUp() throws Exception {
        context = new DefaultCamelContext();
        context.addComponent("grpc", new GrpcCustomComponent());
        context.addRoutes(new RouteBuilder() {
            @Override
            public void configure() {
                from("direct:v1").routeId("v1").routeGroup("chain-v1").to(URI + "&synchronous=true");
                from("direct:v2").routeId("v2").routeGroup("chain-v2").to(URI + "&synchronous=true");
                from("direct:async").routeId("async").routeGroup("chain-async").to(URI + "&synchronous=false");
                from("direct:missing").routeId("missing").routeGroup("chain-missing").to(URI + "&synchronous=true");
                from("direct:jwt").routeId("jwt").routeGroup("chain-jwt")
                        .to("grpc://" + JWT_TARGET + "/test.grpc1170.Echo?method=echo&synchronous=true"
                                + "&authenticationType=JWT");
            }
        });
        context.start();

        // Bound after start, as AddClassResolverAction binds the chain's resolver on RouteAddedEvent.
        bindChainClassResolver("v1", v1);
        bindChainClassResolver("v2", v2);
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

    private void bindChainClassResolver(String routeId, ClassLoader classLoader) {
        MetadataUtil.addBean(context.getRoute(routeId), ClassResolver.class, new CipCustomClassResolver(classLoader));
    }

    private static ClassLoader compileLibrary(String version) throws Exception {
        Path sources = Files.createDirectories(tempDir.resolve(version).resolve("src/test/grpc1170"));
        Path classes = Files.createDirectories(tempDir.resolve(version).resolve("classes"));
        Path source = Files.writeString(sources.resolve("EchoGrpc.java"), """
                package test.grpc1170;

                public final class EchoGrpc {
                    public static EchoBlockingStub newBlockingStub(io.grpc.Channel channel) {
                        return new EchoBlockingStub();
                    }

                    public static EchoStub newStub(io.grpc.Channel channel) {
                        return new EchoStub();
                    }

                    public abstract static class EchoImplBase {
                    }

                    public static final class EchoBlockingStub {
                        public String echo(String request) {
                            return "%1$s:" + request;
                        }
                    }

                    public static final class EchoStub {
                        public void echo(String request, io.grpc.stub.StreamObserver<String> observer) {
                            observer.onNext("%1$s:" + request);
                            observer.onCompleted();
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
}
