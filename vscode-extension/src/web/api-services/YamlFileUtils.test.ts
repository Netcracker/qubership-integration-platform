jest.mock(
  "vscode",
  () => ({
    Uri: class Uri {},
    window: { showInformationMessage: jest.fn() },
  }),
  { virtual: true },
);

jest.mock("yaml", () => ({ stringify: jest.fn() }));
jest.mock("../response/file/fileApiProvider", () => ({
  fileApi: { writeFile: jest.fn() },
}));

import * as vscode from "vscode";
import * as yaml from "yaml";
import { fileApi } from "../response/file/fileApiProvider";
import { YamlFileUtils } from "./YamlFileUtils";

describe("YamlFileUtils", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  test("saveYamlFile writes YAML bytes via fileApi.writeFile", async () => {
    const fileUri = {} as any;
    const data = { a: 1 };

    (yaml.stringify as unknown as jest.Mock).mockReturnValue("a: 1\n");

    await YamlFileUtils.saveYamlFile(fileUri, data);

    expect(yaml.stringify).toHaveBeenCalledWith(data);
    expect(fileApi.writeFile).toHaveBeenCalledTimes(1);

    const [passedUri, passedBytes] = (fileApi.writeFile as unknown as jest.Mock)
      .mock.calls[0];
    expect(passedUri).toBe(fileUri);
    expect(passedBytes).toBeInstanceOf(Uint8Array);

    const decoded = new TextDecoder().decode(passedBytes as Uint8Array);
    expect(decoded).toBe("a: 1\n");
  });

  test("saveYamlFileWithMessage writes file and shows information message", async () => {
    const fileUri = {} as any;
    const data = { x: "y" };

    (yaml.stringify as unknown as jest.Mock).mockReturnValue("x: y\n");

    const spy = jest.spyOn(YamlFileUtils, "saveYamlFile");

    await YamlFileUtils.saveYamlFileWithMessage(fileUri, data, "Saved!");

    expect(spy).toHaveBeenCalledWith(fileUri, data);
    expect(fileApi.writeFile).toHaveBeenCalledTimes(1);

    const showInfo = vscode.window
      .showInformationMessage as unknown as jest.Mock;
    expect(showInfo).toHaveBeenCalledWith("Saved!");
  });
});
