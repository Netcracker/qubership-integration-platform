package org.qubership.integration.platform.engine.util.paths;

import org.apache.commons.codec.digest.DigestUtils;

import java.net.URI;
import java.util.Locale;

/**
 * Parses an egress route's resolved target URL ({@code route.getPath()}, e.g.
 * {@code "https://api.example.com:8443/v2"}) into the parts an {@code HTTPRoute}/
 * {@code ServiceEntry}/{@code DestinationRule} rule needs: host, a port defaulted from the scheme
 * when absent, and a path defaulted to {@code "/"} when absent. Mirrors
 * {@code ControlPlaneDefaultService.postEgressGatewayRoutes}'s existing inline
 * {@code java.net.URI} handling.
 */
public record EgressTarget(String scheme, String host, int port, String path) {
    private static final int DEFAULT_HTTP_PORT = 80;
    private static final int DEFAULT_HTTPS_PORT = 443;

    // Kubernetes object names are capped at 63 characters (DNS-1123 label limit).
    private static final int K8S_NAME_LENGTH_LIMIT = 63;
    private static final int HOST_RESOURCE_NAME_HASH_LENGTH = 8;

    public static EgressTarget parse(String url) {
        URI uri = URI.create(url);
        String scheme = uri.getScheme();
        int explicitPort = uri.getPort();
        int port = explicitPort > 0
                ? explicitPort
                : ("https".equals(scheme) ? DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT);
        String rawPath = uri.getPath();
        String path = (rawPath == null || rawPath.isEmpty()) ? "/" : rawPath;
        return new EgressTarget(scheme, uri.getHost(), port, path);
    }

    public boolean isHttps() {
        return "https".equals(scheme);
    }

    /**
     * A Kubernetes-safe object name derived from {@link #host()} alone (not port or scheme), so
     * every route that targets the same external host converges on the same
     * {@code ServiceEntry}/{@code DestinationRule} name -- in engine's live registration and
     * the build pipeline's build-time generation alike. The hash suffix guarantees two hosts that
     * sanitize to the same base string (e.g. differing only in characters this strips) still get
     * distinct names.
     */
    public String hostResourceName() {
        String lowercase = host.toLowerCase(Locale.ROOT);
        String hash = DigestUtils.sha1Hex(lowercase).substring(0, HOST_RESOURCE_NAME_HASH_LENGTH);
        String sanitized = lowercase.replaceAll("[^-a-z0-9]", "");
        int maxSanitizedLength = K8S_NAME_LENGTH_LIMIT - 1 - hash.length();
        if (sanitized.length() > maxSanitizedLength) {
            sanitized = sanitized.substring(0, maxSanitizedLength);
        }
        return sanitized.isEmpty() ? hash : sanitized + "-" + hash;
    }
}
