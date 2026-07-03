// jsdom does not implement MessageChannel, which antd v6's form engine
// (@rc-component/form) uses to defer field-watch notifications to a macro task.
// Provide a minimal macro-task polyfill so form-rendering tests run under jsdom.
// The consumer only uses `port1.onmessage` and `port2.postMessage`. Delivery
// must stay a macro task (not a microtask): rc-form relies on React re-rendering
// between notifications, so a microtask would loop until the queue overflows.

type MessageListener = ((event: { data: unknown }) => void) | null;

class PolyfillMessagePort {
  onmessage: MessageListener = null;
  private target: PolyfillMessagePort | null = null;

  link(target: PolyfillMessagePort): void {
    this.target = target;
  }

  postMessage(data: unknown): void {
    const { target } = this;
    setTimeout(() => target?.onmessage?.({ data }), 0);
  }

  start(): void {}
  close(): void {}
  addEventListener(): void {}
  removeEventListener(): void {}
}

class PolyfillMessageChannel {
  readonly port1 = new PolyfillMessagePort();
  readonly port2 = new PolyfillMessagePort();

  constructor() {
    this.port1.link(this.port2);
    this.port2.link(this.port1);
  }
}

if (typeof globalThis.MessageChannel === "undefined") {
  globalThis.MessageChannel =
    PolyfillMessageChannel as unknown as typeof globalThis.MessageChannel;
}
