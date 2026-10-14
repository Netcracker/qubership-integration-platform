package org.qubership.integration.platform.engine.routes.fixture;

import com.netcracker.cloud.context.propagation.core.contextdata.IncomingContextData;
import com.netcracker.cloud.context.propagation.core.supports.providers.AbstractContextProviderOnInheritableThreadLocal;
import com.netcracker.cloud.framework.contexts.allowedheaders.AllowedHeadersContextObject;
import org.apache.hc.core5.http.HttpHeaders;

import java.util.List;
import java.util.Map;

final class HttpSenderSnapshotContextProvider extends AbstractContextProviderOnInheritableThreadLocal<AllowedHeadersContextObject> {
    @Override
    public String contextName() {
        return "snapshot-http-sender-headers";
    }

    @Override
    public AllowedHeadersContextObject provide(IncomingContextData contextData) {
        return contextData == null ? new AllowedHeadersContextObject(Map.of())
                : new AllowedHeadersContextObject(contextData, List.of(HttpHeaders.AUTHORIZATION));
    }
}
