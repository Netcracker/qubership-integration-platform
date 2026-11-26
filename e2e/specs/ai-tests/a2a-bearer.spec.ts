/**
 * The AI service's A2A endpoint refuses a message that carries no caller bearer.
 *
 * With `qip.ai.auth.required` on, an A2A message with no `Authorization` header must end its task
 * as failed, and must not reach the model. The conversational skill answers `message/send` with
 * the fixed session copy and no artifact. The Agent Card stays public, so discovery is not checked
 * here.
 *
 * Needs the AI service on `CIP_AI_SERVICE_URL` (default `http://localhost:8094`, the `ai` Compose
 * profile) started with `QIP_AI_A2A_ENABLED=true` and `QIP_AI_AUTH_REQUIRED=true`. The dev env file
 * sets auth off, so an override is needed. This project does not provision that service, so the
 * case runs only with `E2E_AI_A2A_BEARER=1`, as `E2E_AI_RECOVERY` gates the ai-recovery case.
 *
 * Shown failing: with the bearer guard in `CipAssistA2aAgentExecutor` (the `callerBearer == null
 * && AuthPolicy.required()` check) commented out, the case fails at `expect(task.status.state)
 * .toBe("failed")` with received `"completed"`: the turn answers with text. Restored, it passes.
 */
import { test, expect } from "@playwright/test";
import { randomUUID } from "node:crypto";

const serviceUrl = process.env.CIP_AI_SERVICE_URL ?? "http://localhost:8094";

test.describe("AI service A2A bearer", () => {
  test.skip(
    process.env.E2E_AI_A2A_BEARER !== "1",
    "needs a running ai-service with auth required; set E2E_AI_A2A_BEARER=1",
  );

  test("rejects message/send with no Authorization header", async ({ request }) => {
    const response = await request.post(`${serviceUrl}/rpc`, {
      data: {
        jsonrpc: "2.0",
        id: randomUUID(),
        method: "message/send",
        params: {
          message: {
            kind: "message",
            messageId: randomUUID(),
            role: "user",
            parts: [{ kind: "text", text: "List the chains" }],
          },
        },
      },
    });
    expect(response.status()).toBe(200);
    const body = await response.json();
    expect(body.error ?? null).toBeNull();

    const task = body.result.task ?? body.result;
    expect(task.status.state).toBe("failed");
    expect(task.status.message.parts[0].text).toBe(
      "Your session has expired. Sign in again to continue.",
    );
    expect(task.artifacts ?? []).toHaveLength(0);
  });
});
