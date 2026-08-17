# Egress Gateway Routes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace `postEgressGatewayRoutes`'s `UnsupportedOperationException` stub with a real Istio-native implementation in `engine`, and add a mirroring `runtime-catalog` `ResourceBuilder`, so outgoing chain calls (Service Call / HTTP / GraphQL senders targeting `EXTERNAL` systems) route through Gateway API `HTTPRoute` + Istio `ServiceEntry`/`DestinationRule` instead of throwing.

**Architecture:** Egress becomes a third route tier alongside the existing `public`/`private` trigger tiers. `IstioRoutesRegistrationService`'s `HTTPRoute` merge machinery is generalized (path-match extraction and rule-building become pluggable functions) so all three tiers share one read-modify-write-with-retry core. A new `EgressRouteResourceBuilder` mirrors this for build-time CR generation. `ServiceEntry`/`DestinationRule` are keyed by external host (not by chain/deployment), shared across every route that targets that host, and never deleted.

**Tech Stack:** Java 21, Spring Boot, Jackson (`YAMLMapper`, `ObjectMapper`), Lombok, JUnit 5, Mockito, the official Kubernetes Java client (`io.kubernetes.client`).

## Global Constraints

- Design source of truth: `docs/superpowers/specs/2026-08-17-egress-gateway-routes-design.md`. Follow it for every naming, namespace, and behavioral decision below.
- `GatewayPathMatch` and the new `EgressTarget` helper are duplicated verbatim (content-identical, package differs) between `engine`'s `util.paths` and `runtime-catalog`'s `util.paths` — this mirrors the existing `GatewayPathMatch` duplication precedent (no shared library between the two modules for this class of helper). Any change to one copy's logic must be mirrored in the other.
- `ServiceEntry`/`DestinationRule` naming (`EgressTarget.hostResourceName()`) MUST be byte-identical between the `engine` and `runtime-catalog` copies of `EgressTarget`, since both modules write to CRs of the same name for the same host — this is what makes the "shared, host-keyed, upsert-only" design converge instead of producing duplicate objects.
- `ServiceEntry`/`DestinationRule` are never deleted by any code path added in this plan (explicit non-goal in the design spec).
- `enableInsecureTls` is not implemented for Istio (explicit non-goal in the design spec) — `DestinationRule` always uses `tls.mode: SIMPLE` for `https` targets, nothing for `http`.
- `micro-engine` is out of scope — it has its own, unrelated `ControlPlaneService` interface (see design spec's corrected interface-change section).
- Namespace for every new CR (`HTTPRoute`, `ServiceEntry`, `DestinationRule`) is the microservice's own namespace (`cloud.microservice.namespace` in `engine`, the domain's namespace in `runtime-catalog`), matching the existing public/private tier CRs — not `istio-system`.
- Run the full test suite for the module you touch before each commit: `./mvnw -pl engine test` or `./mvnw -pl runtime-catalog test` (adjust to the repo's actual build tool/module names if different — check for a `pom.xml`/`build.gradle` at the module root before running).

---

### Task 1: `EgressTarget` URL-parsing helper (engine)

**Files:**
- Create: `engine/src/main/java/org/qubership/integration/platform/engine/util/paths/EgressTarget.java`
- Test: `engine/src/test/java/org/qubership/integration/platform/engine/util/paths/EgressTargetTest.java`

**Interfaces:**
- Produces: `EgressTarget` record with components `scheme() : String`, `host() : String`, `port() : int`, `path() : String`; static factory `EgressTarget.parse(String url) : EgressTarget`; instance methods `isHttps() : boolean` and `hostResourceName() : String`. Every later engine task that touches egress routes (Task 4) consumes this exact API.

- [ ] **Step 1: Write the failing tests**

```java
package org.qubership.integration.platform.engine.util.paths;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class EgressTargetTest {

    @Test
    void parsesHostAndDefaultsHttpsPortWhenAbsent() {
        EgressTarget target = EgressTarget.parse("https://api.example.com/v2/orders");

        assertEquals("https", target.scheme());
        assertEquals("api.example.com", target.host());
        assertEquals(443, target.port());
        assertEquals("/v2/orders", target.path());
        assertTrue(target.isHttps());
    }

    @Test
    void defaultsHttpPortWhenAbsent() {
        EgressTarget target = EgressTarget.parse("http://plain-host/path");

        assertEquals(80, target.port());
        assertFalse(target.isHttps());
    }

    @Test
    void preservesAnExplicitPort() {
        EgressTarget target = EgressTarget.parse("http://internal-host:9090");

        assertEquals(9090, target.port());
    }

    @Test
    void defaultsPathToSlashWhenAbsent() {
        EgressTarget target = EgressTarget.parse("https://host:8443");

        assertEquals("/", target.path());
    }

    @Test
    void preservesAnExplicitPath() {
        EgressTarget target = EgressTarget.parse("https://host/a/b/c");

        assertEquals("/a/b/c", target.path());
    }

    @Test
    void hostResourceNameIsCaseInsensitiveAndConvergesForTheSameHost() {
        String lower = EgressTarget.parse("https://Api.Example.COM/v2").hostResourceName();
        String upper = EgressTarget.parse("https://api.example.com/v9").hostResourceName();

        assertEquals(lower, upper);
    }

    @Test
    void hostResourceNameDiffersForHostsThatSanitizeToTheSameBaseString() {
        // Both "a.b.c" and "abc" sanitize (dots stripped) to the base string "abc" -- the hash
        // suffix must keep them distinct.
        String first = EgressTarget.parse("https://a.b.c/x").hostResourceName();
        String second = EgressTarget.parse("https://abc/x").hostResourceName();

        assertNotEquals(first, second);
    }

    @Test
    void hostResourceNameStaysWithinTheKubernetesNameLengthLimit() {
        String longHost = "a".repeat(300) + ".example.com";
        String name = EgressTarget.parse("https://" + longHost + "/x").hostResourceName();

        assertTrue(name.length() <= 63);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl engine test -Dtest=EgressTargetTest`
Expected: FAIL to compile (`EgressTarget` doesn't exist yet)

- [ ] **Step 3: Implement `EgressTarget`**

```java
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
     * runtime-catalog's build-time generation alike. The hash suffix guarantees two hosts that
     * sanitize to the same base string (e.g. differing only in characters this strips) still get
     * distinct names.
     */
    public String hostResourceName() {
        String hash = DigestUtils.sha1Hex(host).substring(0, HOST_RESOURCE_NAME_HASH_LENGTH);
        String sanitized = host.toLowerCase(Locale.ROOT).replaceAll("[^-a-z0-9]", "");
        int maxSanitizedLength = K8S_NAME_LENGTH_LIMIT - 1 - hash.length();
        if (sanitized.length() > maxSanitizedLength) {
            sanitized = sanitized.substring(0, maxSanitizedLength);
        }
        return sanitized.isEmpty() ? hash : sanitized + "-" + hash;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -pl engine test -Dtest=EgressTargetTest`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add engine/src/main/java/org/qubership/integration/platform/engine/util/paths/EgressTarget.java \
        engine/src/test/java/org/qubership/integration/platform/engine/util/paths/EgressTargetTest.java
git commit -m "feat(engine): add EgressTarget URL-parsing helper for egress routes"
```

---

### Task 2: `EgressTarget` URL-parsing helper (runtime-catalog)

**Files:**
- Create: `runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/util/paths/EgressTarget.java`
- Test: `runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/util/paths/EgressTargetTest.java`

**Interfaces:**
- Produces: the identical `EgressTarget` API as Task 1, package `org.qubership.integration.platform.runtime.catalog.util.paths`. Consumed by Task 7 (`EgressRouteResourceBuilder`).

- [ ] **Step 1: Write the failing tests**

Identical to Task 1's test file, with the package declaration changed:

```java
package org.qubership.integration.platform.runtime.catalog.util.paths;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class EgressTargetTest {

    @Test
    void parsesHostAndDefaultsHttpsPortWhenAbsent() {
        EgressTarget target = EgressTarget.parse("https://api.example.com/v2/orders");

        assertEquals("https", target.scheme());
        assertEquals("api.example.com", target.host());
        assertEquals(443, target.port());
        assertEquals("/v2/orders", target.path());
        assertTrue(target.isHttps());
    }

    @Test
    void defaultsHttpPortWhenAbsent() {
        EgressTarget target = EgressTarget.parse("http://plain-host/path");

        assertEquals(80, target.port());
        assertFalse(target.isHttps());
    }

    @Test
    void preservesAnExplicitPort() {
        EgressTarget target = EgressTarget.parse("http://internal-host:9090");

        assertEquals(9090, target.port());
    }

    @Test
    void defaultsPathToSlashWhenAbsent() {
        EgressTarget target = EgressTarget.parse("https://host:8443");

        assertEquals("/", target.path());
    }

    @Test
    void preservesAnExplicitPath() {
        EgressTarget target = EgressTarget.parse("https://host/a/b/c");

        assertEquals("/a/b/c", target.path());
    }

    @Test
    void hostResourceNameIsCaseInsensitiveAndConvergesForTheSameHost() {
        String lower = EgressTarget.parse("https://Api.Example.COM/v2").hostResourceName();
        String upper = EgressTarget.parse("https://api.example.com/v9").hostResourceName();

        assertEquals(lower, upper);
    }

    @Test
    void hostResourceNameDiffersForHostsThatSanitizeToTheSameBaseString() {
        String first = EgressTarget.parse("https://a.b.c/x").hostResourceName();
        String second = EgressTarget.parse("https://abc/x").hostResourceName();

        assertNotEquals(first, second);
    }

    @Test
    void hostResourceNameStaysWithinTheKubernetesNameLengthLimit() {
        String longHost = "a".repeat(300) + ".example.com";
        String name = EgressTarget.parse("https://" + longHost + "/x").hostResourceName();

        assertTrue(name.length() <= 63);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl runtime-catalog test -Dtest=EgressTargetTest`
Expected: FAIL to compile (`EgressTarget` doesn't exist yet)

- [ ] **Step 3: Implement `EgressTarget`**

Identical to Task 1's implementation, with the package declaration changed:

```java
package org.qubership.integration.platform.runtime.catalog.util.paths;

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
     * runtime-catalog's build-time generation alike. The hash suffix guarantees two hosts that
     * sanitize to the same base string (e.g. differing only in characters this strips) still get
     * distinct names.
     */
    public String hostResourceName() {
        String hash = DigestUtils.sha1Hex(host).substring(0, HOST_RESOURCE_NAME_HASH_LENGTH);
        String sanitized = host.toLowerCase(Locale.ROOT).replaceAll("[^-a-z0-9]", "");
        int maxSanitizedLength = K8S_NAME_LENGTH_LIMIT - 1 - hash.length();
        if (sanitized.length() > maxSanitizedLength) {
            sanitized = sanitized.substring(0, maxSanitizedLength);
        }
        return sanitized.isEmpty() ? hash : sanitized + "-" + hash;
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -pl runtime-catalog test -Dtest=EgressTargetTest`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/util/paths/EgressTarget.java \
        runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/util/paths/EgressTargetTest.java
git commit -m "feat(runtime-catalog): add EgressTarget URL-parsing helper for egress routes"
```

---

### Task 3: Batch the `ControlPlaneService.postEgressGatewayRoutes` interface (engine)

**Files:**
- Modify: `engine/src/main/java/org/qubership/integration/platform/engine/controlplane/ControlPlaneService.java`
- Modify: `engine/src/main/java/org/qubership/integration/platform/engine/cloudcore/controlplane/ControlPlaneDevService.java`
- Modify: `engine/src/main/java/org/qubership/integration/platform/engine/cloudcore/controlplane/ControlPlaneDefaultService.java`
- Modify: `engine/src/main/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/before/RegisterRoutesInControlPlaneAction.java`
- Test: `engine/src/test/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/before/RegisterRoutesInControlPlaneActionTest.java`

**Interfaces:**
- Consumes: nothing new.
- Produces: `ControlPlaneService.postEgressGatewayRoutes(List<DeploymentRouteUpdate> routes, String endpoint) throws ControlPlaneException`, replacing today's `postEgressGatewayRoutes(DeploymentRouteUpdate route)`. Task 4's `IstioRoutesRegistrationService` implements this new signature.

- [ ] **Step 1: Update the interface**

In `ControlPlaneService.java`, replace:

```java
    void postEgressGatewayRoutes(DeploymentRouteUpdate route);
```

with:

```java
    void postEgressGatewayRoutes(List<DeploymentRouteUpdate> routes, String endpoint) throws ControlPlaneException;
```

- [ ] **Step 2: Update `ControlPlaneDevService` (no-op implementer)**

In `ControlPlaneDevService.java`, replace:

```java
    @Override
    public void postEgressGatewayRoutes(DeploymentRouteUpdate route) {

    }
```

with:

```java
    @Override
    public void postEgressGatewayRoutes(List<DeploymentRouteUpdate> routes, String endpoint) {

    }
```

- [ ] **Step 3: Update `ControlPlaneDefaultService` (cloud-core implementer)**

In `ControlPlaneDefaultService.java`, replace:

```java
    /**
     * Post routes to egress gateway via control plane configuration.
     * On gateway some headers of original request will be removed, like 'Origin', 'Authorization' (set in HEADERS_TO_REMOVE)
     * By default prefixRewrite is '/'
     *
     * @param route  route configuration
     */
    @Override
    public void postEgressGatewayRoutes(DeploymentRouteUpdate route) throws ControlPlaneException {
        String targetURL = route.getPath();
```

with:

```java
    /**
     * Post routes to egress gateway via control plane configuration.
     * On gateway some headers of original request will be removed, like 'Origin', 'Authorization' (set in HEADERS_TO_REMOVE)
     * By default prefixRewrite is '/'
     *
     * @param routes route configurations
     * @param endpoint unused; Core Mesh's route objects aren't named per pod
     */
    @Override
    public void postEgressGatewayRoutes(List<DeploymentRouteUpdate> routes, String endpoint) throws ControlPlaneException {
        for (DeploymentRouteUpdate route : routes) {
            postEgressGatewayRoute(route);
        }
    }

    private void postEgressGatewayRoute(DeploymentRouteUpdate route) throws ControlPlaneException {
        String targetURL = route.getPath();
```

The rest of the original method body (from `String gatewayPrefix = route.getGatewayPrefix();` through its closing brace) is unchanged -- it now belongs to the new private `postEgressGatewayRoute` method instead of the public `postEgressGatewayRoutes` method.

- [ ] **Step 4: Update the call site**

In `RegisterRoutesInControlPlaneAction.java`, replace:

```java
            // Register http based senders and service call paths '/{senderType}/{elementId}', '/system/{elementId}'
            deploymentConfiguration.getRoutes().stream()
                .filter(route -> route.getType() == RouteType.EXTERNAL_SENDER
                    || route.getType() == RouteType.EXTERNAL_SERVICE)
                .forEach(route -> controlPlaneService.postEgressGatewayRoutes(formatServiceRoutes(route)));
```

with:

```java
            // Register http based senders and service call paths '/{senderType}/{elementId}', '/system/{elementId}'
            controlPlaneService.postEgressGatewayRoutes(
                deploymentConfiguration.getRoutes().stream()
                    .filter(route -> route.getType() == RouteType.EXTERNAL_SENDER
                        || route.getType() == RouteType.EXTERNAL_SERVICE)
                    .map(RegisterRoutesInControlPlaneAction::formatServiceRoutes)
                    .toList(),
                applicationConfiguration.getDeploymentName());
```

- [ ] **Step 5: Write the failing test for the batched call site**

Add to `RegisterRoutesInControlPlaneActionTest.java`:

```java
    @Test
    void postsExternalSenderAndServiceRoutesAsABatchToEgress() {
        DeploymentRouteUpdate senderRoute = route("http://backend:8080", RouteType.EXTERNAL_SENDER);
        DeploymentRouteUpdate serviceRoute = route("http://backend2:8080", RouteType.EXTERNAL_SERVICE);
        DeploymentConfiguration configuration = configuration(senderRoute, serviceRoute);

        action.execute(null, deploymentInfo(), configuration);

        verify(controlPlaneService).postEgressGatewayRoutes(eq(List.of(senderRoute, serviceRoute)), eq(DEPLOYMENT_NAME));
    }
```

- [ ] **Step 6: Run the test to verify it fails, then passes**

Run: `./mvnw -pl engine test -Dtest=RegisterRoutesInControlPlaneActionTest`
Expected before Step 4's edit is in place: FAIL to compile (old `postEgressGatewayRoutes(DeploymentRouteUpdate)` signature no longer exists on the mock)
Expected after Steps 1-4: PASS (4 tests)

- [ ] **Step 7: Run the full engine test suite**

Run: `./mvnw -pl engine test`
Expected: PASS -- confirms no other caller of the old single-route `postEgressGatewayRoutes` signature was missed.

- [ ] **Step 8: Commit**

```bash
git add engine/src/main/java/org/qubership/integration/platform/engine/controlplane/ControlPlaneService.java \
        engine/src/main/java/org/qubership/integration/platform/engine/cloudcore/controlplane/ControlPlaneDevService.java \
        engine/src/main/java/org/qubership/integration/platform/engine/cloudcore/controlplane/ControlPlaneDefaultService.java \
        engine/src/main/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/before/RegisterRoutesInControlPlaneAction.java \
        engine/src/test/java/org/qubership/integration/platform/engine/service/deployment/processing/actions/context/before/RegisterRoutesInControlPlaneActionTest.java
git commit -m "feat(engine): batch ControlPlaneService.postEgressGatewayRoutes like the trigger tiers"
```

---

### Task 4: `IstioRoutesRegistrationService` egress tier (engine)

**Files:**
- Modify: `engine/src/main/java/org/qubership/integration/platform/engine/model/gatewayapi/HTTPUrlRewriteFilter.java`
- Modify: `engine/src/main/java/org/qubership/integration/platform/engine/cloudcore/controlplane/IstioRoutesRegistrationService.java`
- Test: `engine/src/test/java/org/qubership/integration/platform/engine/cloudcore/controlplane/IstioRoutesRegistrationServiceTest.java`

**Interfaces:**
- Consumes: `EgressTarget` (Task 1), the new `ControlPlaneService.postEgressGatewayRoutes(List<DeploymentRouteUpdate>, String)` signature (Task 3).
- Produces: a real `postEgressGatewayRoutes` implementation. No new public API beyond what Task 3 declared.

- [ ] **Step 1: Add the missing `hostname` field to `HTTPUrlRewriteFilter`**

The Gateway API `HTTPURLRewriteFilter` has both `hostname` and `path`; the existing POJO (built for the ingress tiers, which never rewrite the target host) only has `path`. Replace the full contents of `HTTPUrlRewriteFilter.java`:

```java
package org.qubership.integration.platform.engine.model.gatewayapi;

import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class HTTPUrlRewriteFilter {
    private String hostname;
    private HTTPPathModifier path;
}
```

- [ ] **Step 2: Write the failing tests**

Add to `IstioRoutesRegistrationServiceTest.java`. These four imports need adding alongside the existing ones:

```java
import org.qubership.integration.platform.engine.errorhandling.KubeApiConflictException;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.argThat;
```

New helper method, alongside the existing `route(...)` helper:

```java
    private DeploymentRouteUpdate egressRoute(String targetUrl, String gatewayPrefix, RouteType type) {
        return DeploymentRouteUpdate.builder()
                .path(targetUrl)
                .gatewayPrefix(gatewayPrefix)
                .type(type)
                .build();
    }
```

New test methods:

```java
    @Test
    void postEgressGatewayRoutesCreatesHttpRouteServiceEntryAndDestinationRuleForHttpsTarget() {
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.empty());
        DeploymentRouteUpdate route =
                egressRoute("https://api.example.com/v2", "/system/service-a", RouteType.EXTERNAL_SERVICE);

        service.postEgressGatewayRoutes(List.of(route), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator, times(3)).createOrReplaceCustomObject(captor.capture());
        List<KubeCustomObjectRequest> requests = captor.getAllValues();

        KubeCustomObjectRequest serviceEntryRequest = requests.stream()
                .filter(r -> "serviceentries".equals(r.getResourceNamePlural())).findFirst().orElseThrow();
        KubeCustomObjectRequest destinationRuleRequest = requests.stream()
                .filter(r -> "destinationrules".equals(r.getResourceNamePlural())).findFirst().orElseThrow();
        KubeCustomObjectRequest httpRouteRequest = requests.stream()
                .filter(r -> "httproutes".equals(r.getResourceNamePlural())).findFirst().orElseThrow();

        assertEquals("networking.istio.io", serviceEntryRequest.getGroup());
        assertEquals(List.of("api.example.com"), serviceEntryRequest.getBody().getSpec().get("hosts"));
        assertEquals("MESH_EXTERNAL", serviceEntryRequest.getBody().getSpec().get("location"));

        assertEquals("networking.istio.io", destinationRuleRequest.getGroup());
        assertEquals("api.example.com", destinationRuleRequest.getBody().getSpec().get("host"));

        assertEquals(CLOUD_SERVICE_NAME + "-egress-routes", httpRouteRequest.getBody().getMetadata().getName());
        HTTPRouteSpec spec = new ObjectMapper().convertValue(httpRouteRequest.getBody().getSpec(), HTTPRouteSpec.class);
        assertEquals("egress-gateway", spec.getParentRefs().get(0).getName());
        assertEquals("PathPrefix", spec.getRules().get(0).getMatches().get(0).getPath().getType());
        assertEquals("/system/service-a", spec.getRules().get(0).getMatches().get(0).getPath().getValue());
        assertEquals("networking.istio.io", spec.getRules().get(0).getBackendRefs().get(0).getGroup());
        assertEquals("Hostname", spec.getRules().get(0).getBackendRefs().get(0).getKind());
        assertEquals("api.example.com", spec.getRules().get(0).getBackendRefs().get(0).getName());
        assertEquals(443, spec.getRules().get(0).getBackendRefs().get(0).getPort());
        assertEquals("api.example.com", spec.getRules().get(0).getFilters().get(0).getUrlRewrite().getHostname());
        assertEquals("/v2", spec.getRules().get(0).getFilters().get(0).getUrlRewrite().getPath().getReplacePrefixMatch());
        assertEquals("ReplacePrefixMatch", spec.getRules().get(0).getFilters().get(0).getUrlRewrite().getPath().getType());
    }

    @Test
    void postEgressGatewayRoutesSkipsDestinationRuleForHttpTarget() {
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.empty());
        DeploymentRouteUpdate route =
                egressRoute("http://backend:9090", "/http-sender/elem-1/abc", RouteType.EXTERNAL_SENDER);

        service.postEgressGatewayRoutes(List.of(route), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator, times(2)).createOrReplaceCustomObject(captor.capture());
        assertTrue(captor.getAllValues().stream().noneMatch(r -> "destinationrules".equals(r.getResourceNamePlural())));
    }

    @Test
    void postEgressGatewayRoutesConvergesTwoRoutesSharingAHostOnOneServiceEntry() {
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.empty());
        DeploymentRouteUpdate routeA =
                egressRoute("https://api.example.com/a", "/system/elem-a", RouteType.EXTERNAL_SERVICE);
        DeploymentRouteUpdate routeB =
                egressRoute("https://api.example.com/b", "/system/elem-b", RouteType.EXTERNAL_SERVICE);

        service.postEgressGatewayRoutes(List.of(routeA, routeB), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        // 1 ServiceEntry + 1 DestinationRule (one unique host) + 1 HTTPRoute (two rules) = 3 calls
        verify(kubeOperator, times(3)).createOrReplaceCustomObject(captor.capture());
        long serviceEntryCalls = captor.getAllValues().stream()
                .filter(r -> "serviceentries".equals(r.getResourceNamePlural())).count();
        assertEquals(1, serviceEntryCalls);
    }

    @Test
    void postEgressGatewayRoutesPreservesOtherChainsRulesInTheSharedEgressCr() {
        HTTPRouteRule existingRule = HTTPRouteRule.builder()
                .matches(List.of(HTTPRouteMatch.builder()
                        .path(HTTPPathMatch.builder().type("PathPrefix").value("/system/other-elem").build())
                        .build()))
                .backendRefs(List.of(HTTPBackendRef.builder()
                        .group("networking.istio.io").kind("Hostname").name("other.example.com").port(443).weight(1)
                        .build()))
                .build();
        HTTPRouteSpec existingSpec = HTTPRouteSpec.builder()
                .parentRefs(List.of(ParentReference.builder()
                        .group("gateway.networking.k8s.io").kind("Gateway").name("egress-gateway").build()))
                .rules(List.of(existingRule))
                .build();
        KubeCustomObject existing = KubeCustomObject.builder()
                .metadata(metadataWithVersion(CLOUD_SERVICE_NAME + "-egress-routes", "5"))
                .spec(new ObjectMapper().convertValue(existingSpec, new TypeReference<Map<String, Object>>() {}))
                .build();
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.of(existing));

        DeploymentRouteUpdate route =
                egressRoute("https://api.example.com/v2", "/system/service-a", RouteType.EXTERNAL_SERVICE);
        service.postEgressGatewayRoutes(List.of(route), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator, times(3)).createOrReplaceCustomObject(captor.capture());
        KubeCustomObjectRequest httpRouteRequest = captor.getAllValues().stream()
                .filter(r -> "httproutes".equals(r.getResourceNamePlural())).findFirst().orElseThrow();
        HTTPRouteSpec spec = new ObjectMapper().convertValue(httpRouteRequest.getBody().getSpec(), HTTPRouteSpec.class);
        assertEquals(2, spec.getRules().size());
        assertTrue(spec.getRules().stream()
                .anyMatch(r -> "/system/other-elem".equals(r.getMatches().get(0).getPath().getValue())));
        assertTrue(spec.getRules().stream()
                .anyMatch(r -> "/system/service-a".equals(r.getMatches().get(0).getPath().getValue())));
    }

    @Test
    void upsertHostResourceIgnoresAConcurrentConflictOnHostKeyedObjects() {
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.empty());
        doThrow(new KubeApiConflictException("conflict"))
                .when(kubeOperator)
                .createOrReplaceCustomObject(argThat(r -> "serviceentries".equals(r.getResourceNamePlural())));
        DeploymentRouteUpdate route =
                egressRoute("https://api.example.com/v2", "/system/service-a", RouteType.EXTERNAL_SERVICE);

        assertDoesNotThrow(() -> service.postEgressGatewayRoutes(List.of(route), CLOUD_SERVICE_NAME));
    }

    private V1ObjectMeta metadataWithVersion(String name, String resourceVersion) {
        V1ObjectMeta metadata = new V1ObjectMeta();
        metadata.setName(name);
        metadata.setResourceVersion(resourceVersion);
        return metadata;
    }
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -pl engine test -Dtest=IstioRoutesRegistrationServiceTest`
Expected: FAIL to compile (`postEgressGatewayRoutes(List, String)` doesn't exist on `IstioRoutesRegistrationService` yet -- it still implements the old stub signature via `ControlPlaneService`, which Task 3 already changed, so this currently fails to compile at the class level)

- [ ] **Step 4: Replace `IstioRoutesRegistrationService.java` in full**

The merge machinery is generalized (path-match extraction and rule-building become `Function` parameters) so the egress tier reuses the exact same read-modify-write-with-retry core as the public/private tiers, instead of duplicating it. Replace the entire file:

```java
package org.qubership.integration.platform.engine.cloudcore.controlplane;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneException;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneService;
import org.qubership.integration.platform.engine.errorhandling.KubeApiConflictException;
import org.qubership.integration.platform.engine.kubernetes.KubeCustomObject;
import org.qubership.integration.platform.engine.kubernetes.KubeCustomObjectRequest;
import org.qubership.integration.platform.engine.kubernetes.KubeOperator;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.engine.model.deployment.update.RouteType;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPBackendRef;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPPathMatch;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPPathModifier;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPRouteFilter;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPRouteMatch;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPRouteRule;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPRouteSpec;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPRouteTimeouts;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPUrlRewriteFilter;
import org.qubership.integration.platform.engine.model.gatewayapi.ParentReference;
import org.qubership.integration.platform.engine.util.GatewayDuration;
import org.qubership.integration.platform.engine.util.paths.EgressTarget;
import org.qubership.integration.platform.engine.util.paths.GatewayPathMatch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component("controlPlaneService")
@ConditionalOnProperty(value = "qip.control-plane.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(name = "qip.control-plane.mesh-type", havingValue = "Istio")
public class IstioRoutesRegistrationService implements ControlPlaneService {

    private static final String GATEWAY_API_GROUP = "gateway.networking.k8s.io";
    private static final String GATEWAY_API_VERSION = "v1";
    private static final String HTTP_ROUTES_PLURAL = "httproutes";
    private static final String PUBLIC_GATEWAY_NAME = "public-gateway";
    private static final String PRIVATE_GATEWAY_NAME = "private-gateway";
    private static final String EGRESS_GATEWAY_NAME = "egress-gateway";
    private static final int BACKEND_PORT = 8080;

    private static final String NETWORKING_ISTIO_API_GROUP = "networking.istio.io";
    private static final String NETWORKING_ISTIO_API_VERSION = "v1";
    private static final String SERVICE_ENTRIES_PLURAL = "serviceentries";
    private static final String DESTINATION_RULES_PLURAL = "destinationrules";

    private final KubeOperator kubeOperator;
    private final ObjectMapper objectMapper;
    private final String namespace;
    private final String baseRoutePrefix;

    @Autowired
    public IstioRoutesRegistrationService(
            KubeOperator kubeOperator,
            ObjectMapper objectMapper,
            @Value("${cloud.microservice.namespace}") String namespace,
            @Value("${qip.chains.external-routes.base-path}") String baseRoutePrefix
    ) {
        this.kubeOperator = kubeOperator;
        this.objectMapper = objectMapper;
        this.namespace = namespace;
        this.baseRoutePrefix = baseRoutePrefix;
    }

    @Override
    public synchronized void postPublicEngineRoutes(List<DeploymentRouteUpdate> deploymentRoutes, String endpoint)
            throws ControlPlaneException {
        mergeTierRoutes(tierRequest(endpoint, "public"), deploymentRoutes, PUBLIC_GATEWAY_NAME,
                this::triggerPathMatch, route -> buildTriggerRule(route, endpoint));
    }

    @Override
    public synchronized void postPrivateEngineRoutes(List<DeploymentRouteUpdate> deploymentRoutes, String endpoint)
            throws ControlPlaneException {
        mergeTierRoutes(tierRequest(endpoint, "private"), deploymentRoutes, PRIVATE_GATEWAY_NAME,
                this::triggerPathMatch, route -> buildTriggerRule(route, endpoint));
    }

    @Override
    public synchronized void removeEngineRoutes(List<DeploymentRouteUpdate> deploymentRoutes, String deploymentName)
            throws ControlPlaneException {
        List<DeploymentRouteUpdate> publicRoutes = deploymentRoutes.stream()
                .filter(route -> RouteType.isPublicTriggerRoute(route.getType()))
                .toList();
        if (!publicRoutes.isEmpty()) {
            mergeTierRoutes(tierRequest(deploymentName, "public"), publicRoutes, PUBLIC_GATEWAY_NAME,
                    this::triggerPathMatch, null);
        }

        List<DeploymentRouteUpdate> privateRoutes = deploymentRoutes.stream()
                .filter(route -> RouteType.isPrivateTriggerRoute(route.getType()))
                .toList();
        if (!privateRoutes.isEmpty()) {
            mergeTierRoutes(tierRequest(deploymentName, "private"), privateRoutes, PRIVATE_GATEWAY_NAME,
                    this::triggerPathMatch, null);
        }
    }

    @Override
    public synchronized void postEgressGatewayRoutes(List<DeploymentRouteUpdate> routes, String endpoint)
            throws ControlPlaneException {
        routes.stream()
                .map(route -> EgressTarget.parse(route.getPath()))
                .collect(Collectors.toMap(EgressTarget::host, Function.identity(), (first, second) -> first))
                .values()
                .forEach(this::upsertHostResources);

        mergeTierRoutes(egressTierRequest(endpoint), routes, EGRESS_GATEWAY_NAME,
                this::egressPathMatch, this::buildEgressRule);
    }

    private static final int MAX_MERGE_ATTEMPTS = 3;

    private void mergeTierRoutes(
            KubeCustomObjectRequest tierRequest,
            List<DeploymentRouteUpdate> givenRoutes,
            String gatewayName,
            Function<DeploymentRouteUpdate, GatewayPathMatch> pathMatchExtractor,
            Function<DeploymentRouteUpdate, HTTPRouteRule> ruleBuilder
    ) {
        if (givenRoutes.isEmpty()) {
            return;
        }
        try {
            for (int attempt = 1; attempt <= MAX_MERGE_ATTEMPTS; attempt++) {
                try {
                    attemptMergeTierRoutes(tierRequest, givenRoutes, gatewayName, pathMatchExtractor, ruleBuilder);
                    return;
                } catch (KubeApiConflictException e) {
                    if (attempt == MAX_MERGE_ATTEMPTS) {
                        throw e;
                    }
                    log.warn("Concurrent update detected for {} on attempt {}/{}, retrying",
                            tierRequest.getBody().getMetadata().getName(), attempt, MAX_MERGE_ATTEMPTS);
                }
            }
        } catch (ControlPlaneException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to update Istio HTTPRoute for control plane routes: {}", e.getMessage());
            throw new ControlPlaneException("Failed to update Istio HTTPRoute for control plane routes", e);
        }
    }

    private void attemptMergeTierRoutes(
            KubeCustomObjectRequest tierRequest,
            List<DeploymentRouteUpdate> givenRoutes,
            String gatewayName,
            Function<DeploymentRouteUpdate, GatewayPathMatch> pathMatchExtractor,
            Function<DeploymentRouteUpdate, HTTPRouteRule> ruleBuilder
    ) {
        Optional<KubeCustomObject> current = kubeOperator.getCustomObject(tierRequest);
        List<HTTPRouteRule> existingRules = current
                .map(obj -> objectMapper.convertValue(obj.getSpec(), HTTPRouteSpec.class))
                .map(HTTPRouteSpec::getRules)
                .filter(Objects::nonNull)
                .orElse(List.of());

        Set<GatewayPathMatch> touchedPaths = givenRoutes.stream()
                .map(pathMatchExtractor)
                .collect(Collectors.toSet());

        List<HTTPRouteRule> preservedRules = existingRules.stream()
                .filter(rule -> !touchedPaths.contains(ruleMatch(rule)))
                .toList();

        List<HTTPRouteRule> newRules = ruleBuilder == null
                ? List.of()
                : givenRoutes.stream().map(ruleBuilder).toList();

        List<HTTPRouteRule> mergedRules = new ArrayList<>(preservedRules);
        mergedRules.addAll(newRules);

        tierRequest.getBody().getMetadata().setResourceVersion(
                current.map(obj -> obj.getMetadata().getResourceVersion()).orElse(null));

        if (mergedRules.isEmpty()) {
            if (current.isPresent()) {
                kubeOperator.deleteCustomObject(tierRequest);
            }
            return;
        }

        HTTPRouteSpec spec = HTTPRouteSpec.builder()
                .parentRefs(parentRefs(gatewayName))
                .rules(mergedRules)
                .build();
        tierRequest.getBody().setSpec(objectMapper.convertValue(spec, new TypeReference<Map<String, Object>>() {}));
        kubeOperator.createOrReplaceCustomObject(tierRequest);
    }

    /**
     * Reads the path match off an existing rule fetched from the cluster. Unlike
     * {@code runtime-catalog}'s sibling code (which preserves an unrecognized rule and logs a
     * warning), a malformed rule here throws (e.g. {@link IndexOutOfBoundsException} on an
     * empty {@code matches} list), which {@link #mergeTierRoutes} wraps as a
     * {@link ControlPlaneException} and aborts the whole write. That asymmetry is intentional:
     * this write path merges into a live route the engine itself owns, so failing loudly and
     * leaving the existing HTTPRoute untouched is safer than silently guessing at a rule's
     * intent and possibly dropping or misapplying it.
     */
    private GatewayPathMatch ruleMatch(HTTPRouteRule rule) {
        HTTPPathMatch path = rule.getMatches().get(0).getPath();
        return GatewayPathMatch.of(path.getType(), path.getValue());
    }

    private GatewayPathMatch triggerPathMatch(DeploymentRouteUpdate route) {
        return GatewayPathMatch.forPath(baseRoutePrefix + route.getPath());
    }

    private GatewayPathMatch egressPathMatch(DeploymentRouteUpdate route) {
        return GatewayPathMatch.forPath(route.getGatewayPrefix());
    }

    private HTTPRouteRule buildTriggerRule(DeploymentRouteUpdate route, String backendName) {
        GatewayPathMatch pathMatch = triggerPathMatch(route);

        HTTPRouteTimeouts timeouts = null;
        if (route.getConnectTimeout() != null && route.getConnectTimeout() > 0) {
            timeouts = HTTPRouteTimeouts.builder()
                    .request(GatewayDuration.formatMillis(route.getConnectTimeout()))
                    .build();
        }

        return HTTPRouteRule.builder()
                .matches(List.of(HTTPRouteMatch.builder()
                        .path(HTTPPathMatch.builder().type(pathMatch.getType()).value(pathMatch.getValue()).build())
                        .build()))
                .filters(Collections.emptyList())
                .backendRefs(List.of(HTTPBackendRef.builder()
                        .group("")
                        .kind("Service")
                        .name(backendName)
                        .port(BACKEND_PORT)
                        .weight(1)
                        .build()))
                .timeouts(timeouts)
                .build();
    }

    private HTTPRouteRule buildEgressRule(DeploymentRouteUpdate route) {
        EgressTarget target = EgressTarget.parse(route.getPath());
        GatewayPathMatch pathMatch = egressPathMatch(route);

        HTTPRouteTimeouts timeouts = null;
        if (route.getConnectTimeout() != null && route.getConnectTimeout() > 0) {
            timeouts = HTTPRouteTimeouts.builder()
                    .request(GatewayDuration.formatMillis(route.getConnectTimeout()))
                    .build();
        }

        return HTTPRouteRule.builder()
                .matches(List.of(HTTPRouteMatch.builder()
                        .path(HTTPPathMatch.builder().type(pathMatch.getType()).value(pathMatch.getValue()).build())
                        .build()))
                .filters(List.of(HTTPRouteFilter.builder()
                        .type("URLRewrite")
                        .urlRewrite(HTTPUrlRewriteFilter.builder()
                                .hostname(target.host())
                                .path(HTTPPathModifier.builder()
                                        .type("ReplacePrefixMatch")
                                        .replacePrefixMatch(target.path())
                                        .build())
                                .build())
                        .build()))
                .backendRefs(List.of(HTTPBackendRef.builder()
                        .group(NETWORKING_ISTIO_API_GROUP)
                        .kind("Hostname")
                        .name(target.host())
                        .port(target.port())
                        .weight(1)
                        .build()))
                .timeouts(timeouts)
                .build();
    }

    /**
     * Creates or updates the {@code ServiceEntry} (and, for an https target, the
     * {@code DestinationRule}) that registers {@code target}'s host with the mesh. Both are named
     * deterministically from the host alone ({@link EgressTarget#hostResourceName()}), so every
     * route that targets the same external host -- across every chain this namespace hosts --
     * converges on the same pair of objects, and their content is fully determined by the
     * host/port/scheme: any deployer can safely overwrite it wholesale. Never deleted; see the
     * design spec's cleanup non-goal.
     */
    private void upsertHostResources(EgressTarget target) {
        String name = target.hostResourceName();
        upsertServiceEntry(name, target);
        if (target.isHttps()) {
            upsertDestinationRule(name, target);
        }
    }

    private void upsertServiceEntry(String name, EgressTarget target) {
        Map<String, Object> spec = Map.of(
                "hosts", List.of(target.host()),
                "location", "MESH_EXTERNAL",
                "resolution", "DNS",
                "ports", List.of(Map.of(
                        "number", target.port(),
                        "name", target.isHttps() ? "https" : "http",
                        "protocol", target.isHttps() ? "HTTPS" : "HTTP")));
        upsertHostResource(NETWORKING_ISTIO_API_GROUP, NETWORKING_ISTIO_API_VERSION, SERVICE_ENTRIES_PLURAL,
                "ServiceEntry", name, spec);
    }

    private void upsertDestinationRule(String name, EgressTarget target) {
        Map<String, Object> spec = Map.of(
                "host", target.host(),
                "trafficPolicy", Map.of(
                        "portLevelSettings", List.of(Map.of(
                                "port", Map.of("number", target.port()),
                                "tls", Map.of("mode", "SIMPLE", "sni", target.host())))));
        upsertHostResource(NETWORKING_ISTIO_API_GROUP, NETWORKING_ISTIO_API_VERSION, DESTINATION_RULES_PLURAL,
                "DestinationRule", name, spec);
    }

    private void upsertHostResource(
            String group, String version, String plural, String kind, String name, Map<String, Object> spec
    ) {
        V1ObjectMeta metadata = new V1ObjectMeta();
        metadata.setName(name);
        metadata.setNamespace(namespace);

        KubeCustomObjectRequest request = KubeCustomObjectRequest.builder()
                .group(group)
                .version(version)
                .resourceNamePlural(plural)
                .body(KubeCustomObject.builder()
                        .apiVersion(group + "/" + version)
                        .kind(kind)
                        .metadata(metadata)
                        .spec(spec)
                        .build())
                .build();

        Optional<KubeCustomObject> existing = kubeOperator.getCustomObject(request);
        request.getBody().getMetadata().setResourceVersion(
                existing.map(obj -> obj.getMetadata().getResourceVersion()).orElse(null));

        try {
            kubeOperator.createOrReplaceCustomObject(request);
        } catch (KubeApiConflictException e) {
            // Another pod created or updated the same host-keyed object concurrently. Its content
            // is fully determined by the host, so whichever write won is equivalent -- nothing to
            // reconcile.
            log.debug("Concurrent update of {} '{}', skipping (content is host-derived and equivalent)", kind, name);
        }
    }

    private List<ParentReference> parentRefs(String gatewayName) {
        return List.of(ParentReference.builder()
                .group(GATEWAY_API_GROUP)
                .kind("Gateway")
                .name(gatewayName)
                .build());
    }

    private KubeCustomObjectRequest tierRequest(String cloudServiceName, String tier) {
        V1ObjectMeta metadata = new V1ObjectMeta();
        metadata.setName(cloudServiceName + "-chain-" + tier + "-routes");
        metadata.setNamespace(namespace);

        return KubeCustomObjectRequest.builder()
                .group(GATEWAY_API_GROUP)
                .version(GATEWAY_API_VERSION)
                .resourceNamePlural(HTTP_ROUTES_PLURAL)
                .body(KubeCustomObject.builder()
                        .apiVersion(GATEWAY_API_GROUP + "/" + GATEWAY_API_VERSION)
                        .kind("HTTPRoute")
                        .metadata(metadata)
                        .build())
                .build();
    }

    private KubeCustomObjectRequest egressTierRequest(String cloudServiceName) {
        V1ObjectMeta metadata = new V1ObjectMeta();
        metadata.setName(cloudServiceName + "-egress-routes");
        metadata.setNamespace(namespace);

        return KubeCustomObjectRequest.builder()
                .group(GATEWAY_API_GROUP)
                .version(GATEWAY_API_VERSION)
                .resourceNamePlural(HTTP_ROUTES_PLURAL)
                .body(KubeCustomObject.builder()
                        .apiVersion(GATEWAY_API_GROUP + "/" + GATEWAY_API_VERSION)
                        .kind("HTTPRoute")
                        .metadata(metadata)
                        .build())
                .build();
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -pl engine test -Dtest=IstioRoutesRegistrationServiceTest`
Expected: PASS (all pre-existing public/private/remove tests, unchanged in behavior, plus the 5 new egress tests)

- [ ] **Step 6: Run the full engine test suite**

Run: `./mvnw -pl engine test`
Expected: PASS -- confirms the merge-machinery generalization didn't change public/private tier behavior anywhere else.

- [ ] **Step 7: Commit**

```bash
git add engine/src/main/java/org/qubership/integration/platform/engine/model/gatewayapi/HTTPUrlRewriteFilter.java \
        engine/src/main/java/org/qubership/integration/platform/engine/cloudcore/controlplane/IstioRoutesRegistrationService.java \
        engine/src/test/java/org/qubership/integration/platform/engine/cloudcore/controlplane/IstioRoutesRegistrationServiceTest.java
git commit -m "feat(engine): implement egress HTTPRoute/ServiceEntry/DestinationRule generation"
```

---

### Task 5: `HttpRouteEgressNamingStrategy` (runtime-catalog)

**Files:**
- Create: `runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/cr/naming/strategies/HttpRouteEgressNamingStrategy.java`
- Test: `runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/cr/naming/strategies/HttpRouteEgressNamingStrategyTest.java`

**Interfaces:**
- Produces: a `@Component("httpRouteEgressNamingStrategy")` bean implementing `NamingStrategy<ResourceBuildContext<List<Snapshot>>>`, proposing `<domain-name>-egress-routes` by default. Consumed by Task 7 (`EgressRouteResourceBuilder`) and Task 8 (`CustomResourceService`).

- [ ] **Step 1: Write the failing tests**

```java
package org.qubership.integration.platform.runtime.catalog.cr.naming.strategies;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.cr.BuildInfo;
import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuildContext;
import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNameVerifier;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildOptions;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpRouteEgressNamingStrategyTest {

    @Test
    void proposesNameWithDefaultSuffix() {
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy =
                context -> "my-domain-v1";
        HttpRouteEgressNamingStrategy strategy = new HttpRouteEgressNamingStrategy(
                new K8sNameVerifier(),
                new K8sNameValidator(),
                integrationResourceNamingStrategy,
                "-egress-routes");

        ResourceBuildContext<List<Snapshot>> context = ResourceBuildContext.create(
                BuildInfo.builder().options(ResourceBuildOptions.builder().name("my-domain").build()).build()
        ).updateTo(Collections.emptyList());

        assertEquals("my-domain-v1-egress-routes", strategy.getName(context));
    }

    @Test
    void proposesNameWithOverriddenSuffix() {
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy =
                context -> "my-domain-v1";
        HttpRouteEgressNamingStrategy strategy = new HttpRouteEgressNamingStrategy(
                new K8sNameVerifier(),
                new K8sNameValidator(),
                integrationResourceNamingStrategy,
                "-egress");

        ResourceBuildContext<List<Snapshot>> context = ResourceBuildContext.create(
                BuildInfo.builder().options(ResourceBuildOptions.builder().name("my-domain").build()).build()
        ).updateTo(Collections.emptyList());

        assertEquals("my-domain-v1-egress", strategy.getName(context));
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl runtime-catalog test -Dtest=HttpRouteEgressNamingStrategyTest`
Expected: FAIL to compile (`HttpRouteEgressNamingStrategy` doesn't exist yet)

- [ ] **Step 3: Implement the naming strategy**

```java
package org.qubership.integration.platform.runtime.catalog.cr.naming.strategies;

import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuildContext;
import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNameVerifier;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNames;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component("httpRouteEgressNamingStrategy")
public class HttpRouteEgressNamingStrategy extends K8sResourceNamingStrategy<ResourceBuildContext<List<Snapshot>>> {
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy;
    private final K8sNameValidator nameValidator;
    private final String suffix;

    @Autowired
    public HttpRouteEgressNamingStrategy(
            K8sNameVerifier nameVerifier,
            K8sNameValidator nameValidator,

            @Qualifier("integrationResourceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy,

            @Value("${qip.cr.naming.http-route.egress-suffix:-egress-routes}")
            String suffix
    ) {
        super(nameVerifier);
        this.integrationResourceNamingStrategy = integrationResourceNamingStrategy;
        this.nameValidator = nameValidator;
        this.suffix = suffix;
    }

    @Override
    protected String proposeName(ResourceBuildContext<List<Snapshot>> context) {
        String base = integrationResourceNamingStrategy.getName(context);
        // Reserve room for the full suffix before truncating, so a long base name can never cut
        // into the suffix, the same way the public/private tiers already guard against it.
        int maxBaseLength = K8sNames.K8S_RESOURCE_NAME_LENGTH_LIMIT - suffix.length();
        if (maxBaseLength > 0 && base.length() > maxBaseLength) {
            base = base.substring(0, maxBaseLength);
        }
        return nameValidator.validate(base + suffix);
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -pl runtime-catalog test -Dtest=HttpRouteEgressNamingStrategyTest`
Expected: PASS (2 tests)

- [ ] **Step 5: Commit**

```bash
git add runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/cr/naming/strategies/HttpRouteEgressNamingStrategy.java \
        runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/cr/naming/strategies/HttpRouteEgressNamingStrategyTest.java
git commit -m "feat(runtime-catalog): add HttpRouteEgressNamingStrategy"
```

---

### Task 6: `ServiceEntry`/`DestinationRule` in `KubeOperator.createOrUpdateResource` (runtime-catalog)

**Files:**
- Modify: `runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/kubernetes/KubeOperator.java`
- Test: `runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/kubernetes/KubeOperatorTest.java`

**Interfaces:**
- Consumes: nothing new (uses the existing generic `createOrUpdateCustomResource` helper already used for `HTTPRoute`).
- Produces: `createOrUpdateResource(Object resource)` now applies a parsed `ServiceEntry`/`DestinationRule` `KubeCustomObject` directly (mirroring the existing `HTTPRoute` special case), instead of falling through to `GenericCustomResources` -- which returns an empty definitions map under the "localdev" profile and would throw. Consumed by Task 7's `EgressRouteResourceBuilder`-generated YAML, deployed through `CustomResourceService.deploy()`.

- [ ] **Step 1: Write the failing tests**

Add to `KubeOperatorTest.java`, immediately after the existing `createOrUpdateResourceAppliesParsedHttpRouteWithoutGenericCustomResources` test:

```java
    @Test
    void createOrUpdateResourceAppliesParsedServiceEntryWithoutGenericCustomResources() throws Exception {
        String istioGroup = "networking.istio.io";
        String istioVersion = "v1";
        String plural = "serviceentries";
        String name = "api-example-com-a1b2c3d4";
        ModelMapper.addModelMap(istioGroup, istioVersion, "ServiceEntry", plural, KubeCustomObject.class, KubeCustomObjectList.class);
        String serviceEntryYaml = "apiVersion: " + istioGroup + "/" + istioVersion + "\n"
                + "kind: ServiceEntry\n"
                + "metadata:\n"
                + "  name: " + name + "\n"
                + "spec:\n"
                + "  hosts:\n"
                + "    - api.example.com\n"
                + "  location: MESH_EXTERNAL\n"
                + "  resolution: DNS\n"
                + "  ports:\n"
                + "    - number: 443\n"
                + "      name: https\n"
                + "      protocol: HTTPS\n";
        List<Object> parsed = Yaml.loadAll(serviceEntryYaml);
        assertEquals(1, parsed.size());
        Object resource = parsed.get(0);
        assertTrue(resource instanceof KubeCustomObject);

        CustomObjectsApi.APIlistNamespacedCustomObjectRequest listRequest =
                mock(CustomObjectsApi.APIlistNamespacedCustomObjectRequest.class);
        when(customObjectsApi.listNamespacedCustomObject(istioGroup, istioVersion, NAMESPACE, plural)).thenReturn(listRequest);
        Map<String, Object> emptyList = new LinkedHashMap<>();
        emptyList.put("items", List.of());
        when(listRequest.execute()).thenReturn(emptyList);

        CustomObjectsApi.APIcreateNamespacedCustomObjectRequest createRequest =
                mock(CustomObjectsApi.APIcreateNamespacedCustomObjectRequest.class);
        when(customObjectsApi.createNamespacedCustomObject(eq(istioGroup), eq(istioVersion), eq(NAMESPACE), eq(plural), any()))
                .thenReturn(createRequest);
        when(createRequest.execute()).thenReturn(new Object());

        assertDoesNotThrow(() -> kubeOperator.createOrUpdateResource(resource));

        verify(customObjectsApi).createNamespacedCustomObject(eq(istioGroup), eq(istioVersion), eq(NAMESPACE), eq(plural), any());
    }

    @Test
    void createOrUpdateResourceAppliesParsedDestinationRuleWithoutGenericCustomResources() throws Exception {
        String istioGroup = "networking.istio.io";
        String istioVersion = "v1";
        String plural = "destinationrules";
        String name = "api-example-com-a1b2c3d4";
        ModelMapper.addModelMap(istioGroup, istioVersion, "DestinationRule", plural, KubeCustomObject.class, KubeCustomObjectList.class);
        String destinationRuleYaml = "apiVersion: " + istioGroup + "/" + istioVersion + "\n"
                + "kind: DestinationRule\n"
                + "metadata:\n"
                + "  name: " + name + "\n"
                + "spec:\n"
                + "  host: api.example.com\n"
                + "  trafficPolicy:\n"
                + "    portLevelSettings:\n"
                + "      - port:\n"
                + "          number: 443\n"
                + "        tls:\n"
                + "          mode: SIMPLE\n"
                + "          sni: api.example.com\n";
        List<Object> parsed = Yaml.loadAll(destinationRuleYaml);
        assertEquals(1, parsed.size());
        Object resource = parsed.get(0);
        assertTrue(resource instanceof KubeCustomObject);

        CustomObjectsApi.APIlistNamespacedCustomObjectRequest listRequest =
                mock(CustomObjectsApi.APIlistNamespacedCustomObjectRequest.class);
        when(customObjectsApi.listNamespacedCustomObject(istioGroup, istioVersion, NAMESPACE, plural)).thenReturn(listRequest);
        Map<String, Object> emptyList = new LinkedHashMap<>();
        emptyList.put("items", List.of());
        when(listRequest.execute()).thenReturn(emptyList);

        CustomObjectsApi.APIcreateNamespacedCustomObjectRequest createRequest =
                mock(CustomObjectsApi.APIcreateNamespacedCustomObjectRequest.class);
        when(customObjectsApi.createNamespacedCustomObject(eq(istioGroup), eq(istioVersion), eq(NAMESPACE), eq(plural), any()))
                .thenReturn(createRequest);
        when(createRequest.execute()).thenReturn(new Object());

        assertDoesNotThrow(() -> kubeOperator.createOrUpdateResource(resource));

        verify(customObjectsApi).createNamespacedCustomObject(eq(istioGroup), eq(istioVersion), eq(NAMESPACE), eq(plural), any());
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl runtime-catalog test -Dtest=KubeOperatorTest`
Expected: FAIL -- `createOrUpdateResource` falls through to the generic `KubeCustomObject` branch, which calls `genericCustomResources.definitionFor("ServiceEntry")`/`definitionFor("DestinationRule")` on a mock that returns nothing configured for those kinds, throwing.

- [ ] **Step 3: Add the constants**

In `KubeOperator.java`, add alongside the existing `HTTP_ROUTE_KIND`/`GATEWAY_API_GROUP`/`GATEWAY_API_VERSION` constants:

```java
    private static final String SERVICE_ENTRY_KIND = "ServiceEntry";
    private static final String DESTINATION_RULE_KIND = "DestinationRule";
    private static final String ISTIO_NETWORKING_API_GROUP = "networking.istio.io";
    private static final String ISTIO_NETWORKING_API_VERSION = "v1";
    private static final String SERVICE_ENTRIES_PLURAL = "serviceentries";
    private static final String DESTINATION_RULES_PLURAL = "destinationrules";
```

- [ ] **Step 4: Add the two branches to `createOrUpdateResource`**

Replace:

```java
        } else if (resource instanceof KubeCustomObject customObject && HTTP_ROUTE_KIND.equals(customObject.getKind())) {
            // HTTPRoute is handled directly (not through GenericCustomResources) because that map
            // returns empty under the "localdev" profile, which would make definitionFor() throw
            // there too. It's also always safe to update in place if it already exists.
            log.debug("Applying {} name={}", customObject.getKind(), getName(customObject).orElse(""));
            createOrUpdateCustomResource(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL, customObject,
                    new TypeToken<KubeCustomObjectList>() {}.getType(), true);
        } else if (resource instanceof KubeCustomObject customObject) {
```

with:

```java
        } else if (resource instanceof KubeCustomObject customObject && HTTP_ROUTE_KIND.equals(customObject.getKind())) {
            // HTTPRoute is handled directly (not through GenericCustomResources) because that map
            // returns empty under the "localdev" profile, which would make definitionFor() throw
            // there too. It's also always safe to update in place if it already exists.
            log.debug("Applying {} name={}", customObject.getKind(), getName(customObject).orElse(""));
            createOrUpdateCustomResource(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL, customObject,
                    new TypeToken<KubeCustomObjectList>() {}.getType(), true);
        } else if (resource instanceof KubeCustomObject customObject && SERVICE_ENTRY_KIND.equals(customObject.getKind())) {
            // Same rationale as HTTPRoute above: handled directly, not through GenericCustomResources.
            log.debug("Applying {} name={}", customObject.getKind(), getName(customObject).orElse(""));
            createOrUpdateCustomResource(ISTIO_NETWORKING_API_GROUP, ISTIO_NETWORKING_API_VERSION, SERVICE_ENTRIES_PLURAL,
                    customObject, new TypeToken<KubeCustomObjectList>() {}.getType(), true);
        } else if (resource instanceof KubeCustomObject customObject && DESTINATION_RULE_KIND.equals(customObject.getKind())) {
            // Same rationale as HTTPRoute above: handled directly, not through GenericCustomResources.
            log.debug("Applying {} name={}", customObject.getKind(), getName(customObject).orElse(""));
            createOrUpdateCustomResource(ISTIO_NETWORKING_API_GROUP, ISTIO_NETWORKING_API_VERSION, DESTINATION_RULES_PLURAL,
                    customObject, new TypeToken<KubeCustomObjectList>() {}.getType(), true);
        } else if (resource instanceof KubeCustomObject customObject) {
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -pl runtime-catalog test -Dtest=KubeOperatorTest`
Expected: PASS (all pre-existing tests plus the 2 new ones)

- [ ] **Step 6: Commit**

```bash
git add runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/kubernetes/KubeOperator.java \
        runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/kubernetes/KubeOperatorTest.java
git commit -m "feat(runtime-catalog): apply ServiceEntry/DestinationRule directly in KubeOperator"
```

---

### Task 7: `EgressRouteResourceBuilder` (runtime-catalog)

**Files:**
- Create: `runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/cr/builders/chain/EgressRouteResourceBuilder.java`
- Test: `runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/cr/builders/chain/EgressRouteResourceBuilderTest.java`

**Interfaces:**
- Consumes: `EgressTarget` (Task 2), `HttpRouteEgressNamingStrategy` bean (Task 5), the existing `GatewayPathMatch`, `RoutesGetterService`, `DeploymentRouteMapper`, `HttpRouteRuleNormalizer`, `K8sNameValidator`.
- Produces: `@Component EgressRouteResourceBuilder implements ResourceBuilder<List<Snapshot>>`, and its public cache-key constant `EGRESS_HTTP_ROUTE_CACHE_KEY`, which Task 8's `CustomResourceBuildContextFactory` change reads to seed redeploy-preserves-untouched-rules behavior (the same role `HttpRouteResourceBuilder.PUBLIC_HTTP_ROUTE_CACHE_KEY`/`PRIVATE_HTTP_ROUTE_CACHE_KEY` already play).

- [ ] **Step 1: Write the failing tests**

```java
package org.qubership.integration.platform.runtime.catalog.cr.builders.chain;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.runtime.catalog.cr.BuildInfo;
import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuildContext;
import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildOptions;
import org.qubership.integration.platform.runtime.catalog.model.deployment.RouteType;
import org.qubership.integration.platform.runtime.catalog.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.DeploymentRoute;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DeploymentRouteMapper;
import org.qubership.integration.platform.runtime.catalog.service.RoutesGetterService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EgressRouteResourceBuilderTest {

    private RoutesGetterService routesGetterService;
    private EgressRouteResourceBuilder builder;

    @BeforeEach
    void setUp() {
        routesGetterService = mock(RoutesGetterService.class);
        NamingStrategy<ResourceBuildContext<List<Snapshot>>> egressNamingStrategy =
                context -> "my-domain-v1-egress-routes";

        builder = new EgressRouteResourceBuilder(
                new YAMLMapper(),
                routesGetterService,
                org.mapstruct.factory.Mappers.getMapper(DeploymentRouteMapper.class),
                egressNamingStrategy,
                new K8sNameValidator());
        org.springframework.test.util.ReflectionTestUtils.setField(builder, "domainLabel", "qip.domain");
        org.springframework.test.util.ReflectionTestUtils.setField(builder, "bgVersionLabel", "qip.bg-version");
        org.springframework.test.util.ReflectionTestUtils.setField(builder, "bgVersion", "v1");
    }

    private ResourceBuildContext<List<Snapshot>> contextWithSnapshot(String snapshotId) {
        Snapshot snapshot = mock(Snapshot.class);
        when(snapshot.getId()).thenReturn(snapshotId);
        return ResourceBuildContext.create(BuildInfo.builder()
                        .options(ResourceBuildOptions.builder().name("my-domain").build())
                        .build())
                .updateTo(List.of(snapshot));
    }

    @Test
    void disabledWhenThereAreNoEgressRoutes() {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("/chain-a").type(RouteType.EXTERNAL_TRIGGER).build()));

        assertFalse(builder.enabled(contextWithSnapshot("snap-1")));
    }

    @Test
    void enabledWhenThereIsAtLeastOneEgressRoute() {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("https://api.example.com").gatewayPrefix("/system/elem-a")
                        .type(RouteType.EXTERNAL_SERVICE).build()));

        assertTrue(builder.enabled(contextWithSnapshot("snap-1")));
    }

    @Test
    void buildEmitsHttpRouteServiceEntryAndDestinationRuleForAnHttpsRoute() throws Exception {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("https://api.example.com/v2").gatewayPrefix("/system/elem-a")
                        .type(RouteType.EXTERNAL_SERVICE).build()));

        String result = builder.build(contextWithSnapshot("snap-1"));

        assertTrue(result.contains("kind: HTTPRoute"));
        assertTrue(result.contains("name: my-domain-v1-egress-routes"));
        assertTrue(result.contains("name: egress-gateway"));
        assertTrue(result.contains("value: \"/system/elem-a\""));
        assertTrue(result.contains("kind: ServiceEntry"));
        assertTrue(result.contains("api.example.com"));
        assertTrue(result.contains("kind: DestinationRule"));
        assertTrue(result.contains("mode: SIMPLE"));
    }

    @Test
    void buildOmitsDestinationRuleForAnHttpRoute() throws Exception {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("http://backend:9090").gatewayPrefix("/http-sender/elem-a/hash")
                        .type(RouteType.EXTERNAL_SENDER).build()));

        String result = builder.build(contextWithSnapshot("snap-1"));

        assertTrue(result.contains("kind: ServiceEntry"));
        assertFalse(result.contains("kind: DestinationRule"));
    }

    @Test
    void buildEmitsOneServiceEntryForTwoRoutesSharingAHost() throws Exception {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("https://api.example.com/a").gatewayPrefix("/system/elem-a")
                        .type(RouteType.EXTERNAL_SERVICE).build(),
                DeploymentRoute.builder().path("https://api.example.com/b").gatewayPrefix("/system/elem-b")
                        .type(RouteType.EXTERNAL_SERVICE).build()));

        String result = builder.build(contextWithSnapshot("snap-1"));

        assertEqualsOccurrences(1, "kind: ServiceEntry", result);
    }

    @Test
    void buildPreservesUntouchedRulesFromTheCache() throws Exception {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("https://api.example.com/v2").gatewayPrefix("/system/elem-a")
                        .type(RouteType.EXTERNAL_SERVICE).build()));
        Map<String, Object> pathMatch = Map.of("type", "PathPrefix", "value", "/system/other-elem");
        Map<String, Object> match = Map.of("path", pathMatch);
        Map<String, Object> existingRule = new LinkedHashMap<>();
        existingRule.put("matches", List.of(match));
        Map<String, Object> existingSpec = new LinkedHashMap<>();
        existingSpec.put("rules", List.of(existingRule));

        ResourceBuildContext<List<Snapshot>> context = contextWithSnapshot("snap-1");
        context.getBuildCache().put(EgressRouteResourceBuilder.EGRESS_HTTP_ROUTE_CACHE_KEY, existingSpec);

        String result = builder.build(context);

        assertTrue(result.contains("/system/other-elem"));
        assertTrue(result.contains("/system/elem-a"));
    }

    private void assertEqualsOccurrences(int expected, String needle, String haystack) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        org.junit.jupiter.api.Assertions.assertEquals(expected, count);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl runtime-catalog test -Dtest=EgressRouteResourceBuilderTest`
Expected: FAIL to compile (`EgressRouteResourceBuilder` doesn't exist yet)

- [ ] **Step 3: Implement `EgressRouteResourceBuilder`**

```java
package org.qubership.integration.platform.runtime.catalog.cr.builders.chain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.cr.CustomResourceBuildError;
import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuildContext;
import org.qubership.integration.platform.runtime.catalog.cr.ResourceBuilder;
import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.runtime.catalog.model.deployment.RouteType;
import org.qubership.integration.platform.runtime.catalog.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.DeploymentRoute;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DeploymentRouteMapper;
import org.qubership.integration.platform.runtime.catalog.service.RoutesGetterService;
import org.qubership.integration.platform.runtime.catalog.util.GatewayDuration;
import org.qubership.integration.platform.runtime.catalog.util.paths.EgressTarget;
import org.qubership.integration.platform.runtime.catalog.util.paths.GatewayPathMatch;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Component
@ConditionalOnProperty(name = "qip.control-plane.mesh-type", havingValue = "Istio")
public class EgressRouteResourceBuilder implements ResourceBuilder<List<Snapshot>> {
    public static final String EGRESS_HTTP_ROUTE_CACHE_KEY = "egressHttpRoute";
    private static final String ROUTES_CACHE_KEY = "egressRouteResourceBuilder.routes";

    private static final String GATEWAY_API_GROUP = "gateway.networking.k8s.io";
    private static final String GATEWAY_API_VERSION = "v1";
    private static final String EGRESS_GATEWAY_NAME = "egress-gateway";

    private static final String NETWORKING_ISTIO_API_GROUP = "networking.istio.io";
    private static final String NETWORKING_ISTIO_API_VERSION = "v1";

    private final YAMLMapper yamlMapper;
    private final RoutesGetterService routesGetterService;
    private final DeploymentRouteMapper deploymentRouteMapper;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRouteEgressNamingStrategy;
    private final K8sNameValidator k8sNameValidator;

    @Value("${qip.cr.labels.domain}")
    String domainLabel;

    @Value("${qip.cr.labels.bg-version}")
    String bgVersionLabel;

    @Value("${spring.application.deployment_version}")
    String bgVersion;

    @Autowired
    public EgressRouteResourceBuilder(
            @Qualifier("customResourceYamlMapper") YAMLMapper yamlMapper,
            RoutesGetterService routesGetterService,
            DeploymentRouteMapper deploymentRouteMapper,

            @Qualifier("httpRouteEgressNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRouteEgressNamingStrategy,

            K8sNameValidator k8sNameValidator
    ) {
        this.yamlMapper = yamlMapper;
        this.routesGetterService = routesGetterService;
        this.deploymentRouteMapper = deploymentRouteMapper;
        this.httpRouteEgressNamingStrategy = httpRouteEgressNamingStrategy;
        this.k8sNameValidator = k8sNameValidator;
    }

    @Override
    public boolean enabled(ResourceBuildContext<List<Snapshot>> context) {
        return !collectRoutes(context).isEmpty();
    }

    @Override
    public String build(ResourceBuildContext<List<Snapshot>> context) throws Exception {
        List<DeploymentRouteUpdate> routes = collectRoutes(context);

        StringBuilder out = new StringBuilder();
        appendEgressHttpRoute(out, context, routes);
        appendHostResources(out, routes);
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private List<DeploymentRouteUpdate> collectRoutes(ResourceBuildContext<List<Snapshot>> context) {
        Object cached = context.getBuildCache().get(ROUTES_CACHE_KEY);
        if (cached != null) {
            return (List<DeploymentRouteUpdate>) cached;
        }
        List<String> snapshotIds = context.getData().stream().map(Snapshot::getId).toList();
        List<DeploymentRouteUpdate> updates;
        if (snapshotIds.isEmpty()) {
            updates = List.of();
        } else {
            Specification<ChainElement> spec = (root, query, cb) -> root.get("snapshot").get("id").in(snapshotIds);
            List<DeploymentRoute> routes = routesGetterService.getRoutes(spec);
            updates = deploymentRouteMapper.asUpdates(routes).stream()
                    .filter(route -> route.getType() == RouteType.EXTERNAL_SENDER
                            || route.getType() == RouteType.EXTERNAL_SERVICE)
                    .toList();
        }
        context.getBuildCache().put(ROUTES_CACHE_KEY, updates);
        return updates;
    }

    private void appendEgressHttpRoute(
            StringBuilder out,
            ResourceBuildContext<List<Snapshot>> context,
            List<DeploymentRouteUpdate> routes
    ) {
        if (routes.isEmpty()) {
            return;
        }

        String name = httpRouteEgressNamingStrategy.getName(context);
        List<ObjectNode> preservedRules = preservedRulesFromCache(context, routes);
        List<ObjectNode> newRules = routes.stream().map(this::buildRule).toList();

        ObjectNode httpRoute = yamlMapper.createObjectNode();
        httpRoute.put("apiVersion", GATEWAY_API_GROUP + "/" + GATEWAY_API_VERSION);
        httpRoute.put("kind", "HTTPRoute");

        ObjectNode metadata = httpRoute.withObjectProperty("metadata");
        metadata.put("name", name);
        ObjectNode labels = metadata.withObject("labels");
        labels.put(domainLabel, k8sNameValidator.validate(context.getBuildInfo().getOptions().getName()));
        labels.put(bgVersionLabel, bgVersion);

        ObjectNode spec = httpRoute.withObjectProperty("spec");
        spec.withArray("parentRefs").addObject()
                .put("group", GATEWAY_API_GROUP)
                .put("kind", "Gateway")
                .put("name", EGRESS_GATEWAY_NAME);
        ArrayNode rules = spec.withArray("rules");
        preservedRules.forEach(rules::add);
        newRules.forEach(rules::add);

        appendYamlDocument(out, httpRoute, "egress HTTPRoute CR " + name);
    }

    private ObjectNode buildRule(DeploymentRouteUpdate route) {
        EgressTarget target = EgressTarget.parse(route.getPath());
        GatewayPathMatch pathMatch = GatewayPathMatch.forPath(route.getGatewayPrefix());
        ObjectNode rule = yamlMapper.createObjectNode();

        ObjectNode match = rule.withArray("matches").addObject();
        ObjectNode path = match.withObjectProperty("path");
        path.put("type", pathMatch.getType());
        path.put("value", pathMatch.getValue());

        ObjectNode filter = rule.withArray("filters").addObject();
        filter.put("type", "URLRewrite");
        ObjectNode urlRewrite = filter.withObjectProperty("urlRewrite");
        urlRewrite.put("hostname", target.host());
        ObjectNode rewritePath = urlRewrite.withObjectProperty("path");
        rewritePath.put("type", "ReplacePrefixMatch");
        rewritePath.put("replacePrefixMatch", target.path());

        ObjectNode backendRef = rule.withArray("backendRefs").addObject();
        backendRef.put("group", NETWORKING_ISTIO_API_GROUP);
        backendRef.put("kind", "Hostname");
        backendRef.put("name", target.host());
        backendRef.put("port", target.port());
        backendRef.put("weight", 1);

        if (route.getConnectTimeout() != null && route.getConnectTimeout() > 0) {
            ObjectNode timeouts = rule.withObjectProperty("timeouts");
            timeouts.put("request", GatewayDuration.formatMillis(route.getConnectTimeout()));
        }

        return rule;
    }

    @SuppressWarnings("unchecked")
    private List<ObjectNode> preservedRulesFromCache(
            ResourceBuildContext<List<Snapshot>> context,
            List<DeploymentRouteUpdate> routes
    ) {
        Object cached = context.getBuildCache().get(EGRESS_HTTP_ROUTE_CACHE_KEY);
        if (!(cached instanceof Map<?, ?> existingSpec)) {
            return List.of();
        }
        Object rulesRaw = existingSpec.get("rules");
        if (!(rulesRaw instanceof List<?> existingRules)) {
            return List.of();
        }

        Set<GatewayPathMatch> touchedPaths = routes.stream()
                .map(route -> GatewayPathMatch.forPath(route.getGatewayPrefix()))
                .collect(Collectors.toSet());

        List<ObjectNode> preserved = new ArrayList<>();
        for (Object ruleObj : existingRules) {
            ObjectNode ruleNode = yamlMapper.convertValue(ruleObj, ObjectNode.class);
            HttpRouteRuleNormalizer.normalizeIntegralDoubles(ruleNode);
            JsonNode pathNode = ruleNode.path("matches").path(0).path("path");
            String type = pathNode.path("type").asText(null);
            String value = pathNode.path("value").asText(null);
            if (value == null) {
                log.warn("Preserved egress HTTPRoute rule has no recognizable path match "
                        + "(matches[0].path.type/value); keeping it unconditionally rather than risk silently "
                        + "dropping it from the cluster: {}", ruleNode);
                preserved.add(ruleNode);
                continue;
            }
            if (type == null) {
                // Gateway API defaults HTTPPathMatch.type to PathPrefix when omitted.
                type = "PathPrefix";
            }
            if (!touchedPaths.contains(GatewayPathMatch.of(type, value))) {
                preserved.add(ruleNode);
            }
        }
        return preserved;
    }

    private void appendHostResources(StringBuilder out, List<DeploymentRouteUpdate> routes) {
        Map<String, EgressTarget> targetsByHost = new LinkedHashMap<>();
        for (DeploymentRouteUpdate route : routes) {
            EgressTarget target = EgressTarget.parse(route.getPath());
            targetsByHost.putIfAbsent(target.host(), target);
        }
        for (EgressTarget target : targetsByHost.values()) {
            appendServiceEntry(out, target);
            if (target.isHttps()) {
                appendDestinationRule(out, target);
            }
        }
    }

    private void appendServiceEntry(StringBuilder out, EgressTarget target) {
        ObjectNode serviceEntry = yamlMapper.createObjectNode();
        serviceEntry.put("apiVersion", NETWORKING_ISTIO_API_GROUP + "/" + NETWORKING_ISTIO_API_VERSION);
        serviceEntry.put("kind", "ServiceEntry");
        serviceEntry.withObjectProperty("metadata").put("name", target.hostResourceName());

        ObjectNode spec = serviceEntry.withObjectProperty("spec");
        spec.withArray("hosts").add(target.host());
        spec.put("location", "MESH_EXTERNAL");
        spec.put("resolution", "DNS");
        ObjectNode port = spec.withArray("ports").addObject();
        port.put("number", target.port());
        port.put("name", target.isHttps() ? "https" : "http");
        port.put("protocol", target.isHttps() ? "HTTPS" : "HTTP");

        appendYamlDocument(out, serviceEntry, "ServiceEntry " + target.hostResourceName());
    }

    private void appendDestinationRule(StringBuilder out, EgressTarget target) {
        ObjectNode destinationRule = yamlMapper.createObjectNode();
        destinationRule.put("apiVersion", NETWORKING_ISTIO_API_GROUP + "/" + NETWORKING_ISTIO_API_VERSION);
        destinationRule.put("kind", "DestinationRule");
        destinationRule.withObjectProperty("metadata").put("name", target.hostResourceName());

        ObjectNode spec = destinationRule.withObjectProperty("spec");
        spec.put("host", target.host());
        ObjectNode portLevelSettings = spec.withObjectProperty("trafficPolicy")
                .withArray("portLevelSettings").addObject();
        portLevelSettings.withObjectProperty("port").put("number", target.port());
        ObjectNode tls = portLevelSettings.withObjectProperty("tls");
        tls.put("mode", "SIMPLE");
        tls.put("sni", target.host());

        appendYamlDocument(out, destinationRule, "DestinationRule " + target.hostResourceName());
    }

    private void appendYamlDocument(StringBuilder out, ObjectNode document, String description) {
        try {
            out.append(yamlMapper.writeValueAsString(document));
            if (out.charAt(out.length() - 1) != '\n') {
                out.append('\n');
            }
        } catch (Exception e) {
            throw new CustomResourceBuildError("Failed to build " + description, e);
        }
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -pl runtime-catalog test -Dtest=EgressRouteResourceBuilderTest`
Expected: PASS (7 tests)

- [ ] **Step 5: Commit**

```bash
git add runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/cr/builders/chain/EgressRouteResourceBuilder.java \
        runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/cr/builders/chain/EgressRouteResourceBuilderTest.java
git commit -m "feat(runtime-catalog): add EgressRouteResourceBuilder"
```

---

### Task 8: Wire the egress tier into `CustomResourceService` lifecycle (runtime-catalog)

**Files:**
- Modify: `runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/cr/CustomResourceService.java`
- Modify: `runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/cr/CustomResourceBuildContextFactory.java`
- Test: `runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/cr/CustomResourceServiceTest.java`

**Interfaces:**
- Consumes: `HttpRouteEgressNamingStrategy` bean (Task 5), `EgressRouteResourceBuilder.EGRESS_HTTP_ROUTE_CACHE_KEY` (Task 7).
- Produces: `CustomResourceService.IntegrationResources` gains an `egressHttpRoute` field; `deleteHttpRoutes`/`deleteChainSnapshotHttpRoutes` cover the egress tier; `ServiceEntry`/`DestinationRule` registered in `init()` so `deploy()`'s `Yaml.loadAll` can deserialize them (their actual apply logic is Task 6's `KubeOperator` branches).

- [ ] **Step 1: Write the failing tests**

Modify `CustomResourceServiceTest.java`'s imports and `setUp()`:

```java
import org.qubership.integration.platform.runtime.catalog.cr.naming.strategies.HttpRouteEgressNamingStrategy;
```

Add the constant and update `setUp()`:

```java
    private static final String EGRESS_ROUTE_NAME = "my-domain-v1-egress-routes";
```

Replace:

```java
        EngineRoutesNamingStrategy engineRoutesNamingStrategy = new EngineRoutesNamingStrategy(
                new K8sNameVerifier(), new K8sNameValidator(), integrationResourceNamingStrategy,
                "-routes");

        customResourceService = new CustomResourceService(
                kubeOperator,
                integrationResourceNamingStrategy,
                context -> "my-domain-v1-cfg",
                mock(IntegrationConfigurationSerdes.class),
                mock(GenericCustomResources.class),
                false,
                routesGetterService,
                Mappers.getMapper(DeploymentRouteMapper.class),
                publicNamingStrategy,
                privateNamingStrategy,
                engineRoutesNamingStrategy,
                new YAMLMapper()
        );
```

with:

```java
        EngineRoutesNamingStrategy engineRoutesNamingStrategy = new EngineRoutesNamingStrategy(
                new K8sNameVerifier(), new K8sNameValidator(), integrationResourceNamingStrategy,
                "-routes");
        HttpRouteEgressNamingStrategy egressNamingStrategy = new HttpRouteEgressNamingStrategy(
                new K8sNameVerifier(), new K8sNameValidator(), integrationResourceNamingStrategy,
                "-egress-routes");

        customResourceService = new CustomResourceService(
                kubeOperator,
                integrationResourceNamingStrategy,
                context -> "my-domain-v1-cfg",
                mock(IntegrationConfigurationSerdes.class),
                mock(GenericCustomResources.class),
                false,
                routesGetterService,
                Mappers.getMapper(DeploymentRouteMapper.class),
                publicNamingStrategy,
                privateNamingStrategy,
                egressNamingStrategy,
                engineRoutesNamingStrategy,
                new YAMLMapper()
        );
```

Replace the existing `deleteHttpRoutesDeletesBothComputedTierNamesUnconditionally` test:

```java
    @Test
    void deleteHttpRoutesDeletesBothComputedTierNamesUnconditionally() {
        customResourceService.deleteHttpRoutes(DOMAIN);

        verify(kubeOperator).deleteCustomObject(GROUP, VERSION, PLURAL, PUBLIC_ROUTE_NAME);
        verify(kubeOperator).deleteCustomObject(GROUP, VERSION, PLURAL, PRIVATE_ROUTE_NAME);
    }
```

with:

```java
    @Test
    void deleteHttpRoutesDeletesAllComputedTierNamesUnconditionally() {
        customResourceService.deleteHttpRoutes(DOMAIN);

        verify(kubeOperator).deleteCustomObject(GROUP, VERSION, PLURAL, PUBLIC_ROUTE_NAME);
        verify(kubeOperator).deleteCustomObject(GROUP, VERSION, PLURAL, PRIVATE_ROUTE_NAME);
        verify(kubeOperator).deleteCustomObject(GROUP, VERSION, PLURAL, EGRESS_ROUTE_NAME);
    }
```

Replace the existing `deleteChainSnapshotWithOnlyEgressRoutesDoesNothing` test (its premise -- that a snapshot with only egress routes does nothing -- is exactly what this task changes):

```java
    @Test
    void deleteChainSnapshotWithOnlyEgressRoutesDoesNothing() {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("https://example.com").type(RouteType.EXTERNAL_SENDER).build()));

        customResourceService.deleteChainSnapshotHttpRoutes(DOMAIN, "snapshot-1");

        verify(kubeOperator, never()).getCustomObject(any(), any(), any(), any());
    }
```

with:

```java
    @Test
    void deleteChainSnapshotWithOnlyEgressRoutesStripsThemFromTheEgressCr() {
        when(routesGetterService.getRoutes(any())).thenReturn(List.of(
                DeploymentRoute.builder().path("https://example.com").gatewayPrefix("/system/service-a")
                        .type(RouteType.EXTERNAL_SENDER).build()));
        when(kubeOperator.getCustomObject(eq(GROUP), eq(VERSION), eq(PLURAL), eq(EGRESS_ROUTE_NAME)))
                .thenReturn(Optional.of(httpRoute(EGRESS_ROUTE_NAME, List.of(rule("/system/service-a")))));

        customResourceService.deleteChainSnapshotHttpRoutes(DOMAIN, "snapshot-1");

        verify(kubeOperator, never()).getCustomObject(eq(GROUP), eq(VERSION), eq(PLURAL), eq(PUBLIC_ROUTE_NAME));
        verify(kubeOperator, never()).getCustomObject(eq(GROUP), eq(VERSION), eq(PLURAL), eq(PRIVATE_ROUTE_NAME));
        verify(kubeOperator).deleteCustomObject(GROUP, VERSION, PLURAL, EGRESS_ROUTE_NAME);
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl runtime-catalog test -Dtest=CustomResourceServiceTest`
Expected: FAIL to compile (no `httpRouteEgressNamingStrategy` constructor parameter yet)

- [ ] **Step 3: Update `IntegrationResources` and the constructor**

In `CustomResourceService.java`, replace the record declaration:

```java
    public record IntegrationResources(
            CamelKIntegration integration,
            V1ServiceMonitor serviceMonitor,
            V1Service service,
            V1ConfigMap integrationsConfiguration,
            Collection<V1ConfigMap> integrationSources,
            V1Secret secret,
            Collection<KubeCustomObject> customResources,
            KubeCustomObject publicHttpRoute,
            KubeCustomObject privateHttpRoute
    ) {
```

with:

```java
    public record IntegrationResources(
            CamelKIntegration integration,
            V1ServiceMonitor serviceMonitor,
            V1Service service,
            V1ConfigMap integrationsConfiguration,
            Collection<V1ConfigMap> integrationSources,
            V1Secret secret,
            Collection<KubeCustomObject> customResources,
            KubeCustomObject publicHttpRoute,
            KubeCustomObject privateHttpRoute,
            KubeCustomObject egressHttpRoute
    ) {
```

Add the import and field, and extend the constructor. Replace:

```java
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePublicNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePrivateNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> engineRoutesNamingStrategy;
```

with:

```java
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePublicNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePrivateNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRouteEgressNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> engineRoutesNamingStrategy;
```

Replace the constructor:

```java
    @Autowired
    public CustomResourceService(
            KubeOperator kubeOperator,
            @Qualifier("integrationResourceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy,
            @Qualifier("integrationsConfigurationResourceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationsConfigurationConfigMapNamingStrategy,
            IntegrationConfigurationSerdes integrationConfigurationSerdes,
            GenericCustomResources genericCustomResources,
            @Value("${qip.cr.build.monitoring.enabled:false}") boolean monitoringEnabled,
            RoutesGetterService routesGetterService,
            DeploymentRouteMapper deploymentRouteMapper,
            @Qualifier("httpRoutePublicNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePublicNamingStrategy,
            @Qualifier("httpRoutePrivateNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePrivateNamingStrategy,
            @Qualifier("engineRoutesNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> engineRoutesNamingStrategy,
            @Qualifier("customResourceYamlMapper") YAMLMapper yamlMapper
    ) {
        this.kubeOperator = kubeOperator;
        this.integrationResourceNamingStrategy = integrationResourceNamingStrategy;
        this.integrationsConfigurationConfigMapNamingStrategy = integrationsConfigurationConfigMapNamingStrategy;
        this.integrationConfigurationSerdes = integrationConfigurationSerdes;
        this.genericCustomResources = genericCustomResources;
        this.monitoringEnabled = monitoringEnabled;
        this.routesGetterService = routesGetterService;
        this.deploymentRouteMapper = deploymentRouteMapper;
        this.httpRoutePublicNamingStrategy = httpRoutePublicNamingStrategy;
        this.httpRoutePrivateNamingStrategy = httpRoutePrivateNamingStrategy;
        this.engineRoutesNamingStrategy = engineRoutesNamingStrategy;
        this.yamlMapper = yamlMapper;
    }
```

with:

```java
    @Autowired
    public CustomResourceService(
            KubeOperator kubeOperator,
            @Qualifier("integrationResourceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy,
            @Qualifier("integrationsConfigurationResourceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationsConfigurationConfigMapNamingStrategy,
            IntegrationConfigurationSerdes integrationConfigurationSerdes,
            GenericCustomResources genericCustomResources,
            @Value("${qip.cr.build.monitoring.enabled:false}") boolean monitoringEnabled,
            RoutesGetterService routesGetterService,
            DeploymentRouteMapper deploymentRouteMapper,
            @Qualifier("httpRoutePublicNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePublicNamingStrategy,
            @Qualifier("httpRoutePrivateNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePrivateNamingStrategy,
            @Qualifier("httpRouteEgressNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRouteEgressNamingStrategy,
            @Qualifier("engineRoutesNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> engineRoutesNamingStrategy,
            @Qualifier("customResourceYamlMapper") YAMLMapper yamlMapper
    ) {
        this.kubeOperator = kubeOperator;
        this.integrationResourceNamingStrategy = integrationResourceNamingStrategy;
        this.integrationsConfigurationConfigMapNamingStrategy = integrationsConfigurationConfigMapNamingStrategy;
        this.integrationConfigurationSerdes = integrationConfigurationSerdes;
        this.genericCustomResources = genericCustomResources;
        this.monitoringEnabled = monitoringEnabled;
        this.routesGetterService = routesGetterService;
        this.deploymentRouteMapper = deploymentRouteMapper;
        this.httpRoutePublicNamingStrategy = httpRoutePublicNamingStrategy;
        this.httpRoutePrivateNamingStrategy = httpRoutePrivateNamingStrategy;
        this.httpRouteEgressNamingStrategy = httpRouteEgressNamingStrategy;
        this.engineRoutesNamingStrategy = engineRoutesNamingStrategy;
        this.yamlMapper = yamlMapper;
    }
```

- [ ] **Step 4: Register `ServiceEntry`/`DestinationRule` in `init()`**

Replace:

```java
    @PostConstruct
    public void init() {
        ModelMapper.addModelMap("camel.apache.org", "v1", "Integration", "Integrations", CamelKIntegration.class, CamelKIntegrationList.class);
        ModelMapper.addModelMap("monitoring.coreos.com", "v1", "ServiceMonitor", "ServiceMonitors", V1ServiceMonitor.class, V1ServiceMonitorList.class);
        ModelMapper.addModelMap(GATEWAY_API_GROUP, GATEWAY_API_VERSION, "HTTPRoute", HTTP_ROUTES_PLURAL,
                KubeCustomObject.class, KubeCustomObjectList.class);
        genericCustomResources.registerModelMaps();
    }
```

with:

```java
    private static final String NETWORKING_ISTIO_API_GROUP = "networking.istio.io";
    private static final String NETWORKING_ISTIO_API_VERSION = "v1";

    @PostConstruct
    public void init() {
        ModelMapper.addModelMap("camel.apache.org", "v1", "Integration", "Integrations", CamelKIntegration.class, CamelKIntegrationList.class);
        ModelMapper.addModelMap("monitoring.coreos.com", "v1", "ServiceMonitor", "ServiceMonitors", V1ServiceMonitor.class, V1ServiceMonitorList.class);
        ModelMapper.addModelMap(GATEWAY_API_GROUP, GATEWAY_API_VERSION, "HTTPRoute", HTTP_ROUTES_PLURAL,
                KubeCustomObject.class, KubeCustomObjectList.class);
        ModelMapper.addModelMap(NETWORKING_ISTIO_API_GROUP, NETWORKING_ISTIO_API_VERSION, "ServiceEntry", "serviceentries",
                KubeCustomObject.class, KubeCustomObjectList.class);
        ModelMapper.addModelMap(NETWORKING_ISTIO_API_GROUP, NETWORKING_ISTIO_API_VERSION, "DestinationRule", "destinationrules",
                KubeCustomObject.class, KubeCustomObjectList.class);
        genericCustomResources.registerModelMaps();
    }
```

(Place the two new constants next to the existing `GATEWAY_API_GROUP`/`GATEWAY_API_VERSION`/`HTTP_ROUTES_PLURAL` constants at the top of the class rather than inline as shown here -- this diff shows them adjacent to `init()` only for readability.)

- [ ] **Step 5: Fetch `egressHttpRoute` in `getIntegrationResources`**

Replace:

```java
        String publicRouteName = httpRoutePublicNamingStrategy.getName(getContextForDomain(name));
        String privateRouteName = httpRoutePrivateNamingStrategy.getName(getContextForDomain(name));
        KubeCustomObject publicHttpRoute = kubeOperator
                .getCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL, publicRouteName)
                .orElse(null);
        KubeCustomObject privateHttpRoute = kubeOperator
                .getCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL, privateRouteName)
                .orElse(null);

        return Optional.of(new IntegrationResources(
                integration.orElse(null),
                serviceMonitor.orElse(null),
                service.orElse(null),
                integrationsConfiguration.orElse(null),
                integrationSources,
                secret.orElse(null),
                customResources,
                publicHttpRoute,
                privateHttpRoute
        ));
```

with:

```java
        String publicRouteName = httpRoutePublicNamingStrategy.getName(getContextForDomain(name));
        String privateRouteName = httpRoutePrivateNamingStrategy.getName(getContextForDomain(name));
        String egressRouteName = httpRouteEgressNamingStrategy.getName(getContextForDomain(name));
        KubeCustomObject publicHttpRoute = kubeOperator
                .getCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL, publicRouteName)
                .orElse(null);
        KubeCustomObject privateHttpRoute = kubeOperator
                .getCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL, privateRouteName)
                .orElse(null);
        KubeCustomObject egressHttpRoute = kubeOperator
                .getCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL, egressRouteName)
                .orElse(null);

        return Optional.of(new IntegrationResources(
                integration.orElse(null),
                serviceMonitor.orElse(null),
                service.orElse(null),
                integrationsConfiguration.orElse(null),
                integrationSources,
                secret.orElse(null),
                customResources,
                publicHttpRoute,
                privateHttpRoute,
                egressHttpRoute
        ));
```

- [ ] **Step 6: Extend `deleteHttpRoutes` and `deleteChainSnapshotHttpRoutes`**

Replace:

```java
    void deleteHttpRoutes(String name) {
        ResourceBuildContext<List<Snapshot>> context = getContextForDomain(name);
        kubeOperator.deleteCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL,
                httpRoutePublicNamingStrategy.getName(context));
        kubeOperator.deleteCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL,
                httpRoutePrivateNamingStrategy.getName(context));
    }
```

with:

```java
    void deleteHttpRoutes(String name) {
        ResourceBuildContext<List<Snapshot>> context = getContextForDomain(name);
        kubeOperator.deleteCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL,
                httpRoutePublicNamingStrategy.getName(context));
        kubeOperator.deleteCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL,
                httpRoutePrivateNamingStrategy.getName(context));
        kubeOperator.deleteCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL,
                httpRouteEgressNamingStrategy.getName(context));
    }
```

Replace:

```java
    void deleteChainSnapshotHttpRoutes(String name, String snapshotId) {
        Specification<ChainElement> spec = (root, query, cb) ->
                cb.equal(root.get("snapshot").get("id"), snapshotId);
        List<DeploymentRoute> ownRoutes = routesGetterService.getRoutes(spec);
        List<DeploymentRouteUpdate> ownUpdates = deploymentRouteMapper.asUpdates(ownRoutes);

        Set<GatewayPathMatch> publicPaths = tierOwnPaths(ownUpdates, RouteType::isExternalTriggerRoute);
        Set<GatewayPathMatch> privatePaths = tierOwnPaths(ownUpdates, RouteType::isPrivateTriggerRoute);

        if (publicPaths.isEmpty() && privatePaths.isEmpty()) {
            return;
        }
        if (!publicPaths.isEmpty()) {
            stripPathsFromTier(httpRoutePublicNamingStrategy.getName(getContextForDomain(name)), publicPaths, "public");
        }
        if (!privatePaths.isEmpty()) {
            stripPathsFromTier(httpRoutePrivateNamingStrategy.getName(getContextForDomain(name)), privatePaths, "private");
        }
    }
```

with:

```java
    void deleteChainSnapshotHttpRoutes(String name, String snapshotId) {
        Specification<ChainElement> spec = (root, query, cb) ->
                cb.equal(root.get("snapshot").get("id"), snapshotId);
        List<DeploymentRoute> ownRoutes = routesGetterService.getRoutes(spec);
        List<DeploymentRouteUpdate> ownUpdates = deploymentRouteMapper.asUpdates(ownRoutes);

        Set<GatewayPathMatch> publicPaths = tierOwnPaths(ownUpdates, RouteType::isExternalTriggerRoute);
        Set<GatewayPathMatch> privatePaths = tierOwnPaths(ownUpdates, RouteType::isPrivateTriggerRoute);
        Set<GatewayPathMatch> egressPaths = egressOwnPaths(ownUpdates);

        if (publicPaths.isEmpty() && privatePaths.isEmpty() && egressPaths.isEmpty()) {
            return;
        }
        if (!publicPaths.isEmpty()) {
            stripPathsFromTier(httpRoutePublicNamingStrategy.getName(getContextForDomain(name)), publicPaths, "public");
        }
        if (!privatePaths.isEmpty()) {
            stripPathsFromTier(httpRoutePrivateNamingStrategy.getName(getContextForDomain(name)), privatePaths, "private");
        }
        if (!egressPaths.isEmpty()) {
            stripPathsFromTier(httpRouteEgressNamingStrategy.getName(getContextForDomain(name)), egressPaths, "egress");
        }
    }
```

Add a new private helper next to `tierOwnPaths` (egress paths are matched on `gatewayPrefix` directly, not `baseRoutePrefix + path`, unlike the trigger tiers -- see the design spec):

```java
    /**
     * Builds the set of this snapshot's own egress route path matches. Unlike {@link #tierOwnPaths},
     * this reads {@code gatewayPrefix} (the resolved internal path, e.g. {@code /system/{id}}), not
     * {@code baseRoutePrefix + path} -- egress routes' {@code path} is the resolved external target
     * URL, not a gateway-facing path.
     */
    private Set<GatewayPathMatch> egressOwnPaths(List<DeploymentRouteUpdate> ownUpdates) {
        return ownUpdates.stream()
                .filter(route -> route.getType() == RouteType.EXTERNAL_SENDER
                        || route.getType() == RouteType.EXTERNAL_SERVICE)
                .map(route -> GatewayPathMatch.forPath(route.getGatewayPrefix()))
                .collect(Collectors.toSet());
    }
```

- [ ] **Step 7: Seed the egress cache key in `CustomResourceBuildContextFactory`**

In `CustomResourceBuildContextFactory.java`, add the import:

```java
import static org.qubership.integration.platform.runtime.catalog.cr.builders.chain.EgressRouteResourceBuilder.EGRESS_HTTP_ROUTE_CACHE_KEY;
```

Replace:

```java
    private void putHttpRouteRulesToBuildCache(
            ResourceBuildContext<List<Snapshot>> context,
            CustomResourceService.IntegrationResources resources
    ) {
        if (resources.publicHttpRoute() != null) {
            context.getBuildCache().put(PUBLIC_HTTP_ROUTE_CACHE_KEY, resources.publicHttpRoute().getSpec());
        }
        if (resources.privateHttpRoute() != null) {
            context.getBuildCache().put(PRIVATE_HTTP_ROUTE_CACHE_KEY, resources.privateHttpRoute().getSpec());
        }
    }
```

with:

```java
    private void putHttpRouteRulesToBuildCache(
            ResourceBuildContext<List<Snapshot>> context,
            CustomResourceService.IntegrationResources resources
    ) {
        if (resources.publicHttpRoute() != null) {
            context.getBuildCache().put(PUBLIC_HTTP_ROUTE_CACHE_KEY, resources.publicHttpRoute().getSpec());
        }
        if (resources.privateHttpRoute() != null) {
            context.getBuildCache().put(PRIVATE_HTTP_ROUTE_CACHE_KEY, resources.privateHttpRoute().getSpec());
        }
        if (resources.egressHttpRoute() != null) {
            context.getBuildCache().put(EGRESS_HTTP_ROUTE_CACHE_KEY, resources.egressHttpRoute().getSpec());
        }
    }
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./mvnw -pl runtime-catalog test -Dtest=CustomResourceServiceTest`
Expected: PASS (all pre-existing tests, updated as shown, plus the behavior change verified)

- [ ] **Step 9: Run the full runtime-catalog test suite**

Run: `./mvnw -pl runtime-catalog test`
Expected: PASS -- confirms `CustomResourceBuildContextFactory` and every other `CustomResourceService` caller compiles against the new constructor and record shape.

- [ ] **Step 10: Commit**

```bash
git add runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/cr/CustomResourceService.java \
        runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/cr/CustomResourceBuildContextFactory.java \
        runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/cr/CustomResourceServiceTest.java
git commit -m "feat(runtime-catalog): wire the egress tier into CustomResourceService lifecycle"
```

---

## Final Verification

After all 8 tasks:

- [ ] Run `./mvnw -pl engine,runtime-catalog test` (or the equivalent full build for both modules) and confirm everything passes.
- [ ] Grep both modules for any remaining reference to the old singular `postEgressGatewayRoutes(DeploymentRouteUpdate route)` signature (`grep -rn "postEgressGatewayRoutes" engine runtime-catalog`) to confirm no caller was missed.
- [ ] Re-read `docs/superpowers/specs/2026-08-17-egress-gateway-routes-design.md` against the final diff: confirm the HTTPRoute tier is per-micro-domain and shared, `ServiceEntry`/`DestinationRule` are host-keyed and never deleted, `enableInsecureTls` was not reintroduced, and namespaces match the public/private tier CRs (not `istio-system`).
