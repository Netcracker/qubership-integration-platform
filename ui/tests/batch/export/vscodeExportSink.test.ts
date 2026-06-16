const sendMessageToExtension = jest.fn().mockResolvedValue({});

jest.mock("../../../src/api/rest/vscodeExtensionApi", () => {
  class VSCodeExtensionApi {
    sendMessageToExtension = sendMessageToExtension;
  }
  return { VSCodeExtensionApi, isVsCode: true };
});

import { api } from "../../../src/api/api.ts";
import { VSCodeExtensionApi } from "../../../src/api/rest/vscodeExtensionApi.ts";
import {
  reportExportImagesProgress,
  saveExportedImageToVsCode,
} from "../../../src/batch/export/vscodeExportSink";

describe("vscodeExportSink", () => {
  beforeEach(() => {
    sendMessageToExtension.mockClear();
    Object.setPrototypeOf(api, VSCodeExtensionApi.prototype);
    (api as VSCodeExtensionApi).sendMessageToExtension = sendMessageToExtension;
  });

  test("saveExportedImageToVsCode sends saveExportedImage to the extension", async () => {
    await saveExportedImageToVsCode("/tmp/out", {
      target: { chainId: "chain-1", outputName: "chain-1" },
      fileName: "chain-1.png",
      contentBase64: "abc",
    });

    expect(sendMessageToExtension).toHaveBeenCalledWith("saveExportedImage", {
      outputDir: "/tmp/out",
      fileName: "chain-1.png",
      contentBase64: "abc",
    });
  });

  test("saveExportedImageToVsCode throws outside VS Code webview", async () => {
    Object.setPrototypeOf(api, Object.prototype);

    await expect(
      saveExportedImageToVsCode("/tmp/out", {
        target: { chainId: "chain-1" },
        fileName: "chain-1.png",
        contentBase64: "abc",
      }),
    ).rejects.toThrow("VS Code export sink is available only in VS Code webview");
  });

  test("reportExportImagesProgress forwards events in VS Code webview", async () => {
    await reportExportImagesProgress("exportImagesProgress", { current: 1 });

    expect(sendMessageToExtension).toHaveBeenCalledWith("exportImagesProgress", {
      current: 1,
    });
  });

  test("reportExportImagesProgress is a no-op outside VS Code webview", async () => {
    Object.setPrototypeOf(api, Object.prototype);

    await reportExportImagesProgress("exportImagesStarted");

    expect(sendMessageToExtension).not.toHaveBeenCalled();
  });
});
