import { App, Modal, message as staticMessage } from "antd";
import { useEffect } from "react";

// Antd v6 static `message` and `Modal` methods render outside React context,
// so they ignore the app's VS Code theme and locale. Capture the context-aware
// instances from <App> here and route shared call sites — including the
// non-component `confirmAndRun` — through them. Until <App> mounts, calls fall
// back to the static API.

type AppApi = ReturnType<typeof App.useApp>;
type MessageApi = AppApi["message"];
type ModalApi = AppApi["modal"];

let messageApi: MessageApi | null = null;
let modalApi: ModalApi | null = null;

function delegate<T extends object>(getApi: () => T): T {
  return new Proxy({} as T, {
    get(_target, prop) {
      const api = getApi();
      const value = api[prop as keyof T];
      return typeof value === "function"
        ? (value as (...args: unknown[]) => unknown).bind(api)
        : value;
    },
  });
}

/** Context-aware `message`, themed by the app's ConfigProvider. */
export const message: MessageApi = delegate(
  () => messageApi ?? (staticMessage as unknown as MessageApi),
);

/** Context-aware `Modal` methods (`confirm`, `info`, `error`, ...). */
export const modal: ModalApi = delegate(
  () => modalApi ?? (Modal as unknown as ModalApi),
);

/** Registers the App context instances. Mount once inside antd `<App>`. */
export function AntdAppBridge(): null {
  const { message: contextMessage, modal: contextModal } = App.useApp();
  useEffect(() => {
    messageApi = contextMessage;
    modalApi = contextModal;
    return () => {
      if (messageApi === contextMessage) messageApi = null;
      if (modalApi === contextModal) modalApi = null;
    };
  }, [contextMessage, contextModal]);
  return null;
}
