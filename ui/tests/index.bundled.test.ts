const mockOrder: string[] = [];

jest.mock("../src/monaco-init-runtime", () => ({
  initBundledMonaco: jest.fn(() => mockOrder.push("initBundledMonaco")),
}));

jest.mock("../src/index", () => {
  mockOrder.push("./index");
  return {};
});

// ts-jest compiles to CommonJS, which keeps statement order, so the ES module
// evaluation order behind #1223 shows only in a browser (the e2e `vscode` project).
describe("index.bundled", () => {
  it("should configure the bundled Monaco before loading the library", () => {
    jest.isolateModules(() => {
      require("../src/index.bundled");
    });

    expect(mockOrder).toEqual(["initBundledMonaco", "./index"]);
  });
});
