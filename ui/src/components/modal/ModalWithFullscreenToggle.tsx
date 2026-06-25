import type { ModalProps } from "antd";
import { Button, Flex, Modal } from "antd";
import React, { useCallback, useState } from "react";
import { OverridableIcon } from "../../icons/IconProvider.tsx";
import { FullscreenButton } from "./FullscreenButton.tsx";
import styles from "./ModalWithFullscreenToggle.module.css";

export const ModalWithFullscreenToggle: React.FC<ModalProps> = ({
  title,
  height,
  width,
  onCancel,
  className,
  classNames,
  ...rest
}): React.ReactNode => {
  const [isFullscreen, setIsFullscreen] = useState<boolean>(false);

  // Antd v6 allows `classNames` to be a function; merge only the object form.
  const baseClassNames =
    typeof classNames === "function" ? undefined : classNames;

  // Keep a caller-provided slot class when it is a plain string (v6 also allows
  // per-slot objects/functions, which this component does not forward).
  const callerClass = (slot: string): string | undefined => {
    const value = (baseClassNames as Record<string, unknown> | undefined)?.[
      slot
    ];
    return typeof value === "string" ? value : undefined;
  };

  const addClass = useCallback(
    (
      classes: string | undefined,
      classNamePrefix: keyof typeof styles,
    ): string | undefined => {
      const suffix = isFullscreen ? "-fullscreen" : "";
      const className = (classNamePrefix + suffix) as keyof typeof styles;
      return [classes, styles[classNamePrefix], styles[className]]
        .filter((i) => !!i)
        .join(" ");
    },
    [isFullscreen],
  );

  return (
    <Modal
      open
      title={
        <Flex
          align="center"
          gap={0}
          wrap={false}
          justify="space-between"
          style={{ width: "100%" }}
        >
          <Flex
            align="center"
            wrap={false}
            flex={1}
            style={{ minWidth: 0, overflow: "hidden" }}
          >
            {title}
          </Flex>
          <Flex
            align="center"
            gap={4}
            wrap={false}
            style={{ flexShrink: 0, marginLeft: "auto" }}
          >
            <FullscreenButton
              isFullscreen={isFullscreen}
              onClick={() => setIsFullscreen((prevState) => !prevState)}
            />
            <Button
              icon={<OverridableIcon name="close" />}
              onClick={(e) =>
                onCancel?.(e as React.MouseEvent<HTMLButtonElement>)
              }
              type="text"
              title="Close"
              size="small"
            />
          </Flex>
        </Flex>
      }
      onCancel={onCancel}
      width={isFullscreen ? "100vw" : (width ?? "90vw")}
      height={isFullscreen ? "100vh" : (height ?? "90vh")}
      className={addClass(className, "modal")}
      classNames={{
        ...baseClassNames,
        container: addClass(callerClass("container"), "modal-content"),
        header: addClass(callerClass("header"), "modal-header"),
        footer: addClass(callerClass("footer"), "modal-footer"),
        body: addClass(callerClass("body"), "modal-body"),
        wrapper: addClass(callerClass("wrapper"), "modal-wrapper"),
      }}
      {...rest}
      closable={false}
      maskClosable={false}
    />
  );
};
