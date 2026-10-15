/**
 * The MaaS forms under Dev Tools: the declarative file Export downloads holds what was typed.
 *
 * What the API cannot see: which form field reaches which query parameter of
 * `POST /v1/maas-actions/{kafka,rabbitmq}/declarative`, and that the file reaches the browser as a
 * download. `specs/api/maas.spec.ts` covers the file the catalog builds from the parameters.
 *
 * No case presses Create. It calls the MaaS agent, which this stack does not run, so each case
 * asserts that the page sent no create request. The Namespace field gates Export but is not sent
 * with it: the file names the namespace as the placeholder `${ENV_NAMESPACE}`, which the deployer
 * resolves in the target namespace.
 */
import { readFile } from "node:fs/promises";
import type { Download, Page } from "@playwright/test";
import yaml from "js-yaml";
import { test, expect } from "../../support/page-guard.js";
import { callToken } from "../../support/run.js";

/** `MAAS_NAMESPACE_PLACEHOLDER` in `MaasService.java`. */
const NAMESPACE_PLACEHOLDER = "${ENV_NAMESPACE}";

/** The create requests the page sent, which go to the MaaS agent rather than build a file. */
function watchCreates(page: Page): string[] {
  const creates: string[] = [];
  page.on("request", (sent) => {
    if (sent.method() === "POST" && /\/maas-actions\/(kafka|rabbitmq)$/.test(new URL(sent.url()).pathname)) creates.push(sent.url());
  });
  return creates;
}

/** Presses Export and reads the downloaded file. */
async function exportFile(page: Page): Promise<{ name: string; manifest: unknown }> {
  const downloaded: Promise<Download> = page.waitForEvent("download");
  await page.getByRole("button", { name: "Export" }).click();
  const download = await downloaded;
  return { name: download.suggestedFilename(), manifest: yaml.load(await readFile(await download.path(), "utf8")) };
}

test("the Kafka MaaS export downloads a topic manifest for the typed classifier", { tag: ["@ui", "@tier2"] }, async ({ page }) => {
  const classifier = callToken("ui-maas-topic");
  const creates = watchCreates(page);

  await page.goto("/devtools/maas/kafka");
  await page.getByRole("textbox", { name: "Namespace" }).fill(callToken("ui-maas-ns"));
  await page.getByRole("textbox", { name: "Topic Classifier Name" }).fill(classifier);
  const { name, manifest } = await exportFile(page);

  expect(name).toBe("maas-configuration.yaml");
  expect(manifest).toEqual({
    apiVersion: "nc.maas.kafka/v1",
    kind: "topic",
    spec: { classifier: { name: classifier, namespace: NAMESPACE_PLACEHOLDER } },
  });
  expect(creates).toEqual([]);
});

test("the RabbitMQ MaaS export downloads a vhost manifest with the typed exchange, queue, and binding", { tag: ["@ui", "@tier2"] }, async ({ page }) => {
  const vhost = callToken("ui-maas-vhost");
  const exchange = callToken("ui-maas-exchange");
  const queue = callToken("ui-maas-queue");
  const routingKey = callToken("ui-maas-rk");
  const creates = watchCreates(page);

  await page.goto("/devtools/maas/rabbitmq");
  await page.getByRole("textbox", { name: "Namespace" }).fill(callToken("ui-maas-ns"));
  // The vhost starts at `public`, so the typed value replaces it.
  await page.getByRole("textbox", { name: "Vhost Classifier Name" }).fill(vhost);
  await page.getByRole("textbox", { name: "Exchange Name" }).fill(exchange);
  await page.getByRole("textbox", { name: "Queue Name" }).fill(queue);
  await page.getByRole("textbox", { name: /^Routing Key/ }).fill(routingKey);
  const { name, manifest } = await exportFile(page);

  expect(name).toBe("maas-configuration.yaml");
  expect(manifest).toMatchObject({
    apiVersion: "nc.maas.rabbit/v1",
    kind: "vhost",
    spec: {
      classifier: { name: vhost, namespace: NAMESPACE_PLACEHOLDER },
      entities: {
        exchanges: [{ name: exchange, type: "direct", durable: "true", auto_delete: "false" }],
        queues: [{ name: queue, durable: "true", auto_delete: "false" }],
        bindings: [{ source: exchange, destination: queue, routing_key: routingKey }],
      },
    },
  });
  expect(creates).toEqual([]);
});
