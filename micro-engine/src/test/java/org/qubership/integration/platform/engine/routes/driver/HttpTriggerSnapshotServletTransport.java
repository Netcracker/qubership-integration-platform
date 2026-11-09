package org.qubership.integration.platform.engine.routes.driver;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.undertow.httpcore.BufferAllocator;
import io.undertow.server.DefaultExchangeHandler;
import io.undertow.servlet.Servlets;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.servlet.api.DeploymentManager;
import io.undertow.servlet.api.ServletContainer;
import io.undertow.servlet.util.ImmediateInstanceFactory;
import io.undertow.vertx.VertxHttpExchange;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServer;
import org.apache.camel.CamelContext;
import org.qubership.integration.platform.engine.camel.components.servlet.CustomCamelHttpTransportServlet;
import org.qubership.integration.platform.engine.camel.components.servlet.ServletCustomComponent;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class HttpTriggerSnapshotServletTransport implements AutoCloseable {
    private final ServletContainer container = Servlets.newContainer();
    private final CustomCamelHttpTransportServlet servlet = new CustomCamelHttpTransportServlet();
    private final BufferAllocator allocator = new ServletBufferAllocator();
    private final ExecutorService workers;
    private final Vertx vertx;
    private final DeploymentInfo deploymentInfo;
    private final DeploymentManager deployment;
    private HttpServer server;

    HttpTriggerSnapshotServletTransport(CamelContext context, int concurrentRequests) throws Exception {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        workers = Executors.newFixedThreadPool(Math.max(2, concurrentRequests), runnable -> {
            Thread thread = new Thread(runnable, "snapshot-http-servlet");
            thread.setContextClassLoader(classLoader);
            thread.setDaemon(true);
            return thread;
        });
        vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));
        String servletName = "snapshot-http-" + UUID.randomUUID();
        context.getComponent("servlet-custom", ServletCustomComponent.class).setServletName(servletName);
        deploymentInfo = Servlets.deployment()
                .setClassLoader(classLoader)
                .setContextPath("")
                .setDeploymentName(servletName)
                .setDefaultEncoding(StandardCharsets.UTF_8.name())
                .setExecutor(workers)
                .addServlet(Servlets.servlet(servletName, CustomCamelHttpTransportServlet.class,
                                new ImmediateInstanceFactory<>(servlet))
                        .addMapping("/routes/*")
                        .setLoadOnStartup(1));
        deployment = container.addDeployment(deploymentInfo);
        try {
            deployment.deploy();
            DefaultExchangeHandler handler = new DefaultExchangeHandler(deployment.start());
            server = vertx.createHttpServer().requestHandler(request ->
                    handler.handle(new VertxHttpExchange(request, allocator, workers, null)))
                    .listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            close();
            throw exception;
        }
    }

    int port() {
        return server.actualPort();
    }

    CustomCamelHttpTransportServlet servlet() {
        return servlet;
    }

    @Override
    public void close() throws Exception {
        try {
            if (server != null) {
                server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                server = null;
            }
        } finally {
            try {
                if (deployment.getState() == DeploymentManager.State.STARTED) {
                    deployment.stop();
                }
                deployment.undeploy();
                container.removeDeployment(deploymentInfo);
            } finally {
                workers.shutdownNow();
                vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
                if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("HTTP servlet workers did not stop within five seconds.");
                }
            }
        }
    }

    private static final class ServletBufferAllocator implements BufferAllocator {
        private static final int BUFFER_SIZE = 16384;

        @Override
        public ByteBuf allocateBuffer() {
            return allocateBuffer(BUFFER_SIZE);
        }

        @Override
        public ByteBuf allocateBuffer(boolean direct) {
            return allocateBuffer(direct, BUFFER_SIZE);
        }

        @Override
        public ByteBuf allocateBuffer(int size) {
            return PooledByteBufAllocator.DEFAULT.buffer(size);
        }

        @Override
        public ByteBuf allocateBuffer(boolean direct, int size) {
            return direct ? PooledByteBufAllocator.DEFAULT.directBuffer(size)
                    : PooledByteBufAllocator.DEFAULT.heapBuffer(size);
        }

        @Override
        public int getBufferSize() {
            return BUFFER_SIZE;
        }
    }
}
