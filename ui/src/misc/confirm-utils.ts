import type { ReactNode } from "react";
import type { ModalFuncProps } from "antd";
import { modal } from "./antd-app.ts";

type ConfirmAndRunOptions = Pick<
  ModalFuncProps,
  | "content"
  | "okText"
  | "cancelText"
  | "okType"
  | "okButtonProps"
  | "cancelButtonProps"
  | "icon"
  | "width"
> & {
  title: ReactNode;
  /** Runs on confirm. A returned promise keeps the dialog in its loading state. */
  onOk: () => void | Promise<void>;
};

export function confirmAndRun({ onOk, ...props }: ConfirmAndRunOptions) {
  modal.confirm({
    ...props,
    onOk: async () => onOk(),
  });
}
