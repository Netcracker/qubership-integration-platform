/**
 * The MaaS forms under Dev Tools: the declarative file Export downloads holds what was typed.
 *
 * What the API cannot see: which form field reaches which query parameter of
 * `POST /v1/maas-actions/{kafka,rabbitmq}/declarative`, and that the file reaches the browser as a
 * download. `specs/api/maas.spec.ts` covers the file the catalog builds from the parameters.
 *
 * No case presses Create: it calls the MaaS agent, which this stack does not run. The Namespace
 * field gates Export but is not sent with it: the file names the namespace as the placeholder
 * `${ENV_NAMESPACE}`, which the deployer resolves in the target namespace.
 */
import { readFile } from "node:fs/promises";
import type { Download, Page } from "@playwright/test";
import yaml from "js-yaml";
import { test, expect } from "../../support/page-guard.js";
import { callToken } from "../../support/run.js";

/** `MAAS_NAMESPACE_PLACEHOLDER` in `MaasService.java`. */
const NAMESPACE_PLACEHOLDER = "${ENV_NAMESPACE}";

/** Presses Export and reads the downloaded file. */
async function exportFile(page: Page): Promise<{ name: string; manifest: unknown }> {
  const downloaded: Promise<Download> = page.waitForEvent("download");
  await page.getByRole("button", { name: "Export" }).click();
  const download = await downloaded;
  return { name: download.suggestedFilename(), manifest: yaml.load(await readFile(await download.path(), "utf8")) };
}

test.describe("MaaS export", () => {
  test("Kafka downloads a topic manifest for the typed classifier", async ({ page }) => {
    const classifier = callToken("ui-maas-topic");

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
  });

  test("RabbitMQ downloads the typed exchange, queue, and binding", async ({ page }) => {
    const vhost = callToken("ui-maas-vhost");
    const exchange = callToken("ui-maas-exchange");
    const queue = callToken("ui-maas-queue");
    const routingKey = callToken("ui-maas-rk");

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
  });
});
