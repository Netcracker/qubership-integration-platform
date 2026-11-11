package org.qubership.integration.platform.engine.routes.fixture;

import io.grpc.ManagedChannelBuilder;
import io.grpc.ManagedChannelProvider;
import io.grpc.ManagedChannelRegistry;
import io.grpc.netty.NettyChannelBuilder;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

final class PubSubSubscriberChannelRedirect extends ManagedChannelProvider implements AutoCloseable {
    private static final String PUBSUB_HOST = "pubsub.googleapis.com";
    private final int port;
    private final AtomicInteger redirectedChannels = new AtomicInteger();

    PubSubSubscriberChannelRedirect(int port) {
        this.port = port;
        ManagedChannelRegistry.getDefaultRegistry().register(this);
    }

    @Override
    protected boolean isAvailable() {
        return true;
    }

    @Override
    protected int priority() {
        return 10;
    }

    @Override
    protected ManagedChannelBuilder<?> builderForAddress(String address, int targetPort) {
        return PUBSUB_HOST.equals(address) && targetPort == 443
                ? localChannel() : NettyChannelBuilder.forAddress(address, targetPort);
    }

    @Override
    protected ManagedChannelBuilder<?> builderForTarget(String target) {
        return (PUBSUB_HOST + ":443").equals(target) || ("dns:///" + PUBSUB_HOST + ":443").equals(target)
                ? localChannel() : NettyChannelBuilder.forTarget(target);
    }

    @Override
    protected Collection<Class<? extends SocketAddress>> getSupportedSocketAddressTypes() {
        return List.of(InetSocketAddress.class);
    }

    int redirectedChannels() {
        return redirectedChannels.get();
    }

    private ManagedChannelBuilder<?> localChannel() {
        redirectedChannels.incrementAndGet();
        return NettyChannelBuilder.forAddress("localhost", port).overrideAuthority("localhost");
    }

    @Override
    public void close() {
        ManagedChannelRegistry.getDefaultRegistry().deregister(this);
    }
}
