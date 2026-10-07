import { test, expect } from "@playwright/test";
import { SESSION_SEARCH_HEADER_LIMIT } from "../../support/known-defect.js";

// The message the k8s run recorded for #985, cut to the part the narrowing reads.
const RECORDED =
  'POST http://localhost:30093/v1/sessions?count=1000 answered 400: {"serviceName":"Session Management",' +
  '"errorMessage":"Error during element execution","stacktrace":"org.opensearch.client.transport.httpclient5.' +
  "ResponseException: method [POST], host [http://qip-opensearch:9200], URI [/qip_qip-elements-qip-e2e-session-" +
  "elements/_search?typed_keys=true], status line [HTTP/1.1 502 Bad Gateway]\\nupstream connect error or " +
  'disconnect/reset before headers. reset reason: protocol error"}';

test("the session search pin recognizes the failure the k8s run recorded", { tag: ["@infra", "@tier1"] }, () => {
  expect(SESSION_SEARCH_HEADER_LIMIT.matches(RECORDED)).toBe(true);
});

test("the session search pin leaves any other 502 a real failure", { tag: ["@infra", "@tier1"] }, () => {
  const refused = RECORDED.replace("reset reason: protocol error", "reset reason: connection failure");
  expect(SESSION_SEARCH_HEADER_LIMIT.matches(refused)).toBe(false);
  expect(SESSION_SEARCH_HEADER_LIMIT.matches("answered 503: Service Unavailable")).toBe(false);
});
