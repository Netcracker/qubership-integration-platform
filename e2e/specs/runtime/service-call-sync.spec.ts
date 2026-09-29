/**
 * `integrationOperationProtocolType` on `service-call`, the synchronous protocols: http and graphql.
 *
 * Each case builds its own service, imports a specification of the type the protocol expects
 * (OpenAPI, GraphQL schema), and deploys a trigger into a service call in the worker folder. A seed
 * chain cannot carry the service, specification and operation ids the element needs.
 *
 * What the element sent is asserted, not only what it got back, and the two protocols get there
 * differently:
 *
 * - **http** is mocked by the testing service, and the mock's matchers **require** the path
 *   parameter, the query parameter and the body the element should send. A request built wrong
 *   matches no mock, the testing service answers 404, and the trace shows the failed call. The
 *   testing service records no requests, and this is the substitute: do not add recording to it.
 * - **graphql** is never mocked. `EndpointMockTestingService` reads the exchange out of the HTTP
 *   client's context, and the GraphQL producer files none there, so the call reaches its address.
 *   The address is the seeded `http-echo` route on the engine's own port, which answers with the
 *   request the element sent. The propagated correlation header puts the echo's steps into the
 *   same session lookup, which is what proves a real endpoint answered.
 *
 * Two values are not covered, and the registry says why. `soap`: a WSDL import creates a service
 * whose protocol is `http` (`protocol=soap` is refused, "Unsupported protocol: soap"), so the
 * element form writes `http` and a SOAP call runs the http case; a `soap` value has no branch in
 * `service-call/template.hbs`. `grpc`: the `.proto` import works and serves a library, and nothing
 * in the stack answers a gRPC call.
 */
import { test, expect } from "../../support/fixtures.js";
import { ENGINE_CASE_TIMEOUT, readCorpusState, seedChain } from "../../support/corpus.js";
import { ENGINE_LOOPBACK } from "../../fixtures/axis-generator.js";
import { readSpecificationFixture } from "../../fixtures/templating.js";
import { notTheKnownDefect } from "../../support/known-defect.js";
import { callToken, tokenized } from "../../support/run.js";
import { callChain, elementNames, HTTP_TRIGGER_STEPS, trace, type RecordedSession } from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";
import { withBuilt } from "../../support/cleanup.js";
import { HTTP_ADDRESS, HTTP_OPERATION, MOCKED_BODY, openApi, ORDER_ID, serviceCallChain, type ServiceCallOptions } from "./service-call.js";

const CHANNEL = "e2e";
const GRAPHQL_QUERY = "query Widget($id: ID!) { widget(id: $id) { id name } }";
const GRAPHQL_VARIABLES = { id: "e2e-widget" };

/** The steps of the looked-up session (`own`), or of the other session the lookup folded in. */
function stepsOf(session: RecordedSession, own: boolean): string[] {
  return trace(session).filter((each) => (each.sessionId === session.id) === own).map((each) => each.elementName);
}

function graphqlOptions(what: string, properties: Record<string, unknown>): ServiceCallOptions {
  const echo = seedChain(readCorpusState(), "http-echo");
  return {
    what,
    protocol: "graphql",
    file: readSpecificationFixture("widgets.graphql"),
    address: `${ENGINE_LOOPBACK}/routes/${echo.contextPath}`,
    operation: "widget",
    properties: {
      integrationGqlQuery: GRAPHQL_QUERY,
      integrationGqlVariablesJSON: JSON.stringify(GRAPHQL_VARIABLES),
      ...properties,
    },
  };
}

test("integrationOperationProtocolType http sends the path, query and body the element declares", { tag: ["@engine", "@catalog", "@sessions", "@testing-service", "@tier2"] }, async ({ request, env, catalog, sessions, testingService, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  covers("service-call");
  covers("service-call", "integrationOperationProtocolType", "http");
  await withBuilt(catalog, async (built) => {
    const { chain, callerId, callerName } = await serviceCallChain(catalog, env, run, folder.id, sessions, {
      what: "http",
      protocol: "http",
      file: openApi(),
      address: HTTP_ADDRESS,
      operation: HTTP_OPERATION,
      properties: {
        integrationOperationPathParameters: { orderId: ORDER_ID },
        integrationOperationQueryParameters: { channel: CHANNEL },
      },
    }, built);

    const payload = callToken("payload");
    const matchers = [
      { type: "equal", entityType: "path_parameter", entityName: "orderId", value: ORDER_ID },
      { type: "equal", entityType: "query_parameter", entityName: "channel", value: CHANNEL },
      { type: "contain", entityType: "body", value: payload },
    ] as const;
    await testingService.withMock({ name: tokenized(run, "service-call-http"), reference: { chainId: chain.id, elementId: callerId }, response: { status: 201, body: MOCKED_BODY }, matchers: [...matchers] }, async () => {
      const call = await callChain(request, env.chainUrl(chain.contextPath), { data: { payload } });
      expect(call.response.status(), "no mock matched: the element sent another path, query or body").toBe(201);
      expect(await call.response.text()).toBe(MOCKED_BODY);
      const session = await sessions.byExternalId(call.token, { elements: 4 });
      expect(session.executionStatus).toBe("COMPLETED_NORMALLY");
      expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, callerName, "Request attempt"]);
    });
  });
});

test("integrationOperationProtocolType graphql posts the query and its variables to the service", { tag: ["@engine", "@catalog", "@sessions", "@tier2"] }, async ({ request, env, catalog, sessions, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  covers("service-call", "integrationOperationProtocolType", "graphql");
  await withBuilt(catalog, async (built) => {
    // `before` as the element form writes it; without one the query is never set, see
    // docs/product-defects.md and the case below.
    const { chain, callerName } = await serviceCallChain(catalog, env, run, folder.id, sessions, graphqlOptions("graphql", { before: { type: "none" } }), built);

    const call = await callChain(request, env.chainUrl(chain.contextPath), { data: {} });
    expect(call.response.status()).toBe(200);
    expect(JSON.parse(await call.response.text())).toEqual({ query: GRAPHQL_QUERY, operationName: null, variables: GRAPHQL_VARIABLES });
    const session = await sessions.byExternalId(call.token, { elements: 8 });
    expect(session.executionStatus).toBe("COMPLETED_NORMALLY");
    expect(stepsOf(session, true)).toEqual([...HTTP_TRIGGER_STEPS, callerName, "Prepare request", "Request attempt"]);
    expect(stepsOf(session, false), "the echo did not run: something else answered the call").toEqual([...HTTP_TRIGGER_STEPS, "Header Modification"]);
  });
});

test("a graphql service call with no before sends the query it declares", { tag: ["@engine", "@catalog", "@sessions", "@tier2"] }, async ({ request, env, catalog, sessions, folder, run }) => {
  test.setTimeout(ENGINE_CASE_TIMEOUT);
  // Released before the annotation, so a leak it reports cannot pass for the expected failure.
  const { status, body } = await withBuilt(catalog, async (built) => {
    const { chain } = await serviceCallChain(catalog, env, run, folder.id, sessions, graphqlOptions("graphql-no-before", {}), built);
    const call = await callChain(request, env.chainUrl(chain.contextPath), { data: {} });
    return { status: call.response.status(), body: await call.response.text() };
  });
  const sent = status === 200 ? (JSON.parse(body) as { query?: unknown }).query : undefined;

  // docs/product-defects.md: the template sets the GraphQL query inside the `before` step only.
  test.fail();
  if (sent !== null && sent !== GRAPHQL_QUERY) {
    notTheKnownDefect(`the echo answered ${status} ${body}; the defect sends a null query and the fix ${GRAPHQL_QUERY}`);
  }
  expect(sent).toBe(GRAPHQL_QUERY);
});
