package org.qubership.integration.platform.engine.routes.fixture;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.HttpHeader;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class SnapshotHttpRequests {
    private SnapshotHttpRequests() {
    }

    static List<LoggedRequest> since(WireMockServer server, int baseline) {
        List<LoggedRequest> requests = new ArrayList<>();
        server.getAllServeEvents().forEach(serveEvent -> requests.add(serveEvent.getRequest()));
        Collections.reverse(requests);
        return List.copyOf(requests.subList(baseline, requests.size()));
    }

    static Map<String, Object> immutableHeaders(HttpHeaders values) {
        Map<String, Object> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (HttpHeader header : values.all()) {
            List<String> headerValues = header.values();
            headers.put(
                    header.key(),
                    headerValues.size() == 1 ? headerValues.getFirst() : List.copyOf(headerValues)
            );
        }
        return Collections.unmodifiableMap(headers);
    }
}
