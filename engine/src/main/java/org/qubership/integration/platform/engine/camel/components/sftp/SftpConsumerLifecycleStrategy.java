package org.qubership.integration.platform.engine.camel.components.sftp;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ExchangePropertyKey;
import org.apache.camel.Route;
import org.apache.camel.Service;
import org.apache.camel.component.file.GenericFile;
import org.apache.camel.component.file.GenericFileHelper;
import org.apache.camel.component.file.GenericFileOperationFailedException;
import org.apache.camel.component.file.remote.RemoteFileEndpoint;
import org.apache.camel.component.file.remote.SftpConsumer;
import org.apache.camel.component.file.remote.SftpRemoteFile;
import org.apache.camel.spi.ExceptionHandler;
import org.apache.camel.support.LifecycleStrategySupport;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public class SftpConsumerLifecycleStrategy extends LifecycleStrategySupport {

    private final Set<SftpConsumer> configuredConsumers = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

    @Override
    public void onServiceAdd(CamelContext context, Service service, Route route) {
        if (service instanceof SftpConsumer consumer && configuredConsumers.add(consumer)) {
            consumer.setExceptionHandler(new DownloadExceptionHandler(consumer, consumer.getExceptionHandler()));
        }
    }

    private record DownloadExceptionHandler(SftpConsumer consumer, ExceptionHandler delegate) implements ExceptionHandler {

        @Override
        public void handleException(Throwable exception) {
            delegate.handleException(exception);
        }

        @Override
        public void handleException(String message, Throwable exception) {
            delegate.handleException(message, exception);
        }

        @Override
        public void handleException(String message, Exchange exchange, Throwable exception) {
            if (exception instanceof GenericFileOperationFailedException && exchange != null && consumer.isRetrieveFile()) {
                releaseFailedDownload(exchange);
            }
            delegate.handleException(message, exchange, exception);
        }

        private void releaseFailedDownload(Exchange exchange) {
            RemoteFileEndpoint<SftpRemoteFile> endpoint = consumer.getEndpoint();
            GenericFile<SftpRemoteFile> file = exchange.getProperty(ExchangePropertyKey.FILE_EXCHANGE_FILE, GenericFile.class);
            if (file == null || file.getBody() != null || !Boolean.TRUE.equals(endpoint.isIdempotent())
                    || !endpoint.isIdempotentEager()) {
                return;
            }

            // CAMEL-25016: a failed download has no GenericFileOnCompletion to release its eager reservation.
            String key = file.getAbsoluteFilePath();
            if (endpoint.getIdempotentKey() != null) {
                Exchange dummy = GenericFileHelper.createDummy(endpoint, exchange, () -> file);
                key = endpoint.getIdempotentKey().evaluate(dummy, String.class);
            }
            if (key != null) {
                endpoint.getIdempotentRepository().remove(key);
            }
        }
    }
}
