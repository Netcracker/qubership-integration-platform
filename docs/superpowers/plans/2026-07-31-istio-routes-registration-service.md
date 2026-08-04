# IstioRoutesRegistrationService Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give `engine` an Istio-native `ControlPlaneService` implementation that manages Gateway API `HTTPRoute` custom resources directly via Kubernetes, switchable at runtime alongside the existing Cloud-Core Mesh implementation.

**Architecture:** A new `IstioRoutesRegistrationService` implements `ControlPlaneService` by building/merging Gateway API `HTTPRoute` specs (via a small new POJO model, converted to/from `KubeCustomObject.spec`'s `Map<String, Object>` through the existing `ObjectMapper`) and applying them through `KubeOperator`, extended with two new methods (`getCustomObject`, `deleteCustomObject`) it currently lacks. A Spring property (`qip.control-plane.mesh-type`) selects it over the existing `ControlPlaneDefaultService`.

**Tech Stack:** Java 21, Spring Boot, Lombok, Jackson `ObjectMapper`, `io.kubernetes:client-java` (`CustomObjectsApi`), JUnit 5, Mockito.

**Spec:** `docs/superpowers/specs/2026-07-31-istio-routes-registration-service-design.md`

## Global Constraints

- New POJOs use the same Lombok shape as the existing `model.controlplane.v3.post` package: `@Getter @Setter @Builder @AllArgsConstructor @NoArgsConstructor @ToString`, no Jackson annotations except `@JsonInclude(JsonInclude.Include.NON_NULL)` on genuinely optional fields. Gateway API has no snake_case fields, so no naming-strategy configuration is needed anywhere.
- Gateway API resource identity: `apiVersion: gateway.networking.k8s.io/v1`, `kind: HTTPRoute`, plural `httproutes`.
- Backend port is fixed at `8080` (matches `ControlPlaneDefaultService.getRoute()`'s existing `http://<endpoint>:8080` target).
- Tests use plain JUnit 5 (`org.junit.jupiter.api.Test`, `org.junit.jupiter.api.Assertions.*`) and Mockito's `mock(Class)` factory with `static org.mockito.Mockito.*` imports — matches `McpToolUnregisterActionTest`/`McpToolRegistrarTest`, not `@ExtendWith(MockitoExtension.class)` + `@Mock`.
- Do not change `ControlPlaneService`'s method signatures — that refactor is already done and committed.
- Do not touch `micro-engine` — out of scope per the spec's Non-goals.

## File Structure

- **Create** `engine/src/main/java/org/qubership/integration/platform/engine/model/gatewayapi/` (9 files) — Gateway API `HTTPRoute` spec POJOs, mirroring `model.controlplane.v3.post`'s role for the Cloud-Core Mesh wire format.
- **Modify** `engine/src/main/java/org/qubership/integration/platform/engine/kubernetes/KubeOperator.java` — add `getCustomObject`/`deleteCustomObject` plus a package-private test constructor.
- **Create** `engine/src/test/java/org/qubership/integration/platform/engine/kubernetes/KubeOperatorTest.java`.
- **Modify** `engine/src/main/resources/application.yml` — add `qip.control-plane.mesh-type`.
- **Modify** `engine/src/main/java/org/qubership/integration/platform/engine/cloudcore/controlplane/ControlPlaneDefaultService.java` — stack a second `@ConditionalOnProperty` so it only activates for `mesh-type: Core`.
- **Create** `engine/src/main/java/org/qubership/integration/platform/engine/cloudcore/controlplane/IstioRoutesRegistrationService.java` — the new `ControlPlaneService` implementation.
- **Create** `engine/src/test/java/org/qubership/integration/platform/engine/cloudcore/controlplane/IstioRoutesRegistrationServiceTest.java`.

---

### Task 1: Gateway API `HTTPRoute` model classes

**Files:**
- Create: `engine/src/main/java/org/qubership/integration/platform/engine/model/gatewayapi/ParentReference.java`
- Create: `engine/src/main/java/org/qubership/integration/platform/engine/model/gatewayapi/HTTPBackendRef.java`
- Create: `engine/src/main/java/org/qubership/integration/platform/engine/model/gatewayapi/HTTPPathMatch.java`
- Create: `engine/src/main/java/org/qubership/integration/platform/engine/model/gatewayapi/HTTPRouteMatch.java`
- Create: `engine/src/main/java/org/qubership/integration/platform/engine/model/gatewayapi/HTTPPathModifier.java`
- Create: `engine/src/main/java/org/qubership/integration/platform/engine/model/gatewayapi/HTTPUrlRewriteFilter.java`
- Create: `engine/src/main/java/org/qubership/integration/platform/engine/model/gatewayapi/HTTPRouteFilter.java`
- Create: `engine/src/main/java/org/qubership/integration/platform/engine/model/gatewayapi/HTTPRouteTimeouts.java`
- Create: `engine/src/main/java/org/qubership/integration/platform/engine/model/gatewayapi/HTTPRouteRule.java`
- Create: `engine/src/main/java/org/qubership/integration/platform/engine/model/gatewayapi/HTTPRouteSpec.java`
- Test: `engine/src/test/java/org/qubership/integration/platform/engine/model/gatewayapi/HTTPRouteSpecTest.java`

**Interfaces:**
- Produces: `HTTPRouteSpec` (top-level, `parentRefs: List<ParentReference>`, `rules: List<HTTPRouteRule>`) and every type it's built from. Task 3 constructs and reads these types directly, and converts them to/from `Map<String, Object>` via `ObjectMapper.convertValue`.

- [ ] **Step 1: Write the failing test**

Create `engine/src/test/java/org/qubership/integration/platform/engine/model/gatewayapi/HTTPRouteSpecTest.java`:

```java
package org.qubership.integration.platform.engine.model.gatewayapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class HTTPRouteSpecTest {

    @Test
    @SuppressWarnings("unchecked")
    void convertsToMapWithGatewayApiFieldNames() {
        HTTPRouteSpec spec = HTTPRouteSpec.builder()
                .parentRefs(List.of(ParentReference.builder()
                        .group("gateway.networking.k8s.io")
                        .kind("Gateway")
                        .name("public-gateway")
                        .build()))
                .rules(List.of(HTTPRouteRule.builder()
                        .matches(List.of(HTTPRouteMatch.builder()
                                .path(HTTPPathMatch.builder()
                                        .type("PathPrefix")
                                        .value("/api/v1/chain-1")
                                        .build())
                                .build()))
                        .filters(List.of(HTTPRouteFilter.builder()
                                .type("URLRewrite")
                                .urlRewrite(HTTPUrlRewriteFilter.builder()
                                        .path(HTTPPathModifier.builder()
                                                .type("ReplacePrefixMatch")
                                                .replacePrefixMatch("/api/v1")
                                                .build())
                                        .build())
                                .build()))
                        .backendRefs(List.of(HTTPBackendRef.builder()
                                .group("")
                                .kind("Service")
                                .name("engine-service")
                                .port(8080)
                                .weight(1)
                                .build()))
                        .timeouts(HTTPRouteTimeouts.builder().request("30000ms").build())
                        .build()))
                .build();

        Map<String, Object> map = new ObjectMapper().convertValue(spec, Map.class);

        List<Map<String, Object>> parentRefs = (List<Map<String, Object>>) map.get("parentRefs");
        assertEquals("gateway.networking.k8s.io", parentRefs.get(0).get("group"));
        assertEquals("Gateway", parentRefs.get(0).get("kind"));
        assertEquals("public-gateway", parentRefs.get(0).get("name"));

        List<Map<String, Object>> rules = (List<Map<String, Object>>) map.get("rules");
        Map<String, Object> rule = rules.get(0);

        List<Map<String, Object>> matches = (List<Map<String, Object>>) rule.get("matches");
        Map<String, Object> path = (Map<String, Object>) matches.get(0).get("path");
        assertEquals("PathPrefix", path.get("type"));
        assertEquals("/api/v1/chain-1", path.get("value"));

        List<Map<String, Object>> filters = (List<Map<String, Object>>) rule.get("filters");
        Map<String, Object> filter = filters.get(0);
        assertEquals("URLRewrite", filter.get("type"));
        Map<String, Object> urlRewrite = (Map<String, Object>) filter.get("urlRewrite");
        Map<String, Object> pathModifier = (Map<String, Object>) urlRewrite.get("path");
        assertEquals("ReplacePrefixMatch", pathModifier.get("type"));
        assertEquals("/api/v1", pathModifier.get("replacePrefixMatch"));

        List<Map<String, Object>> backendRefs = (List<Map<String, Object>>) rule.get("backendRefs");
        Map<String, Object> backendRef = backendRefs.get(0);
        assertEquals("", backendRef.get("group"));
        assertEquals("Service", backendRef.get("kind"));
        assertEquals("engine-service", backendRef.get("name"));
        assertEquals(8080, backendRef.get("port"));
        assertEquals(1, backendRef.get("weight"));

        Map<String, Object> timeouts = (Map<String, Object>) rule.get("timeouts");
        assertEquals("30000ms", timeouts.get("request"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void omitsTimeoutsWhenNotSet() {
        HTTPRouteRule rule = HTTPRouteRule.builder()
                .matches(List.of())
                .filters(List.of())
                .backendRefs(List.of())
                .build();

        Map<String, Object> map = new ObjectMapper().convertValue(rule, Map.class);

        assertFalse(map.containsKey("timeouts"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `mvn -pl engine -am test -Dtest=HTTPRouteSpecTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compile failure — none of the referenced classes (`HTTPRouteSpec`, `ParentReference`, `HTTPRouteRule`, `HTTPRouteMatch`, `HTTPPathMatch`, `HTTPRouteFilter`, `HTTPUrlRewriteFilter`, `HTTPPathModifier`, `HTTPBackendRef`, `HTTPRouteTimeouts`) exist yet.

- [ ] **Step 3: Create the model classes**

Create `engine/src/main/java/org/qubership/integration/platform/engine/model/gatewayapi/ParentReference.java`:

```java
package org.qubership.integration.platform.engine.model.gatewayapi;

import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class ParentReference {
    private String group;
    private String kind;
    private String name;
}
```

Create `engine/src/main/java/org/qubership/integration/platform/engine/model/gatewayapi/HTTPBackendRef.java`:

```java
package org.qubership.integration.platform.engine.model.gatewayapi;

import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class HTTPBackendRef {
    private String group;
    private String kind;
    private String name;
    private Integer port;
    private Integer weight;
}
```

Create `engine/src/main/java/org/qubership/integration/platform/engine/model/gatewayapi/HTTPPathMatch.java`:

```java
package org.qubership.integration.platform.engine.model.gatewayapi;

import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class HTTPPathMatch {
    private String type;
    private String value;
}
```

Create `engine/src/main/java/org/qubership/integration/platform/engine/model/gatewayapi/HTTPRouteMatch.java`:

```java
package org.qubership.integration.platform.engine.model.gatewayapi;

import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class HTTPRouteMatch {
    private HTTPPathMatch path;
}
```

Create `engine/src/main/java/org/qubership/integration/platform/engine/model/gatewayapi/HTTPPathModifier.java`:

```java
package org.qubership.integration.platform.engine.model.gatewayapi;

import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class HTTPPathModifier {
    private String type;
    private String replacePrefixMatch;
}
```

Create `engine/src/main/java/org/qubership/integration/platform/engine/model/gatewayapi/HTTPUrlRewriteFilter.java`:

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
    private HTTPPathModifier path;
}
```

Create `engine/src/main/java/org/qubership/integration/platform/engine/model/gatewayapi/HTTPRouteFilter.java`:

```java
package org.qubership.integration.platform.engine.model.gatewayapi;

import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class HTTPRouteFilter {
    private String type;
    private HTTPUrlRewriteFilter urlRewrite;
}
```

Create `engine/src/main/java/org/qubership/integration/platform/engine/model/gatewayapi/HTTPRouteTimeouts.java`:

```java
package org.qubership.integration.platform.engine.model.gatewayapi;

import lombok.*;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class HTTPRouteTimeouts {
    private String request;
}
```

Create `engine/src/main/java/org/qubership/integration/platform/engine/model/gatewayapi/HTTPRouteRule.java`:

```java
package org.qubership.integration.platform.engine.model.gatewayapi;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class HTTPRouteRule {
    private List<HTTPRouteMatch> matches;
    private List<HTTPRouteFilter> filters;
    private List<HTTPBackendRef> backendRefs;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private HTTPRouteTimeouts timeouts;
}
```

Create `engine/src/main/java/org/qubership/integration/platform/engine/model/gatewayapi/HTTPRouteSpec.java`:

```java
package org.qubership.integration.platform.engine.model.gatewayapi;

import lombok.*;

import java.util.List;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class HTTPRouteSpec {
    private List<ParentReference> parentRefs;
    private List<HTTPRouteRule> rules;
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `mvn -pl engine -am test -Dtest=HTTPRouteSpecTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add engine/src/main/java/org/qubership/integration/platform/engine/model/gatewayapi/ engine/src/test/java/org/qubership/integration/platform/engine/model/gatewayapi/
git commit -m "feat: add Gateway API HTTPRoute model classes"
```

---

### Task 2: `KubeOperator` read and delete extensions

**Files:**
- Modify: `engine/src/main/java/org/qubership/integration/platform/engine/kubernetes/KubeOperator.java`
- Test: `engine/src/test/java/org/qubership/integration/platform/engine/kubernetes/KubeOperatorTest.java`

**Interfaces:**
- Consumes: `KubeCustomObjectRequest` (existing, unchanged — `getGroup()`, `getVersion()`, `getResourceNamePlural()`, `getBody(): KubeCustomObject`), `KubeCustomObject` (existing, unchanged — `getApiVersion()`, `getKind()`, `getMetadata(): V1ObjectMeta`, `getSpec(): Map<String, Object>`).
- Produces: `Optional<KubeCustomObject> getCustomObject(KubeCustomObjectRequest request)`, `void deleteCustomObject(KubeCustomObjectRequest request)`. Task 3 calls both.

- [ ] **Step 1: Write the failing tests**

Create `engine/src/test/java/org/qubership/integration/platform/engine/kubernetes/KubeOperatorTest.java`:

```java
package org.qubership.integration.platform.engine.kubernetes;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.apis.CoreV1Api;
import io.kubernetes.client.openapi.apis.CustomObjectsApi;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.errorhandling.KubeApiException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KubeOperatorTest {

    private static final String GROUP = "gateway.networking.k8s.io";
    private static final String VERSION = "v1";
    private static final String NAMESPACE = "qip";
    private static final String PLURAL = "httproutes";
    private static final String NAME = "engine-service-chain-public-routes";

    private CustomObjectsApi customObjectsApi;
    private KubeOperator kubeOperator;

    @BeforeEach
    void setUp() {
        CoreV1Api coreApi = mock(CoreV1Api.class);
        customObjectsApi = mock(CustomObjectsApi.class);
        kubeOperator = new KubeOperator(new ObjectMapper(), coreApi, customObjectsApi, NAMESPACE, false);
    }

    @Test
    void getCustomObjectReturnsParsedBodyOn200() throws ApiException {
        CustomObjectsApi.APIgetNamespacedCustomObjectRequest getRequest =
                mock(CustomObjectsApi.APIgetNamespacedCustomObjectRequest.class);
        when(customObjectsApi.getNamespacedCustomObject(GROUP, VERSION, NAMESPACE, PLURAL, NAME))
                .thenReturn(getRequest);

        Map<String, Object> rawObject = new LinkedHashMap<>();
        rawObject.put("apiVersion", GROUP + "/" + VERSION);
        rawObject.put("kind", "HTTPRoute");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("name", NAME);
        rawObject.put("metadata", metadata);
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("rules", List.of());
        rawObject.put("spec", spec);
        when(getRequest.execute()).thenReturn(rawObject);

        Optional<KubeCustomObject> result = kubeOperator.getCustomObject(request());

        assertTrue(result.isPresent());
        assertEquals("HTTPRoute", result.get().getKind());
        assertEquals(NAME, result.get().getMetadata().getName());
        assertEquals(List.of(), result.get().getSpec().get("rules"));
    }

    @Test
    void getCustomObjectReturnsEmptyOn404() throws ApiException {
        CustomObjectsApi.APIgetNamespacedCustomObjectRequest getRequest =
                mock(CustomObjectsApi.APIgetNamespacedCustomObjectRequest.class);
        when(customObjectsApi.getNamespacedCustomObject(GROUP, VERSION, NAMESPACE, PLURAL, NAME))
                .thenReturn(getRequest);
        when(getRequest.execute()).thenThrow(new ApiException(404, "Not Found"));

        Optional<KubeCustomObject> result = kubeOperator.getCustomObject(request());

        assertTrue(result.isEmpty());
    }

    @Test
    void getCustomObjectThrowsKubeApiExceptionOnOtherFailure() throws ApiException {
        CustomObjectsApi.APIgetNamespacedCustomObjectRequest getRequest =
                mock(CustomObjectsApi.APIgetNamespacedCustomObjectRequest.class);
        when(customObjectsApi.getNamespacedCustomObject(GROUP, VERSION, NAMESPACE, PLURAL, NAME))
                .thenReturn(getRequest);
        when(getRequest.execute()).thenThrow(new ApiException(500, "Internal Server Error"));

        assertThrows(KubeApiException.class, () -> kubeOperator.getCustomObject(request()));
    }

    @Test
    void deleteCustomObjectSucceedsOn200() throws ApiException {
        CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest deleteRequest =
                mock(CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest.class);
        when(customObjectsApi.deleteNamespacedCustomObject(GROUP, VERSION, NAMESPACE, PLURAL, NAME))
                .thenReturn(deleteRequest);
        when(deleteRequest.execute()).thenReturn(new Object());

        assertDoesNotThrow(() -> kubeOperator.deleteCustomObject(request()));

        verify(deleteRequest).execute();
    }

    @Test
    void deleteCustomObjectTreats404AsNoOp() throws ApiException {
        CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest deleteRequest =
                mock(CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest.class);
        when(customObjectsApi.deleteNamespacedCustomObject(GROUP, VERSION, NAMESPACE, PLURAL, NAME))
                .thenReturn(deleteRequest);
        when(deleteRequest.execute()).thenThrow(new ApiException(404, "Not Found"));

        assertDoesNotThrow(() -> kubeOperator.deleteCustomObject(request()));
    }

    @Test
    void deleteCustomObjectThrowsKubeApiExceptionOnOtherFailure() throws ApiException {
        CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest deleteRequest =
                mock(CustomObjectsApi.APIdeleteNamespacedCustomObjectRequest.class);
        when(customObjectsApi.deleteNamespacedCustomObject(GROUP, VERSION, NAMESPACE, PLURAL, NAME))
                .thenReturn(deleteRequest);
        when(deleteRequest.execute()).thenThrow(new ApiException(500, "Internal Server Error"));

        assertThrows(KubeApiException.class, () -> kubeOperator.deleteCustomObject(request()));
    }

    private KubeCustomObjectRequest request() {
        V1ObjectMeta metadata = new V1ObjectMeta();
        metadata.setName(NAME);

        return KubeCustomObjectRequest.builder()
                .group(GROUP)
                .version(VERSION)
                .resourceNamePlural(PLURAL)
                .body(KubeCustomObject.builder()
                        .metadata(metadata)
                        .build())
                .build();
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -pl engine -am test -Dtest=KubeOperatorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compile failure — `KubeOperator` has no `getCustomObject`/`deleteCustomObject` methods, and no constructor accepting `(ObjectMapper, CoreV1Api, CustomObjectsApi, String, Boolean)`.

- [ ] **Step 3: Add the constructor and methods to `KubeOperator`**

In `engine/src/main/java/org/qubership/integration/platform/engine/kubernetes/KubeOperator.java`, add the import after the existing `java.util.Objects;` import (line 35):

```java
import java.util.Objects;
import java.util.Optional;
```

Add a package-private constructor right after the existing public one that takes `(ObjectMapper, ApiClient, String, Boolean)` (after line 72, before `getAllSecretsWithLabel`):

```java
    KubeOperator(
            ObjectMapper objectMapper,
            CoreV1Api coreApi,
            CustomObjectsApi customObjectsApi,
            String namespace,
            Boolean devmode
    ) {
        this.objectMapper = objectMapper;
        this.coreApi = coreApi;
        this.customObjectsApi = customObjectsApi;
        this.namespace = namespace;
        this.devmode = devmode;
    }
```

Add the two new public methods right after `createOrReplaceCustomObject` (after line 140, before `isDevmode()`):

```java
    public Optional<KubeCustomObject> getCustomObject(KubeCustomObjectRequest request) {
        try {
            Object response = customObjectsApi.getNamespacedCustomObject(
                    request.getGroup(),
                    request.getVersion(),
                    getNotNullNamespace(),
                    request.getResourceNamePlural(),
                    getNotNullCustomResourceName(request)
            ).execute();

            return Optional.of(objectMapper.convertValue(response, KubeCustomObject.class));
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                return Optional.empty();
            }
            if (!isDevmode()) {
                log.error(DEFAULT_ERR_MESSAGE + e.getResponseBody());
            }
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
        } catch (Exception e) {
            if (!isDevmode()) {
                log.error(DEFAULT_ERR_MESSAGE + e.getMessage());
            }
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getMessage(), e);
        }
    }

    public void deleteCustomObject(KubeCustomObjectRequest request) {
        try {
            customObjectsApi.deleteNamespacedCustomObject(
                    request.getGroup(),
                    request.getVersion(),
                    getNotNullNamespace(),
                    request.getResourceNamePlural(),
                    getNotNullCustomResourceName(request)
            ).execute();
        } catch (ApiException e) {
            if (e.getCode() == 404) {
                return;
            }
            if (!isDevmode()) {
                log.error(DEFAULT_ERR_MESSAGE + e.getResponseBody());
            }
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getResponseBody(), e);
        } catch (Exception e) {
            if (!isDevmode()) {
                log.error(DEFAULT_ERR_MESSAGE + e.getMessage());
            }
            throw new KubeApiException(DEFAULT_ERR_MESSAGE + e.getMessage(), e);
        }
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -pl engine -am test -Dtest=KubeOperatorTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 6, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add engine/src/main/java/org/qubership/integration/platform/engine/kubernetes/KubeOperator.java engine/src/test/java/org/qubership/integration/platform/engine/kubernetes/KubeOperatorTest.java
git commit -m "feat: add KubeOperator.getCustomObject and deleteCustomObject"
```

---

### Task 3: `IstioRoutesRegistrationService`

**Files:**
- Modify: `engine/src/main/resources/application.yml:269-270`
- Modify: `engine/src/main/java/org/qubership/integration/platform/engine/cloudcore/controlplane/ControlPlaneDefaultService.java:42-43`
- Create: `engine/src/main/java/org/qubership/integration/platform/engine/cloudcore/controlplane/IstioRoutesRegistrationService.java`
- Test: `engine/src/test/java/org/qubership/integration/platform/engine/cloudcore/controlplane/IstioRoutesRegistrationServiceTest.java`

**Interfaces:**
- Consumes: `KubeOperator.getCustomObject`/`createOrReplaceCustomObject`/`deleteCustomObject` (Task 2), `HTTPRouteSpec`/`HTTPRouteRule`/`HTTPRouteMatch`/`HTTPPathMatch`/`HTTPRouteFilter`/`HTTPUrlRewriteFilter`/`HTTPPathModifier`/`HTTPBackendRef`/`HTTPRouteTimeouts`/`ParentReference` (Task 1), `ControlPlaneService` (existing interface, unchanged), `KubeCustomObject`/`KubeCustomObjectRequest` (existing, unchanged).
- Produces: `IstioRoutesRegistrationService implements ControlPlaneService` — a `@Component("controlPlaneService")` bean active when `qip.control-plane.enabled=true` (or unset) and `qip.control-plane.mesh-type=Istio`.

- [ ] **Step 1: Wire the `mesh-type` property and make `ControlPlaneDefaultService` mutually exclusive with it**

In `engine/src/main/resources/application.yml`, change (around line 269-270):

```yaml
  control-plane:
    enabled: false
```

to:

```yaml
  control-plane:
    enabled: false
    mesh-type: ${SERVICE_MESH_TYPE:Core}
```

In `engine/src/main/java/org/qubership/integration/platform/engine/cloudcore/controlplane/ControlPlaneDefaultService.java`, change (lines 42-43):

```java
@ConditionalOnProperty(value = "qip.control-plane.enabled", havingValue = "true", matchIfMissing = true)
public class ControlPlaneDefaultService implements ControlPlaneService {
```

to:

```java
@ConditionalOnProperty(value = "qip.control-plane.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(name = "qip.control-plane.mesh-type", havingValue = "Core", matchIfMissing = true)
public class ControlPlaneDefaultService implements ControlPlaneService {
```

This step has no dedicated test — it's config plus an annotation on an existing, already-tested-by-nothing class. It's verified indirectly by Step 6 below (`IstioRoutesRegistrationServiceTest` only exercises the new class directly, not Spring's conditional wiring; manually confirm bean selection by running the app with each property combination if you need to double-check it before merging).

- [ ] **Step 2: Write the failing tests for `IstioRoutesRegistrationService`**

Create `engine/src/test/java/org/qubership/integration/platform/engine/cloudcore/controlplane/IstioRoutesRegistrationServiceTest.java`:

```java
package org.qubership.integration.platform.engine.cloudcore.controlplane;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.qubership.integration.platform.engine.kubernetes.KubeCustomObject;
import org.qubership.integration.platform.engine.kubernetes.KubeCustomObjectRequest;
import org.qubership.integration.platform.engine.kubernetes.KubeOperator;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.engine.model.deployment.update.RouteType;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPBackendRef;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPPathMatch;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPRouteMatch;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPRouteRule;
import org.qubership.integration.platform.engine.model.gatewayapi.HTTPRouteSpec;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IstioRoutesRegistrationServiceTest {

    private static final String NAMESPACE = "qip";
    private static final String BASE_PATH = "/api/v1";
    private static final String CLOUD_SERVICE_NAME = "engine-service";

    private KubeOperator kubeOperator;
    private IstioRoutesRegistrationService service;

    @BeforeEach
    void setUp() {
        kubeOperator = mock(KubeOperator.class);
        service = new IstioRoutesRegistrationService(kubeOperator, new ObjectMapper(), NAMESPACE, BASE_PATH);
    }

    @Test
    void postPublicEngineRoutesCreatesCrWhenNoneExists() {
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.empty());
        DeploymentRouteUpdate route = route("/chain-a", RouteType.EXTERNAL_TRIGGER, 5000L);

        service.postPublicEngineRoutes(List.of(route), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator).createOrReplaceCustomObject(captor.capture());
        verify(kubeOperator, never()).deleteCustomObject(any());

        KubeCustomObjectRequest request = captor.getValue();
        assertEquals("gateway.networking.k8s.io", request.getGroup());
        assertEquals("v1", request.getVersion());
        assertEquals("httproutes", request.getResourceNamePlural());
        assertEquals(CLOUD_SERVICE_NAME + "-chain-public-routes", request.getBody().getMetadata().getName());
        assertEquals(NAMESPACE, request.getBody().getMetadata().getNamespace());

        HTTPRouteSpec spec = new ObjectMapper().convertValue(request.getBody().getSpec(), HTTPRouteSpec.class);
        assertEquals(1, spec.getParentRefs().size());
        assertEquals("public-gateway", spec.getParentRefs().get(0).getName());
        assertEquals(1, spec.getRules().size());
        assertEquals(BASE_PATH + "/chain-a", spec.getRules().get(0).getMatches().get(0).getPath().getValue());
        assertEquals(CLOUD_SERVICE_NAME, spec.getRules().get(0).getBackendRefs().get(0).getName());
        assertEquals("5000ms", spec.getRules().get(0).getTimeouts().getRequest());
    }

    @Test
    void postPublicEngineRoutesPreservesOtherChainsRules() {
        HTTPRouteRule otherChainRule = rule("/chain-b", "other-service");
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.of(existingCr(List.of(otherChainRule))));

        service.postPublicEngineRoutes(List.of(route("/chain-a", RouteType.EXTERNAL_TRIGGER, null)), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator).createOrReplaceCustomObject(captor.capture());

        HTTPRouteSpec spec = new ObjectMapper().convertValue(captor.getValue().getBody().getSpec(), HTTPRouteSpec.class);
        assertEquals(2, spec.getRules().size());
        List<String> paths = spec.getRules().stream()
                .map(r -> r.getMatches().get(0).getPath().getValue())
                .toList();
        assertTrue(paths.contains(BASE_PATH + "/chain-b"));
        assertTrue(paths.contains(BASE_PATH + "/chain-a"));
    }

    @Test
    void postPublicEngineRoutesReplacesOwnStaleRuleInsteadOfDuplicating() {
        HTTPRouteRule staleRule = rule("/chain-a", CLOUD_SERVICE_NAME);
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.of(existingCr(List.of(staleRule))));

        service.postPublicEngineRoutes(List.of(route("/chain-a", RouteType.EXTERNAL_TRIGGER, null)), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator).createOrReplaceCustomObject(captor.capture());

        HTTPRouteSpec spec = new ObjectMapper().convertValue(captor.getValue().getBody().getSpec(), HTTPRouteSpec.class);
        assertEquals(1, spec.getRules().size());
    }

    @Test
    void postPublicEngineRoutesWithEmptyListAndOtherChainsPresentDoesNothing() {
        service.postPublicEngineRoutes(List.of(), CLOUD_SERVICE_NAME);

        verify(kubeOperator, never()).getCustomObject(any());
        verify(kubeOperator, never()).createOrReplaceCustomObject(any());
        verify(kubeOperator, never()).deleteCustomObject(any());
    }

    @Test
    void postPrivateEngineRoutesTargetsPrivateGatewayAndCr() {
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.empty());

        service.postPrivateEngineRoutes(List.of(route("/chain-a", RouteType.PRIVATE_TRIGGER, null)), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator).createOrReplaceCustomObject(captor.capture());

        KubeCustomObjectRequest request = captor.getValue();
        assertEquals(CLOUD_SERVICE_NAME + "-chain-private-routes", request.getBody().getMetadata().getName());

        HTTPRouteSpec spec = new ObjectMapper().convertValue(request.getBody().getSpec(), HTTPRouteSpec.class);
        assertEquals("private-gateway", spec.getParentRefs().get(0).getName());
    }

    @Test
    void removeEngineRoutesDoesNothingWhenCrDoesNotExist() {
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> service.removeEngineRoutes(
                List.of(route("/chain-a", RouteType.EXTERNAL_TRIGGER, null)), CLOUD_SERVICE_NAME));

        verify(kubeOperator, never()).createOrReplaceCustomObject(any());
        verify(kubeOperator, never()).deleteCustomObject(any());
    }

    @Test
    void removeEngineRoutesDeletesCrWhenItBecomesEmpty() {
        HTTPRouteRule onlyRule = rule("/chain-a", CLOUD_SERVICE_NAME);
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.of(existingCr(List.of(onlyRule))));

        service.removeEngineRoutes(List.of(route("/chain-a", RouteType.EXTERNAL_TRIGGER, null)), CLOUD_SERVICE_NAME);

        verify(kubeOperator).deleteCustomObject(any());
        verify(kubeOperator, never()).createOrReplaceCustomObject(any());
    }

    @Test
    void removeEngineRoutesLeavesOtherChainsRulesInPlace() {
        HTTPRouteRule ownRule = rule("/chain-a", CLOUD_SERVICE_NAME);
        HTTPRouteRule otherChainRule = rule("/chain-b", "other-service");
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.of(existingCr(List.of(ownRule, otherChainRule))));

        service.removeEngineRoutes(List.of(route("/chain-a", RouteType.EXTERNAL_TRIGGER, null)), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator).createOrReplaceCustomObject(captor.capture());
        verify(kubeOperator, never()).deleteCustomObject(any());

        HTTPRouteSpec spec = new ObjectMapper().convertValue(captor.getValue().getBody().getSpec(), HTTPRouteSpec.class);
        assertEquals(1, spec.getRules().size());
        assertEquals(BASE_PATH + "/chain-b", spec.getRules().get(0).getMatches().get(0).getPath().getValue());
    }

    @Test
    void removeEngineRoutesSplitsByTierUsingGivenType() {
        when(kubeOperator.getCustomObject(any())).thenReturn(Optional.empty());
        DeploymentRouteUpdate publicRoute = route("/chain-a", RouteType.EXTERNAL_TRIGGER, null);
        DeploymentRouteUpdate privateRoute = route("/chain-b", RouteType.PRIVATE_TRIGGER, null);

        service.removeEngineRoutes(List.of(publicRoute, privateRoute), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator, times(2)).getCustomObject(captor.capture());
        List<String> requestedNames = captor.getAllValues().stream()
                .map(r -> r.getBody().getMetadata().getName())
                .toList();
        assertTrue(requestedNames.contains(CLOUD_SERVICE_NAME + "-chain-public-routes"));
        assertTrue(requestedNames.contains(CLOUD_SERVICE_NAME + "-chain-private-routes"));
    }

    @Test
    void postEgressGatewayRoutesThrowsUnsupportedOperationException() {
        DeploymentRouteUpdate route = route("http://external/api", RouteType.EXTERNAL_SERVICE, null);

        assertThrows(UnsupportedOperationException.class, () -> service.postEgressGatewayRoutes(route));
    }

    private DeploymentRouteUpdate route(String path, RouteType type, Long connectTimeout) {
        DeploymentRouteUpdate.DeploymentRouteUpdateBuilder builder = DeploymentRouteUpdate.builder()
                .path(path)
                .type(type);
        if (connectTimeout != null) {
            builder.connectTimeout(connectTimeout);
        }
        return builder.build();
    }

    private HTTPRouteRule rule(String path, String backendName) {
        return HTTPRouteRule.builder()
                .matches(List.of(HTTPRouteMatch.builder()
                        .path(HTTPPathMatch.builder()
                                .type("PathPrefix")
                                .value(BASE_PATH + path)
                                .build())
                        .build()))
                .filters(List.of())
                .backendRefs(List.of(HTTPBackendRef.builder()
                        .group("")
                        .kind("Service")
                        .name(backendName)
                        .port(8080)
                        .weight(1)
                        .build()))
                .build();
    }

    @SuppressWarnings("unchecked")
    private KubeCustomObject existingCr(List<HTTPRouteRule> rules) {
        HTTPRouteSpec spec = HTTPRouteSpec.builder()
                .parentRefs(List.of())
                .rules(rules)
                .build();
        Map<String, Object> specMap = new ObjectMapper().convertValue(spec, Map.class);

        V1ObjectMeta metadata = new V1ObjectMeta();
        metadata.setName(CLOUD_SERVICE_NAME + "-chain-public-routes");
        metadata.setNamespace(NAMESPACE);

        return KubeCustomObject.builder()
                .apiVersion("gateway.networking.k8s.io/v1")
                .kind("HTTPRoute")
                .metadata(metadata)
                .spec(specMap)
                .build();
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `mvn -pl engine -am test -Dtest=IstioRoutesRegistrationServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compile failure — `IstioRoutesRegistrationService` doesn't exist yet.

- [ ] **Step 4: Implement `IstioRoutesRegistrationService`**

Create `engine/src/main/java/org/qubership/integration/platform/engine/cloudcore/controlplane/IstioRoutesRegistrationService.java`:

```java
package org.qubership.integration.platform.engine.cloudcore.controlplane;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneException;
import org.qubership.integration.platform.engine.controlplane.ControlPlaneService;
import org.qubership.integration.platform.engine.errorhandling.KubeApiException;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.engine.configuration.camel.CamelServletConfiguration.CAMEL_ROUTES_PREFIX;

@Component("controlPlaneService")
@ConditionalOnProperty(value = "qip.control-plane.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(name = "qip.control-plane.mesh-type", havingValue = "Istio")
public class IstioRoutesRegistrationService implements ControlPlaneService {

    private static final String GATEWAY_API_GROUP = "gateway.networking.k8s.io";
    private static final String GATEWAY_API_VERSION = "v1";
    private static final String HTTP_ROUTES_PLURAL = "httproutes";
    private static final String PUBLIC_GATEWAY_NAME = "public-gateway";
    private static final String PRIVATE_GATEWAY_NAME = "private-gateway";
    private static final int BACKEND_PORT = 8080;

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
        mergeTierRoutes(tierRequest(endpoint, "public"), deploymentRoutes, PUBLIC_GATEWAY_NAME, endpoint, true);
    }

    @Override
    public synchronized void postPrivateEngineRoutes(List<DeploymentRouteUpdate> deploymentRoutes, String endpoint)
            throws ControlPlaneException {
        mergeTierRoutes(tierRequest(endpoint, "private"), deploymentRoutes, PRIVATE_GATEWAY_NAME, endpoint, true);
    }

    @Override
    public synchronized void removeEngineRoutes(List<DeploymentRouteUpdate> deploymentRoutes, String deploymentName)
            throws ControlPlaneException {
        List<DeploymentRouteUpdate> publicRoutes = deploymentRoutes.stream()
                .filter(route -> RouteType.isPublicTriggerRoute(route.getType()))
                .toList();
        if (!publicRoutes.isEmpty()) {
            mergeTierRoutes(tierRequest(deploymentName, "public"), publicRoutes, PUBLIC_GATEWAY_NAME, deploymentName, false);
        }

        List<DeploymentRouteUpdate> privateRoutes = deploymentRoutes.stream()
                .filter(route -> RouteType.isPrivateTriggerRoute(route.getType()))
                .toList();
        if (!privateRoutes.isEmpty()) {
            mergeTierRoutes(tierRequest(deploymentName, "private"), privateRoutes, PRIVATE_GATEWAY_NAME, deploymentName, false);
        }
    }

    @Override
    public void postEgressGatewayRoutes(DeploymentRouteUpdate route) {
        throw new UnsupportedOperationException(
                "Egress gateway route registration is not implemented for Istio Ambient Mesh yet; "
                        + "see docs/superpowers/specs/2026-07-31-istio-routes-registration-service-design.md");
    }

    private void mergeTierRoutes(
            KubeCustomObjectRequest tierRequest,
            List<DeploymentRouteUpdate> givenRoutes,
            String gatewayName,
            String backendName,
            boolean buildRules
    ) {
        if (givenRoutes.isEmpty()) {
            return;
        }
        try {
            Optional<KubeCustomObject> current = kubeOperator.getCustomObject(tierRequest);
            List<HTTPRouteRule> existingRules = current
                    .map(obj -> objectMapper.convertValue(obj.getSpec(), HTTPRouteSpec.class))
                    .map(HTTPRouteSpec::getRules)
                    .filter(Objects::nonNull)
                    .orElse(List.of());

            Set<String> touchedPaths = givenRoutes.stream()
                    .map(route -> baseRoutePrefix + route.getPath())
                    .collect(Collectors.toSet());

            List<HTTPRouteRule> preservedRules = existingRules.stream()
                    .filter(rule -> !touchedPaths.contains(matchPath(rule)))
                    .toList();

            List<HTTPRouteRule> newRules = buildRules
                    ? givenRoutes.stream().map(route -> buildRule(route, backendName)).toList()
                    : List.of();

            List<HTTPRouteRule> mergedRules = new ArrayList<>(preservedRules);
            mergedRules.addAll(newRules);

            if (mergedRules.isEmpty()) {
                kubeOperator.deleteCustomObject(tierRequest);
                return;
            }

            HTTPRouteSpec spec = HTTPRouteSpec.builder()
                    .parentRefs(parentRefs(gatewayName))
                    .rules(mergedRules)
                    .build();
            tierRequest.getBody().setSpec(objectMapper.convertValue(spec, new TypeReference<Map<String, Object>>() {}));
            kubeOperator.createOrReplaceCustomObject(tierRequest);
        } catch (KubeApiException e) {
            throw new ControlPlaneException("Failed to update Istio HTTPRoute for control plane routes", e);
        }
    }

    private String matchPath(HTTPRouteRule rule) {
        return rule.getMatches().get(0).getPath().getValue();
    }

    private HTTPRouteRule buildRule(DeploymentRouteUpdate route, String backendName) {
        String path = baseRoutePrefix + route.getPath();

        HTTPRouteTimeouts timeouts = null;
        if (route.getConnectTimeout() != null && route.getConnectTimeout() > 0) {
            timeouts = HTTPRouteTimeouts.builder()
                    .request(route.getConnectTimeout() + "ms")
                    .build();
        }

        return HTTPRouteRule.builder()
                .matches(List.of(HTTPRouteMatch.builder()
                        .path(HTTPPathMatch.builder().type("PathPrefix").value(path).build())
                        .build()))
                .filters(List.of(HTTPRouteFilter.builder()
                        .type("URLRewrite")
                        .urlRewrite(HTTPUrlRewriteFilter.builder()
                                .path(HTTPPathModifier.builder()
                                        .type("ReplacePrefixMatch")
                                        .replacePrefixMatch(CAMEL_ROUTES_PREFIX + route.getPath())
                                        .build())
                                .build())
                        .build()))
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
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `mvn -pl engine -am test -Dtest=IstioRoutesRegistrationServiceTest -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `Tests run: 10, Failures: 0, Errors: 0`

- [ ] **Step 6: Run the full engine test suite and checkstyle to confirm nothing else broke**

Run: `mvn -pl engine -am test`
Expected: `BUILD SUCCESS`, 0 Checkstyle violations, all tests pass (including the pre-existing suite, `HTTPRouteSpecTest`, and `KubeOperatorTest` from Tasks 1-2).

- [ ] **Step 7: Commit**

```bash
git add engine/src/main/resources/application.yml engine/src/main/java/org/qubership/integration/platform/engine/cloudcore/controlplane/ControlPlaneDefaultService.java engine/src/main/java/org/qubership/integration/platform/engine/cloudcore/controlplane/IstioRoutesRegistrationService.java engine/src/test/java/org/qubership/integration/platform/engine/cloudcore/controlplane/IstioRoutesRegistrationServiceTest.java
git commit -m "feat: add IstioRoutesRegistrationService"
```
