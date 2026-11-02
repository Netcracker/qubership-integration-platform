/**
 * `integrationOperationProtocolType` on `service-call`, the synchronous protocols: http and graphql.
 *
 * Each case calls a seeded chain, a trigger into a service call on an operation of a service fixture
 * under `fixtures/services/` whose specification is of the type the protocol expects (OpenAPI,
 * GraphQL schema). Both seeds import the services before their chains.
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
 * element form writes `http` (`specs/ui/chain-graph.spec.ts`) and a SOAP call runs the http case; a
 * `soap` value has no branch in `service-call/template.hbs`. `grpc`: the `.proto` import works and
 * serves a library, and nothing in the stack answers a gRPC call.
 */
import { test, expect } from "../../support/fixtures.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";
import { GRAPHQL_QUERY, GRAPHQL_VARIABLES, ORDER_CHANNEL, ORDER_ID } from "../../fixtures/axis-generator.js";
import { callToken, tokenized } from "../../support/run.js";
import { callChain, elementNames, HTTP_TRIGGER_STEPS, trace, type RecordedSession } from "../../support/sessions.js";
import { covers } from "../../registry/covers.js";
import { axisChain, MOCKED_BODY } from "./service-call.js";

/** The steps of the looked-up session (`own`), or of the other session the lookup folded in. */
function stepsOf(session: RecordedSession, own: boolean): string[] {
  return trace(session).filter((each) => (each.sessionId === session.id) === own).map((each) => each.elementName);
}

const PROTOCOL = "integrationOperationProtocolType";

test.describe("service-call sync protocols", { tag: ["@engine", "@catalog", "@sessions"] }, () => {
  test("http sends the declared path, query and body", { tag: ["@testing-service"] }, async ({ request, env, sessions, testingService, run }) => {
    covers("service-call");
    covers("service-call", "integrationOperationProtocolType", "http");
    const { chain, branch, callerId } = axisChain(PROTOCOL, "http");

    const payload = callToken("payload");
    const matchers = [
      { type: "equal", entityType: "path_parameter", entityName: "orderId", value: ORDER_ID },
      { type: "equal", entityType: "query_parameter", entityName: "channel", value: ORDER_CHANNEL },
      { type: "contain", entityType: "body", value: payload },
    ] as const;
    await testingService.withMock({ name: tokenized(run, "service-call-http"), reference: { chainId: chain.id, elementId: callerId }, response: { status: 201, body: MOCKED_BODY }, matchers: [...matchers] }, async () => {
      const call = await callChain(request, env.chainUrl(chain.contextPath), { data: { payload } });
      const body = await call.response.text();
      expect(call.response.status(), `no mock matched: the element sent another path, query or body. ${body}`).toBe(201);
      expect(body).toBe(MOCKED_BODY);
      const session = await sessions.byExternalId(call.token, { elements: 4 });
      expect(session.executionStatus).toBe("COMPLETED_NORMALLY");
      expect(elementNames(session)).toEqual([...HTTP_TRIGGER_STEPS, branch, "Request attempt"]);
    });
  });

  test("graphql posts the query and its variables", async ({ request, env, sessions }) => {
    covers("service-call", "integrationOperationProtocolType", "graphql");
    const { chain, branch } = axisChain(PROTOCOL, "graphql");

    const call = await callChain(request, env.chainUrl(chain.contextPath), { data: {} });
    expect(call.response.status()).toBe(200);
    expect(JSON.parse(await call.response.text())).toEqual({ query: GRAPHQL_QUERY, operationName: null, variables: GRAPHQL_VARIABLES });
    const session = await sessions.byExternalId(call.token, { elements: 8 });
    expect(session.executionStatus).toBe("COMPLETED_NORMALLY");
    expect(stepsOf(session, true)).toEqual([...HTTP_TRIGGER_STEPS, branch, "Prepare request", "Request attempt"]);
    expect(stepsOf(session, false), "the echo did not run: something else answered the call").toEqual([...HTTP_TRIGGER_STEPS, "Header Modification"]);
  });

  test("graphql with no before sends the declared query", async ({ request, env }) => {
    const chain = seedChain(readCorpusState(), "service-call-graphql-no-before");
    const call = await callChain(request, env.chainUrl(chain.contextPath), { data: {} });
    expect(call.response.status()).toBe(200);
    expect(JSON.parse(await call.response.text())).toEqual({ query: GRAPHQL_QUERY, operationName: null, variables: GRAPHQL_VARIABLES });
  });
});
