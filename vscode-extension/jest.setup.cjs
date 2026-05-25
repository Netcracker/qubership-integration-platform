// Polyfill Web API globals for Jest's "node" testEnvironment.
// Jest's vm sandbox does not expose these by default, even on Node >= 20
// where they are regular globals. Production code uses:
//   crypto.randomUUID()      — needs globalThis.crypto.randomUUID
//   new File([...], name)    — needs globalThis.File
if (typeof globalThis.crypto === "undefined") {
  const { webcrypto } = require("node:crypto");
  Object.defineProperty(globalThis, "crypto", {
    value: webcrypto,
    configurable: true,
  });
}

if (typeof globalThis.File === "undefined") {
  const { File, Blob } = require("node:buffer");
  Object.defineProperty(globalThis, "File", { value: File, configurable: true });
  if (typeof globalThis.Blob === "undefined") {
    Object.defineProperty(globalThis, "Blob", {
      value: Blob,
      configurable: true,
    });
  }
}
