// jsdom ships a `crypto` object without `randomUUID`, which production code
// uses for client-side ids (modal keys, new table rows). Fill it in from Node's
// own implementation so those paths run unchanged under jest.
import { randomUUID } from "node:crypto";

const webCrypto = globalThis.crypto as Crypto | undefined;

if (!webCrypto) {
  Object.defineProperty(globalThis, "crypto", {
    writable: true,
    configurable: true,
    value: { randomUUID },
  });
} else if (typeof webCrypto.randomUUID !== "function") {
  Object.defineProperty(webCrypto, "randomUUID", {
    writable: true,
    configurable: true,
    value: randomUUID,
  });
}
