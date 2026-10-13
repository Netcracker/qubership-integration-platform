/** @jest-environment jsdom */
import { prepareFiles } from "../../src/ai/opencode/files.ts";

it("should normalize text attachments and retain UTF-8 contents", async () => {
  const files = await prepareFiles([
    new File(["name: \u041f\u0440\u0438\u043c\u0435\u0440"], "test.yaml", {
      type: "application/yaml",
    }),
  ]);
  expect(files[0]).toMatchObject({
    type: "file",
    filename: "test.yaml",
    mime: "text/plain",
  });
  expect(
    Buffer.from(files[0].url.split(",")[1], "base64").toString("utf8"),
  ).toBe("name: \u041f\u0440\u0438\u043c\u0435\u0440");
});

it("should preserve image and PDF attachment types", async () => {
  const files = await prepareFiles([
    new File(["image"], "test.png", { type: "image/png" }),
    new File(["pdf"], "test.pdf", { type: "application/pdf" }),
  ]);
  expect(files.map((f) => f.mime)).toEqual(["image/png", "application/pdf"]);
  expect(files[0].url).toMatch(/^data:image\/png;base64,/);
});

it("should reject unsupported, oversized, and excessive attachments", async () => {
  await expect(
    prepareFiles([new File(["zip"], "test.zip", { type: "application/zip" })]),
  ).rejects.toThrow("not a supported attachment");
  const big = new File(["text"], "big.txt");
  Object.defineProperty(big, "size", { value: 10 * 1024 * 1024 + 1 });
  await expect(prepareFiles([big])).rejects.toThrow("10 MB");
  await expect(
    prepareFiles(Array.from({ length: 6 }, () => new File(["x"], "x.txt"))),
  ).rejects.toThrow("five files");
});
