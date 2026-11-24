import { test as base } from "@playwright/test";
import { configureSuite, suiteExtension } from "../../env/suite-extension.js";

export const platformTest = base.extend<{ referencePlatform: void }>({
  referencePlatform: [async ({}, use) => {
    const settings = process.env.QIP_E2E_EXTERNAL;
    const extension = suiteExtension();
    delete process.env.QIP_E2E_EXTERNAL;
    configureSuite({});
    try {
      await use();
    } finally {
      if (settings !== undefined) process.env.QIP_E2E_EXTERNAL = settings;
      else delete process.env.QIP_E2E_EXTERNAL;
      configureSuite(extension);
    }
  }, { auto: true }],
});
