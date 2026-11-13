import { test, expect } from "@playwright/test";
import { kindDevServerHost } from "../../env/provision/k8s.js";

test.describe("kind host networking", () => {
  test("Docker Desktop reaches the UI through the host DNS name", async () => {
    const calls: string[][] = [];
    const host = await kindDevServerHost(async (command, args) => {
      calls.push([command, ...args]);
      return "Docker Desktop\n";
    });
    expect(host).toBe("host.docker.internal");
    expect(calls).toEqual([["docker", "info", "--format", "{{.OperatingSystem}}"]]);
  });

  test("Docker Engine reaches the UI through the bridge gateway", async () => {
    const host = await kindDevServerHost(async (_, args) =>
      args[0] === "info" ? "Ubuntu 24.04\n" : "172.18.0.1 \n",
    );
    expect(host).toBe("172.18.0.1");
    await expect(kindDevServerHost(async (_, args) => args[0] === "info" ? "Ubuntu" : ""))
      .rejects.toThrow("no IPv4 gateway");
  });
});
