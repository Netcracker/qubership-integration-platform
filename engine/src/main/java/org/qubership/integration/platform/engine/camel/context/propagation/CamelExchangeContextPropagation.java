package org.qubership.integration.platform.engine.camel.context.propagation;

import com.netcracker.cloud.context.propagation.core.ContextManager;
import com.netcracker.cloud.context.propagation.core.RequestContextPropagation;
import com.netcracker.cloud.context.propagation.core.contextdata.IncomingContextData;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.map.CaseInsensitiveMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Objects.nonNull;

@Slf4j
@Component
@ConditionalOnMissingBean(CamelExchangeContextPropagation.class)
public class CamelExchangeContextPropagation {

    private final ContextPropsProvider contextPropsProvider;

    public CamelExchangeContextPropagation(ContextPropsProvider contextPropsProvider) {
        this.contextPropsProvider = contextPropsProvider;
    }

    public void initRequestContext(Map<String, Object> headers) {
        try {
            initRequestContextUnhandled(headers);
        } catch (Exception e) {
            log.warn("Failed to initialize request context for camel exchange headers", e);
        }
    }

    public Map<String, Object> createContextSnapshot() {
        return ContextManager.createContextSnapshot();
    }


    /**
     * Build map with context objects (propagated parameters) - Map[header_name, context_string_value]
     */
    public Map<String, String> buildContextSnapshotForSessions() {
        try {
            Map<String, String> contextHeaders = new HashMap<>();
            propagateHeaders(contextHeaders);
            return contextHeaders;
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.info("Error while logging in context snapshot build", e);
            }
            return Collections.emptyMap();
        }
    }

    public void activateContextSnapshot(Map<String, Object> snapshot) {
        ContextManager.activateContextSnapshot(snapshot);
    }

    public void removeContextHeaders(Map<String, Object> exchangeHeaders) {
        Set<String> downstreamHeaders = ContextManager.executeWithContext(
            getContextForHeaders(new CamelExchangeRequestContextData(exchangeHeaders)),
                contextPropsProvider::getDownstreamHeaders);
        exchangeHeaders.entrySet().removeIf(entry -> downstreamHeaders.contains(entry.getKey()));
    }

    public void clear() {
        RequestContextPropagation.clear();
    }

    public <T, R> R  getSafeContext(String contextName, Function<T, R> getter) {
        T object = (T) ContextManager.getSafe(contextName).orElse(null);
        return object != null ? getter.apply(object) : null;
    }

    public void propagateExchangeHeaders(Map<String, Object> headers) {
        RequestContextPropagation.populateResponse(new CamelExchangeResponseContextData(headers));
    }

    public void propagateHeaders(Map<String, String> headers) {
        RequestContextPropagation.populateResponse((name, values) -> {
            if (name == null || values == null) {
                return;
            }

            headers.put(name, values.toString());
        });
    }

    public void overrideHeadersForCurrentContext(Map<String, Object> headers) {
        Map<String, Object> contextHeaders = getHeadersForCurrentContext();
        contextHeaders.putAll(headers);
        getContextForHeaders(new CamelExchangeOverrideContextData(contextHeaders))
                .entrySet()
                .stream()
                .filter(entry -> nonNull(entry.getValue()))
                .forEach(entry -> ContextManager.set(entry.getKey(), entry.getValue()));
    }

    public Map<String, Object> getHeadersForCurrentContext() {
        Map<String, Object> headers = new CaseInsensitiveMap<>();
        RequestContextPropagation.populateResponse(new CamelExchangeResponseContextData(headers));
        return headers;
    }

    public Map<String, Object> getHeadersForContext(Map<String, Object> context) {
        return ContextManager.executeWithContext(context, this::getHeadersForCurrentContext);
    }

    public Map<String, Object> getContextForHeaders(IncomingContextData incomingContextData) {
        return ContextManager.getContextProviders().stream()
                .map(contextProvider -> {
                    Object contextValue = contextProvider.provide(incomingContextData);
                    return nonNull(contextValue) ? Map.entry(contextProvider.contextName(), contextValue) : null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    protected void initRequestContextUnhandled(Map<String, Object> headers) {
        RequestContextPropagation.clear();
        RequestContextPropagation.initRequestContext(new CamelExchangeRequestContextData(headers));
    }
}
