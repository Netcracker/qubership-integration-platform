import { Modal } from "antd";
import type { ReactNode } from "react";

type ConfirmAndRunOptions = {
  title: ReactNode;
  content?: ReactNode;
  onOk: () => void | Promise<void>;
};

export function confirmAndRun(options: ConfirmAndRunOptions) {
  Modal.confirm({
    title: options.title,
    content: options.content,
    onOk: async () => options.onOk(),
  });
}
