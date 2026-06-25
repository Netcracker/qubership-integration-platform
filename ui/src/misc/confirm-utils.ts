import type { ReactNode } from "react";
import { modal } from "./antd-app.ts";

type ConfirmAndRunOptions = {
  title: ReactNode;
  content?: ReactNode;
  onOk: () => void | Promise<void>;
};

export function confirmAndRun(options: ConfirmAndRunOptions) {
  modal.confirm({
    title: options.title,
    content: options.content,
    onOk: async () => options.onOk(),
  });
}
