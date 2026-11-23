import { request, type FullProject } from "@playwright/test";
import { ROLES } from "./containers.js";
import { assertExternalAuthorized } from "./external-auth.js";
import type { ExternalSettings } from "./external-settings.js";
import { newPlatformRequest } from "./platform-http.js";
import { emptyProvisionReport } from "./provision/common.js";
import { suiteExtension } from "./suite-extension.js";

const sourceProjects = new Set(["schema", "harness", "ai-tests", "schemas-build", "vscode-server"]);

function requirement(project: FullProject, input: string): never {
  throw new Error(`External project "${project.name}" requires ${input}.`);
}

export async function checkExternalRun(projects: FullProject[], settings: ExternalSettings) {
  const extension = suiteExtension();
  for (const project of projects) {
    const hasBuild = project.name === "schemas-build" && extension.lifecycle?.schemas
      || project.name === "vscode-server" && extension.lifecycle?.startVsCode;
    if (sourceProjects.has(project.name) && !hasBuild && !settings.full?.sourceRoot) {
      requirement(project, "sourceRoot");
    }
  }
  const project = projects.find((each) => each.metadata.stack !== false);
  if (!project) return;
  if (settings.full?.dedicatedStand !== true) requirement(project, "dedicatedStand: true");
  if (!extension.target) requirement(project, "a stand adapter with a target() implementation");
  for (const role of ROLES) {
    if (!settings.services[role]) requirement(project, `services["${role}"]`);
  }
  if (!settings.proxy && !settings.ui) requirement(project, "proxy or ui");
  const restart = projects.find(({ metadata }) => metadata.qipBrokerRestart === true);
  if (restart && !extension.brokers?.restart) requirement(restart, "adapter.brokers.restart");

  const report = emptyProvisionReport("never");
  const started = Date.now();
  for (const role of ROLES) {
    const base = settings.services[role]!;
    const api = await newPlatformRequest(request, role, base);
    try {
      const response = await api.get(`${base}${role === "testing-service" ? "/health" : "/actuator/health"}`);
      const status = response.status();
      assertExternalAuthorized(status, role);
      if (status !== 200) requirement(project, `a healthy ${role} service (HTTP ${status})`);
      const health = await response.json().catch(() => null);
      if (health?.status !== "UP") requirement(project, `a healthy ${role} service (expected status UP)`);
    } finally {
      await api.dispose();
    }
  }
  report.untouched = [...ROLES];
  report.durationMs = Date.now() - started;
  return report;
}
