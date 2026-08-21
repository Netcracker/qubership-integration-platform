# Istio Review Follow-ups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the four findings from the whole-branch Istio migration review: duplicate `ServiceEntry` port names that Istio rejects outright, unescaped regex literals in placeholder path matches, snapshot cleanup that strips routes another snapshot still serves, and a `qip-dev` chart whose ports and Service names do not match what the application generates.

**Architecture:** Findings 1 and 2 are localized fixes in the `EgressTarget` / `GatewayPathMatch` helper pair, each of which exists as an identical copy in `engine` and `integration-build-pipeline` (no compile-time relationship between the two modules); both copies must change together or the live registration path and the build-time generation path will write conflicting resources for the same object. Finding 3 changes `MicroDomainService.deleteChainSnapshotHttpRoutes` to compute the paths the domain's remaining snapshots still own and strip only the difference, mirroring `ChainRouteRegistry.getUnsharedRoutes` on the engine side. Finding 4 aligns the `qip-dev` gateway chart with the ports and Service names the generators emit, and makes the egress URL overridable so a site whose gateway listens elsewhere does not need a rebuild.

**Tech Stack:** Java 21, JUnit 5, Mockito (`mock(Class)` factory style), Jackson `ObjectNode` / `YAMLMapper`, Helm 3, Gateway API v1, Istio `networking.istio.io/v1`.

## Global constraints

- `engine/.../util/paths/GatewayPathMatch.java` and `integration-build-pipeline/.../util/paths/GatewayPathMatch.java` are identical apart from the `package` line. The two `EgressTarget.java` copies differ only in that plus two Javadoc words (`the build pipeline's` versus `this module's` in `hostResourceName`). Any change to one copy must be applied verbatim to the other, including Javadoc. Both modules write the same cluster objects, so a divergence silently produces two different resource shapes for one host.
- All eight helper and test files are stored in git with LF endings. `core.autocrlf` checks some of them out with CRLF on Windows, so a working-tree `file` probe disagrees with the blob. Edit in place and let git normalize; never rewrite a whole file to change its endings, and pass `--strip-trailing-cr` when diffing the pair so the checkout style cannot mask a real difference.
- Port names must be valid DNS-1123 labels. Istio's `ValidateServiceEntry` runs `ValidatePortName` per port and rejects the whole resource when two ports share a name.
- Path regexes are evaluated by Envoy, which uses RE2. `Pattern.quote` is not usable for escaping: it emits `\Q…\E`, which RE2 does not support.
- `qip.gateway.egress.url` is shared by the `Core` and `Istio` mesh types. Its default stays `egress-gateway:8080`; the `qip-dev` chart moves to match it, not the other way round.
- Do not change the public and private Gateway listener ports. Nothing in the generated resources references them: chain and engine HTTPRoutes attach to those two by `parentRef` (`kind: Gateway`), which carries no port.
- Every commit message must end with `@cf_skip`. The repository's CyberFerret pre-commit hook rejects all commits over a pre-existing email address in `package-lock.json` that has nothing to do with this work, and `@cf_skip` is the hook's own documented escape hatch — the same one this branch already uses (`e5dc64386`, `8b4c891a3`). Never reach for `--no-verify`.
- Keeping the duplicated helpers in sync is a standing decision, not an oversight to fix here. Extracting them into a shared module means adding a Maven module both `engine` and `integration-build-pipeline` depend on, which is outside the scope of these four review findings.

---

### Task 1: Give every port on a host a unique `ServiceEntry` port name

**Files:**
- Modify: `engine/src/main/java/org/qubership/integration/platform/engine/util/paths/EgressTarget.java`
- Modify: `integration-build-pipeline/src/main/java/org/qubership/integration/platform/camelk/util/paths/EgressTarget.java`
- Modify: `engine/src/main/java/org/qubership/integration/platform/engine/cloudcore/controlplane/IstioRoutesRegistrationService.java:342`
- Modify: `integration-build-pipeline/src/main/java/org/qubership/integration/platform/camelk/builders/chain/EgressRouteResourceBuilder.java:264`
- Test: `engine/src/test/java/org/qubership/integration/platform/engine/util/paths/EgressTargetTest.java`
- Test: `integration-build-pipeline/src/test/java/org/qubership/integration/platform/camelk/util/paths/EgressTargetTest.java`
- Test: `engine/src/test/java/org/qubership/integration/platform/engine/cloudcore/controlplane/IstioRoutesRegistrationServiceTest.java`
- Test: `integration-build-pipeline/src/test/java/org/qubership/integration/platform/camelk/builders/chain/EgressRouteResourceBuilderTest.java`

**Interfaces:**
- Produces: `EgressTarget.portName()` returning `String` — `"https-443"`, `"http-8080"`, and so on. Both copies get the identical method.
- Consumes: nothing from other tasks.

Both generators name a `ServiceEntry` port after the scheme alone. A `ServiceEntry` is keyed on host, so one resource accumulates every port any route targets on that host: `api.example.com:443` and `api.example.com:9443` both land in the same object, both named `https`. Istio's admission validation rejects the resource, and every route through that host stops working — including the one that was already there before the second port arrived.

- [ ] **Step 1: Write the failing test**

Add this test to **both** `EgressTargetTest` copies. The only difference between the two files is the `package` line, so paste the same method into each:

```java
    @Test
    void portNameCombinesSchemeAndPortSoOneHostsPortsStayUnique() {
        assertEquals("https-443", EgressTarget.parse("https://api.example.com/v2").portName());
        assertEquals("https-9443", EgressTarget.parse("https://api.example.com:9443/v2").portName());
        assertEquals("http-80", EgressTarget.parse("http://api.example.com/v2").portName());
        assertEquals("http-8080", EgressTarget.parse("http://api.example.com:8080/v2").portName());
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -pl engine test -Dtest=EgressTargetTest` and `mvn -pl integration-build-pipeline test -Dtest=EgressTargetTest`
Expected: FAIL — compilation error, `cannot find symbol: method portName()`.

- [ ] **Step 3: Add `portName()` to both `EgressTarget` copies**

Insert this method directly after `isHttps()` in both files:

```java
    /**
     * The {@code ServiceEntry} port name for this target. A {@code ServiceEntry} is named after its
     * host alone, so one resource collects every port any route targets on that host -- and Istio's
     * own validation rejects a {@code ServiceEntry} whose ports share a name. Naming a port after
     * the scheme alone therefore collides as soon as one host is reached on two ports (443 and 9443
     * would both be "https"). Appending the port number keeps Istio's conventional
     * {@code <protocol>-<suffix>} shape, stays a valid DNS-1123 label, and is unique by
     * construction, since ports are merged by number.
     */
    public String portName() {
        return (isHttps() ? "https" : "http") + "-" + port;
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -pl engine test -Dtest=EgressTargetTest` and `mvn -pl integration-build-pipeline test -Dtest=EgressTargetTest`
Expected: PASS.

- [ ] **Step 5: Write the failing generator tests**

In `IstioRoutesRegistrationServiceTest`, add:

```java
    @Test
    @SuppressWarnings("unchecked")
    void postEgressGatewayRoutesGivesEveryPortOnAHostItsOwnName() {
        when(kubeOperator.getCustomObject(argThat(r -> r != null && "serviceentries".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.of(existingServiceEntry(port(443, "https-443", "HTTPS"))));
        when(kubeOperator.getCustomObject(argThat(r -> r != null && !"serviceentries".equals(r.getResourceNamePlural()))))
                .thenReturn(Optional.empty());

        DeploymentRouteUpdate route =
                egressRoute("https://api.example.com:9443/v2", "/system/service-b", RouteType.EXTERNAL_SERVICE);
        service.postEgressGatewayRoutes(List.of(route), CLOUD_SERVICE_NAME);

        ArgumentCaptor<KubeCustomObjectRequest> captor = ArgumentCaptor.forClass(KubeCustomObjectRequest.class);
        verify(kubeOperator, atLeastOnce()).createOrReplaceCustomObject(captor.capture());
        KubeCustomObjectRequest serviceEntryRequest = captor.getAllValues().stream()
                .filter(r -> "serviceentries".equals(r.getResourceNamePlural())).findFirst().orElseThrow();
        List<Map<String, Object>> ports = (List<Map<String, Object>>) serviceEntryRequest.getBody().getSpec().get("ports");
        List<String> names = ports.stream().map(p -> (String) p.get("name")).sorted().toList();
        assertEquals(List.of("https-443", "https-9443"), names);
    }
```

In `EgressRouteResourceBuilderTest`, add:

```java
    @Test
    void buildGivesEveryPortOnAHostItsOwnNameSoIstioAcceptsTheServiceEntry() throws Exception {
        when(routesGetterService.getRoutes(any(), any())).thenReturn(List.of(
                Route.builder().path("https://api.example.com:9443/v2").gatewayPrefix("/system/elem-b")
                        .type(RouteType.EXTERNAL_SERVICE).build()));

        ResourceBuildContext<List<Snapshot>> context = contextWithSnapshot("snap-1");
        String hostResourceName = EgressTarget.parse("https://api.example.com").hostResourceName();
        context.getBuildCache().put(
                EgressRouteResourceBuilder.serviceEntryCacheKey(hostResourceName),
                existingServiceEntrySpec(port(443, "https-443", "HTTPS")));

        String result = builder.build(context);

        assertTrue(result.contains("name: https-443"));
        assertTrue(result.contains("name: https-9443"));
    }
```

Leave the four existing merge tests alone. They seed `port(8443, "https", "HTTPS")` and `port(443, "https", "HTTPS")` and assert on port *numbers*, so they keep passing — and their legacy port names are exactly what a cluster written by the current code holds, which is worth keeping under test.

- [ ] **Step 6: Run the generator tests to verify they fail**

Run: `mvn -pl engine test -Dtest=IstioRoutesRegistrationServiceTest` and `mvn -pl integration-build-pipeline test -Dtest=EgressRouteResourceBuilderTest`
Expected: FAIL — both new tests see `https` where they expect `https-9443`.

- [ ] **Step 7: Use `portName()` at both call sites**

`IstioRoutesRegistrationService.java:342`, inside `upsertServiceEntry`:

```java
        newPort.put("name", target.portName());
```

`EgressRouteResourceBuilder.java:264`, inside `appendServiceEntry`:

```java
        newPort.put("name", target.portName());
```

Nothing else in either method changes.

- [ ] **Step 8: Run the generator tests to verify they pass**

Run: `mvn -pl engine test -Dtest=IstioRoutesRegistrationServiceTest` and `mvn -pl integration-build-pipeline test -Dtest=EgressRouteResourceBuilderTest`
Expected: PASS, including the four pre-existing merge tests.

- [ ] **Step 9: Commit**

```bash
git add engine/src/main/java/org/qubership/integration/platform/engine/util/paths/EgressTarget.java \
        engine/src/main/java/org/qubership/integration/platform/engine/cloudcore/controlplane/IstioRoutesRegistrationService.java \
        engine/src/test/java/org/qubership/integration/platform/engine/util/paths/EgressTargetTest.java \
        engine/src/test/java/org/qubership/integration/platform/engine/cloudcore/controlplane/IstioRoutesRegistrationServiceTest.java \
        integration-build-pipeline/src/main/java/org/qubership/integration/platform/camelk/util/paths/EgressTarget.java \
        integration-build-pipeline/src/main/java/org/qubership/integration/platform/camelk/builders/chain/EgressRouteResourceBuilder.java \
        integration-build-pipeline/src/test/java/org/qubership/integration/platform/camelk/util/paths/EgressTargetTest.java \
        integration-build-pipeline/src/test/java/org/qubership/integration/platform/camelk/builders/chain/EgressRouteResourceBuilderTest.java
git commit -m "fix: name ServiceEntry ports after scheme and number so ports stay unique @cf_skip"
```

---

### Task 2: Escape literal path segments before substituting placeholders

**Files:**
- Modify: `engine/src/main/java/org/qubership/integration/platform/engine/util/paths/GatewayPathMatch.java`
- Modify: `integration-build-pipeline/src/main/java/org/qubership/integration/platform/camelk/util/paths/GatewayPathMatch.java`
- Test: `engine/src/test/java/org/qubership/integration/platform/engine/util/paths/GatewayPathMatchTest.java`
- Test: `integration-build-pipeline/src/test/java/org/qubership/integration/platform/camelk/util/paths/GatewayPathMatchTest.java`

**Interfaces:**
- `forPath(String)` keeps its signature and its `PathPrefix` behavior for placeholder-free paths. Only the `RegularExpression` value changes, and only for paths whose literal part contains a regex metacharacter.
- Produces: no new public API. `quoteLiteral` is private.

`forPath` replaces each `{param}` with `[^/]+` and leaves everything around it verbatim, so a metacharacter in the literal part stays live. `/files/{name}.json` becomes `/files/[^/]+.json`, where `.` matches any character — the rule then also serves `/files/report-json`, an address no chain claims.

- [ ] **Step 1: Write the failing test**

Add these three tests to **both** `GatewayPathMatchTest` copies:

```java
    @Test
    void literalDotIsEscapedSoItMatchesOnlyADot() {
        GatewayPathMatch match = GatewayPathMatch.forPath("/qip-routes/files/{name}.json");

        assertEquals("RegularExpression", match.getType());
        assertEquals("/qip-routes/files/[^/]+\\.json/?", match.getValue());
    }

    @Test
    void escapedLiteralsMatchOnlyTheLiteralText() {
        GatewayPathMatch match = GatewayPathMatch.forPath("/qip-routes/files/{name}.json");
        Pattern pattern = Pattern.compile(match.getValue());

        assertTrue(pattern.matcher("/qip-routes/files/report.json").matches());
        assertFalse(pattern.matcher("/qip-routes/files/report-json").matches());
    }

    @Test
    void everyRegexMetacharacterInALiteralIsEscaped() {
        GatewayPathMatch match = GatewayPathMatch.forPath("/a+b(c)/{id}/d[e]f*");

        assertEquals("RegularExpression", match.getType());
        assertEquals("/a\\+b\\(c\\)/[^/]+/d\\[e\\]f\\*/?", match.getValue());
    }
```

The other eight tests in each file stay unchanged: none of their paths contain a metacharacter outside a placeholder, so their expected values are unaffected.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -pl engine test -Dtest=GatewayPathMatchTest` and `mvn -pl integration-build-pipeline test -Dtest=GatewayPathMatchTest`
Expected: FAIL — the produced value is `/qip-routes/files/[^/]+.json/?`, with the dot unescaped.

- [ ] **Step 3: Escape the literal segments in both copies**

Replace the imports, constants, and `forPath` in both files. Everything below `of(String, String)` stays as it is:

```java
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
```

```java
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[^{}/]+\\}");
    private static final String PLACEHOLDER_REGEX = "[^/]+";
    private static final String REGEX_METACHARACTERS = "\\.^$|?*+()[]{}";
    private static final String PATH_PREFIX = "PathPrefix";
    private static final String REGULAR_EXPRESSION = "RegularExpression";
```

```java
    public static GatewayPathMatch forPath(String path) {
        Matcher matcher = PLACEHOLDER.matcher(path);
        if (!matcher.find()) {
            return new GatewayPathMatch(PATH_PREFIX, path);
        }
        StringBuilder regex = new StringBuilder();
        int literalStart = 0;
        do {
            regex.append(quoteLiteral(path.substring(literalStart, matcher.start())));
            regex.append(PLACEHOLDER_REGEX);
            literalStart = matcher.end();
        } while (matcher.find());
        regex.append(quoteLiteral(path.substring(literalStart)));
        if (!path.endsWith("/")) {
            regex.append("/?");
        }
        return new GatewayPathMatch(REGULAR_EXPRESSION, regex.toString());
    }

    /**
     * Escapes every regex metacharacter in a literal (non-placeholder) run of the path, so a path
     * such as {@code /files/{name}.json} matches a literal dot instead of any character.
     * {@link Pattern#quote} is not usable here: it wraps its input in {@code \Q...\E}, which RE2 --
     * the engine Envoy evaluates these matches with -- does not support.
     */
    private static String quoteLiteral(String literal) {
        StringBuilder quoted = new StringBuilder(literal.length());
        for (int i = 0; i < literal.length(); i++) {
            char c = literal.charAt(i);
            if (REGEX_METACHARACTERS.indexOf(c) >= 0) {
                quoted.append('\\');
            }
            quoted.append(c);
        }
        return quoted.toString();
    }
```

Update the class Javadoc's second sentence in both copies, so it describes what the code now does:

```java
 * for a path containing one or more {@code {param}} placeholders (each placeholder is
 * replaced with {@code [^/]+} and every literal segment around it is regex-escaped; no
 * anchors are added, since Istio/Envoy's regex path matching already requires a full match).
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `mvn -pl engine test -Dtest=GatewayPathMatchTest` and `mvn -pl integration-build-pipeline test -Dtest=GatewayPathMatchTest`
Expected: PASS, all eleven tests in each file.

- [ ] **Step 5: Run the full suites that consume `GatewayPathMatch`**

`GatewayPathMatch` doubles as the identity key for matching an existing cluster rule to a route, so a changed value changes cleanup behavior too.

Run: `mvn -pl integration-build-pipeline test -Dtest='HttpRouteResourceBuilderTest,EgressRouteResourceBuilderTest'` and `mvn -pl runtime-catalog test -Dtest=MicroDomainServiceHttpRouteTest`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add engine/src/main/java/org/qubership/integration/platform/engine/util/paths/GatewayPathMatch.java \
        engine/src/test/java/org/qubership/integration/platform/engine/util/paths/GatewayPathMatchTest.java \
        integration-build-pipeline/src/main/java/org/qubership/integration/platform/camelk/util/paths/GatewayPathMatch.java \
        integration-build-pipeline/src/test/java/org/qubership/integration/platform/camelk/util/paths/GatewayPathMatchTest.java
git commit -m "fix: escape literal path segments in placeholder route regexes @cf_skip"
```

**Known consequence, no action needed:** a rule already in a cluster for a path that has both a placeholder and a metacharacter was written with the unescaped value, which no longer equals the newly computed match. On the next deploy that rule is preserved rather than replaced, and a second rule appears alongside it. This is limited to that narrow path shape and to clusters running a pre-fix build of an unreleased branch, and the stale rule is a strict superset of the new one, so nothing breaks while it lingers. Delete such rules by hand if a pre-fix cluster is being kept.

---

### Task 3: Strip only the paths no remaining snapshot still owns

**Files:**
- Modify: `runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/cr/MicroDomainService.java:234-274` and `:383-405`
- Test: `runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/cr/MicroDomainServiceHttpRouteTest.java`

**Interfaces:**
- Changed: `void deleteChainSnapshotHttpRoutes(String name, String snapshotId, Set<String> remainingSnapshotIds)` — package-private, gains a third parameter. All eleven existing call sites in `MicroDomainServiceHttpRouteTest` pass `Set.of()`.
- Produces (private): `Set<String> remainingSnapshotIds(IntegrationResources resources, String removedSnapshotId)`, `List<Route> snapshotRoutes(Collection<String> snapshotIds, String description)`, `Set<GatewayPathMatch> unsharedPaths(Set<GatewayPathMatch> ownPaths, Set<GatewayPathMatch> retainedPaths)`.
- Consumes: `SnapshotRepository.findAllByIdIn(Collection<String>)`, already used by this class.

The public, private, and egress HTTPRoute tiers are one CR per micro-domain, shared by every snapshot that domain hosts. `deleteChainSnapshotHttpRoutes` computes only the removed snapshot's own paths and deletes every rule matching them, without asking whether anything else still serves those paths. Two cases make that a live outage:

- A chain redeployed under a new snapshot ID. The new snapshot's deploy writes its rules first; removing the superseded snapshot then deletes them, and the chain that is actually running becomes unreachable.
- Two chains in the same domain calling the same external system. `EgressServiceRouteFormatter` derives the egress `gatewayPrefix` from the element and address, so both resolve to the same prefix. Undeploying either one takes the other's egress route with it.

The engine side already solves the same problem in `ChainRouteRegistry.getUnsharedRoutes`, which subtracts the paths other deployments claim before removing anything. This task gives the runtime-catalog side the same subtraction.

- [ ] **Step 1: Write the failing tests**

Add `import java.util.Set;` to `MicroDomainServiceHttpRouteTest` — the file imports `Collections`, `LinkedHashMap`, `List`, `Map`, and `Optional`, but not `Set`. `never` and `mock` are already imported. Then add these two tests:

```java
    // A path both the removed and a remaining snapshot claim must survive: the shared tier CR is
    // the running chain's only route to the gateway.
    @Test
    void deleteChainSnapshotKeepsAPathARemainingSnapshotStillOwns() {
        when(snapshotRepository.findAllByIdIn(List.of("snapshot-1")))
                .thenReturn(List.of(mock(
                        org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot.class)));
        when(snapshotRepository.findAllByIdIn(Set.of("snapshot-2")))
                .thenReturn(List.of(mock(
                        org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot.class)));
        when(routesGetterService.getRoutes(any(), any())).thenReturn(List.of(
                Route.builder().path("/a").type(RouteType.EXTERNAL_TRIGGER).build()));

        microDomainService.deleteChainSnapshotHttpRoutes(DOMAIN, "snapshot-1", Set.of("snapshot-2"));

        verify(kubeOperator, never()).createOrUpdateResource(any());
        verify(kubeOperator, never()).deleteCustomObject(GROUP, VERSION, PLURAL, PUBLIC_ROUTE_NAME);
    }

    // Only the shared path is spared. The removed snapshot's exclusive path still goes.
    // getRoutes is stubbed by consecutive return: deleteChainSnapshotHttpRoutes resolves the
    // removed snapshot's routes first, then the remaining snapshots'.
    @Test
    void deleteChainSnapshotStripsOnlyThePathsNoRemainingSnapshotOwns() {
        when(snapshotRepository.findAllByIdIn(List.of("snapshot-1")))
                .thenReturn(List.of(mock(
                        org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot.class)));
        when(snapshotRepository.findAllByIdIn(Set.of("snapshot-2")))
                .thenReturn(List.of(mock(
                        org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot.class)));
        when(routesGetterService.getRoutes(any(), any()))
                .thenReturn(List.of(
                        Route.builder().path("/a").type(RouteType.EXTERNAL_TRIGGER).build(),
                        Route.builder().path("/shared").type(RouteType.EXTERNAL_TRIGGER).build()))
                .thenReturn(List.of(
                        Route.builder().path("/shared").type(RouteType.EXTERNAL_TRIGGER).build()));
        when(kubeOperator.getCustomObject(GROUP, VERSION, PLURAL, PUBLIC_ROUTE_NAME))
                .thenReturn(Optional.of(httpRoute(PUBLIC_ROUTE_NAME,
                        List.of(rule("/qip-routes/a"), rule("/qip-routes/shared")))));

        microDomainService.deleteChainSnapshotHttpRoutes(DOMAIN, "snapshot-1", Set.of("snapshot-2"));

        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(kubeOperator).createOrUpdateResource(captor.capture());
        KubeCustomObject updated = (KubeCustomObject) captor.getValue();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> remainingRules = (List<Map<String, Object>>) updated.getSpec().get("rules");
        assertEquals(1, remainingRules.size());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> matches = (List<Map<String, Object>>) remainingRules.get(0).get("matches");
        @SuppressWarnings("unchecked")
        Map<String, Object> pathMatch = (Map<String, Object>) matches.get(0).get("path");
        assertEquals("/qip-routes/shared", pathMatch.get("value"));
    }
```

Then update all eleven existing `microDomainService.deleteChainSnapshotHttpRoutes(DOMAIN, "snapshot-1")` calls in this file to `microDomainService.deleteChainSnapshotHttpRoutes(DOMAIN, "snapshot-1", Set.of())`. Their assertions do not change: with no remaining snapshots, nothing is retained and the strip set is the snapshot's own paths, exactly as today.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `mvn -pl runtime-catalog test -Dtest=MicroDomainServiceHttpRouteTest`
Expected: FAIL — compilation error, `deleteChainSnapshotHttpRoutes` takes two arguments.

- [ ] **Step 3: Add the imports `MicroDomainService` needs**

`java.util.*` is already imported, which covers `Set`, `HashSet`, `Collection`, and `Objects`. Add one import:

```java
import org.qubership.integration.platform.camelk.integrations.configuration.SourceDefinition;
```

- [ ] **Step 4: Compute the remaining snapshot IDs in `deleteChainSnapshot`**

In `deleteChainSnapshot` (line 234), insert one line immediately after `CamelKIntegration integration = resources.integration();`:

```java
            Set<String> remainingSnapshotIds = remainingSnapshotIds(resources, snapshotId);
```

It has to be read here, before the integrations-configuration ConfigMap is rewritten further down the same method, so the result does not depend on whether that rewrite has already dropped this snapshot from the list.

Then change the last line of the lambda (line 272) from:

```java
            deleteChainSnapshotHttpRoutes(name, snapshotId);
```

to:

```java
            deleteChainSnapshotHttpRoutes(name, snapshotId, remainingSnapshotIds);
```

- [ ] **Step 5: Add the `remainingSnapshotIds` helper**

Insert this method directly above `deleteChainSnapshotHttpRoutes`:

```java
    /**
     * The snapshot IDs this micro-domain still hosts once {@code removedSnapshotId} is gone. Read
     * from the integrations-configuration ConfigMap's source list -- the same list
     * {@link #deleteChainSnapshot} filters this snapshot out of, and the one in-cluster record that
     * stores raw, unsanitized snapshot IDs (the source ConfigMaps' {@code SNAPSHOT_ID_LABEL} holds
     * a {@code K8sNameValidator}-sanitized form), so it is the set that can be handed straight to
     * {@code SnapshotRepository}. The label map is the fallback for a domain whose
     * integrations-configuration ConfigMap is missing.
     */
    private Set<String> remainingSnapshotIds(IntegrationResources resources, String removedSnapshotId) {
        Set<String> ids = Optional.ofNullable(resources.integrationsConfiguration())
                .map(integrationConfigurationSerdes::getFromConfigMap)
                .map(IntegrationsConfiguration::getSources)
                .map(sources -> sources.stream()
                        .map(SourceDefinition::getId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(HashSet::new)))
                .orElseGet(() -> new HashSet<>(resources.getSourceByLabelMap(SNAPSHOT_ID_LABEL).keySet()));
        ids.remove(removedSnapshotId);
        return ids;
    }
```

- [ ] **Step 6: Subtract the retained paths in `deleteChainSnapshotHttpRoutes`**

Replace the method (lines 383-405) with:

```java
    /**
     * Strips {@code snapshotId}'s gateway paths from this domain's shared HTTPRoute tiers, minus any
     * path {@code remainingSnapshotIds} still owns. The tiers are one CR per micro-domain, shared by
     * every snapshot that domain hosts, and two snapshots can legitimately claim the same path: a
     * chain redeployed under a new snapshot ID before the superseded one is removed, or two chains
     * reaching the same external system through the same egress prefix. Deleting a rule another
     * snapshot still serves takes a running chain offline silently, so the strip set is this
     * snapshot's paths minus every remaining snapshot's paths -- the same subtraction
     * {@code ChainRouteRegistry.getUnsharedRoutes} performs on the engine side.
     */
    void deleteChainSnapshotHttpRoutes(String name, String snapshotId, Set<String> remainingSnapshotIds) {
        List<Route> ownRoutes = snapshotRoutes(List.of(snapshotId), "removed");
        List<Route> retainedRoutes = snapshotRoutes(remainingSnapshotIds, "remaining");

        Set<GatewayPathMatch> publicPaths = unsharedPaths(
                tierOwnPaths(ownRoutes, RouteType::isExternalTriggerRoute),
                tierOwnPaths(retainedRoutes, RouteType::isExternalTriggerRoute));
        Set<GatewayPathMatch> privatePaths = unsharedPaths(
                tierOwnPaths(ownRoutes, RouteType::isPrivateTriggerRoute),
                tierOwnPaths(retainedRoutes, RouteType::isPrivateTriggerRoute));
        Set<GatewayPathMatch> egressPaths = unsharedPaths(
                egressOwnPaths(ownRoutes), egressOwnPaths(retainedRoutes));

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

    /**
     * Resolves {@code snapshotIds} to the gateway routes they define. {@code description} labels the
     * warning logged when the catalog database has no row for every requested ID: a remaining
     * snapshot that cannot be resolved contributes no paths, which risks stripping a rule that is
     * still live, so it must not pass silently.
     */
    private List<Route> snapshotRoutes(Collection<String> snapshotIds, String description) {
        if (snapshotIds.isEmpty()) {
            return List.of();
        }
        var snapshots = snapshotRepository.findAllByIdIn(snapshotIds);
        if (snapshots.size() < snapshotIds.size()) {
            log.warn("Found {} of {} {} snapshot(s) in the catalog database; paths owned only by the "
                            + "missing snapshot(s) are invisible to HTTPRoute cleanup",
                    snapshots.size(), snapshotIds.size(), description);
        }
        return snapshots.stream()
                .flatMap(snapshot -> routesGetterService
                        .getRoutes(new SnapshotAdapter(snapshot), integrationServiceCatalog).stream())
                .toList();
    }

    /**
     * Returns {@code ownPaths} minus {@code retainedPaths}: the paths only the snapshot being
     * removed claims, and so the only ones safe to strip from a tier shared with other snapshots.
     */
    private Set<GatewayPathMatch> unsharedPaths(Set<GatewayPathMatch> ownPaths, Set<GatewayPathMatch> retainedPaths) {
        Set<GatewayPathMatch> unshared = new HashSet<>(ownPaths);
        unshared.removeAll(retainedPaths);
        return unshared;
    }
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `mvn -pl runtime-catalog test -Dtest=MicroDomainServiceHttpRouteTest`
Expected: PASS, both new tests plus all thirteen updated ones.

- [ ] **Step 8: Run the neighboring suites**

Run: `mvn -pl runtime-catalog test -Dtest='MicroDomainServiceTest,MicroDomainResourceBuildContextFactoryTest,CustomResourceControllerTest'`
Expected: PASS. `MicroDomainServiceTest` covers `deleteChainSnapshot` end to end (lines 287-331) and now exercises `remainingSnapshotIds` through the mocked `IntegrationConfigurationSerdes`; if the mock returns `null` from `getFromConfigMap`, the `Optional` chain falls through to the label-map branch, which is the intended behavior.

- [ ] **Step 9: Commit**

```bash
git add runtime-catalog/src/main/java/org/qubership/integration/platform/runtime/catalog/cr/MicroDomainService.java \
        runtime-catalog/src/test/java/org/qubership/integration/platform/runtime/catalog/cr/MicroDomainServiceHttpRouteTest.java
git commit -m "fix: keep HTTPRoute rules a remaining snapshot still owns during cleanup @cf_skip"
```

---

### Task 4: Align the qip-dev gateway chart with what the generators emit

**Files:**
- Modify: `infrastructure/qip-dev/charts/gateways/templates/egress-gateway.yaml`
- Modify: `infrastructure/qip-dev/charts/gateways/templates/internal-gateway.yaml`
- Modify: `runtime-catalog/src/main/resources/application.yml:288`
- Modify: `infrastructure/qip-dev/README.md`

**Interfaces:**
- No Java changes and no test changes. `qip.gateway.egress.url` keeps its effective default, `egress-gateway:8080`, and gains a `QIP_EGRESS_GATEWAY_URL` override.

Two mismatches keep the local Istio install from exercising the internal and egress flows:

- Generated Camel routes call `http://egress-gateway:8080/system/...` (`EndpointHelperSource`, from `qip.gateway.egress.url`), but the chart's egress Gateway listens on 80. Istio's Gateway API deployment controller derives the generated Service's port from the listener, so nothing answers on 8080.
- `EngineRoutesResourceBuilder` attaches the engine HTTPRoute to a `parentRef` of `kind: Service`, `name: internal-gateway-service` (GAMMA mesh routing, from `qip.gateway.internal.name`). The chart's internal Gateway carries no `gateway.istio.io/name-override`, so Istio names the Service `internal-gateway-istio` and the `parentRef` resolves to nothing.

- [ ] **Step 1: Move the egress listener to 8080**

In `infrastructure/qip-dev/charts/gateways/templates/egress-gateway.yaml`, change the listener port:

```yaml
      name: http
      port: 8080
      protocol: HTTP
```

- [ ] **Step 2: Name the internal gateway's Service and move it to 8080**

Replace `infrastructure/qip-dev/charts/gateways/templates/internal-gateway.yaml` with:

```yaml
{{- if .Values.enabled }}
---
apiVersion: gateway.networking.k8s.io/v1
kind: Gateway
metadata:
  annotations:
    networking.istio.io/service-type: NodePort
    # This forces Istio to name the created K8s Service "internal-gateway-service", which is the
    # Service the engine HTTPRoute names in its parentRef (qip.gateway.internal.name). Without the
    # override Istio would name it "internal-gateway-istio" and the route would attach to nothing.
    gateway.istio.io/name-override: "internal-gateway-service"
  name: internal-gateway
spec:
  gatewayClassName: istio
  listeners:
    - allowedRoutes:
        namespaces:
          from: Same
      name: http
      port: 8080
      protocol: HTTP
{{- end }}
```

- [ ] **Step 3: Make the egress URL overridable**

In `runtime-catalog/src/main/resources/application.yml:288`:

```yaml
      url: ${QIP_EGRESS_GATEWAY_URL:egress-gateway:8080}
```

Spring splits the placeholder at the first colon, so the default remains the literal `egress-gateway:8080`. A site whose Istio egress gateway listens elsewhere sets `QIP_EGRESS_GATEWAY_URL` instead of rebuilding.

- [ ] **Step 4: Render the chart and verify the two Gateways**

Run:

```bash
helm template qip infrastructure/qip-dev --show-only charts/gateways/templates/egress-gateway.yaml
helm template qip infrastructure/qip-dev --show-only charts/gateways/templates/internal-gateway.yaml
helm template qip infrastructure/qip-dev --show-only charts/gateways/templates/public-gateway.yaml
```

Expected: both `egress-gateway` and `internal-gateway` render `port: 8080`, and `internal-gateway` carries `gateway.istio.io/name-override: "internal-gateway-service"`. `public-gateway` still renders `port: 80` — nothing references its port.

- [ ] **Step 5: Verify the property still resolves where it is consumed**

Run: `mvn -pl integration-build-pipeline test -Dtest='EndpointHelperSourceTest,TemplateServiceTest'`
Expected: PASS. Both live in `integration-build-pipeline`, not `runtime-catalog`, and both set `qip.gateway.egress.url` explicitly, so they pin the behavior rather than the default. `runtime-catalog`'s own consumer, `ApplicationAutoConfiguration`, is covered by the full module run in Task 5.

- [ ] **Step 6: Document what a local Istio install needs**

Add this section to `infrastructure/qip-dev/README.md`, directly after the `## Installation` block:

````markdown
## Istio

`global.qip.controlPlane.meshType: Istio` (the default in `values.yaml`) makes the platform generate
Gateway API and Istio resources. Install Istio with the Gateway API CRDs before installing this
chart, and enable the alpha Gateway API features that egress routing depends on:

```sh
kubectl apply -f https://github.com/kubernetes-sigs/gateway-api/releases/download/v1.2.0/standard-install.yaml
istioctl install --set profile=minimal \
  --set values.pilot.env.PILOT_ENABLE_ALPHA_GATEWAY_API=true
kubectl label namespace qip istio-injection=enabled
```

Egress routes use `backendRefs` with `kind: Hostname`, which `istiod` only honors when
`PILOT_ENABLE_ALPHA_GATEWAY_API` is set. Without it the egress `HTTPRoute` is accepted but never
programmed, and outgoing calls fail with no route.

The chart's gateways listen on these ports:

| Gateway | Service name | Port |
| --- | --- | --- |
| `public-gateway` | `public-gateway` | 80 |
| `private-gateway` | `private-gateway` | 80 |
| `internal-gateway` | `internal-gateway-service` | 8080 |
| `egress-gateway` | `egress-gateway` | 8080 |

The internal and egress ports match `qip.gateway.internal.name` and `qip.gateway.egress.url` in
`runtime-catalog`. Change one and change the other, or set `QIP_EGRESS_GATEWAY_URL`.
````

- [ ] **Step 7: Commit**

```bash
git add infrastructure/qip-dev/charts/gateways/templates/egress-gateway.yaml \
        infrastructure/qip-dev/charts/gateways/templates/internal-gateway.yaml \
        infrastructure/qip-dev/README.md \
        runtime-catalog/src/main/resources/application.yml
git commit -m "fix: align qip-dev gateway ports and Service names with generated routes @cf_skip"
```

---

### Task 5: Verify the branch end to end

**Files:** none — verification only.

- [ ] **Step 1: Build and test the three touched modules**

Run: `mvn -pl engine,integration-build-pipeline,runtime-catalog -am test`
Expected: PASS, no compilation warnings introduced by the four tasks.

- [ ] **Step 2: Confirm the two helper copies stayed in sync**

Run:

```bash
diff --strip-trailing-cr \
  <(grep -v '^package ' engine/src/main/java/org/qubership/integration/platform/engine/util/paths/GatewayPathMatch.java) \
  <(grep -v '^package ' integration-build-pipeline/src/main/java/org/qubership/integration/platform/camelk/util/paths/GatewayPathMatch.java)
diff --strip-trailing-cr \
  <(grep -v '^package ' engine/src/main/java/org/qubership/integration/platform/engine/util/paths/EgressTarget.java) \
  <(grep -v '^package ' integration-build-pipeline/src/main/java/org/qubership/integration/platform/camelk/util/paths/EgressTarget.java)
```

Expected: `GatewayPathMatch` produces no output at all. `EgressTarget` produces exactly the two-line difference that was already there before this plan:

```
42,43c42,43
<      * {@code ServiceEntry}/{@code DestinationRule} name -- in engine's live registration and
<      * the build pipeline's build-time generation alike. The hash suffix guarantees two hosts that
---
>      * {@code ServiceEntry}/{@code DestinationRule} name -- in engine's live registration and this
>      * module's build-time generation alike. The hash suffix guarantees two hosts that
```

Any other difference means Task 1 or Task 2 was applied to one copy only.

- [ ] **Step 3: Render the chart one more time**

Run: `helm template qip infrastructure/qip-dev > /dev/null && echo OK`
Expected: `OK` — the chart still renders after the Task 4 edits.
