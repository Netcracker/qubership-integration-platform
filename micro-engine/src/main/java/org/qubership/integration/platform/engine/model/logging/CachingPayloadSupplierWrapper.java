package org.qubership.integration.platform.engine.model.logging;

import org.qubership.integration.platform.engine.model.SessionElementProperty;

import java.util.Map;
import java.util.function.Supplier;

public class CachingPayloadSupplierWrapper implements Payload {
    private static class CachingSupplier<T> implements Supplier<T> {
        private final Supplier<T> delegate;
        private T cached;

        public CachingSupplier(Supplier<T> delegate) {
            this.delegate = delegate;
            this.cached = null;
        }

        @Override
        public T get() {
            synchronized (this) {
                if (cached == null) {
                    cached = delegate.get();
                }
            }
            return cached;
        }
    }

    private final Supplier<Map<String, String>> headerSupplier;
    private final Supplier<Map<String, String>> contextSupplier;
    private final Supplier<Map<String, SessionElementProperty>> propertiesSupplier;
    private final Supplier<String> bodySupplier;

    public CachingPayloadSupplierWrapper(
            Supplier<Map<String, String>> headerSupplier,
            Supplier<Map<String, String>> contextSupplier,
            Supplier<Map<String, SessionElementProperty>> propertiesSupplier,
            Supplier<String> bodySupplier
    ) {
        this.headerSupplier = new CachingSupplier<>(headerSupplier);
        this.contextSupplier = new CachingSupplier<>(contextSupplier);
        this.propertiesSupplier = new CachingSupplier<>(propertiesSupplier);
        this.bodySupplier = new CachingSupplier<>(bodySupplier);
    }

    @Override
    public Map<String, String> getHeaders() {
        return headerSupplier.get();
    }

    @Override
    public Map<String, String> getContext() {
        return contextSupplier.get();
    }

    @Override
    public Map<String, SessionElementProperty> getProperties() {
        return propertiesSupplier.get();
    }

    @Override
    public String getBody() {
        return bodySupplier.get();
    }
}
