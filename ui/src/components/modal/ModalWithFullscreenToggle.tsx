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
        container: addClass(undefined, "modal-content"),
        header: addClass(undefined, "modal-header"),
        footer: addClass(undefined, "modal-footer"),
        body: addClass(undefined, "modal-body"),
        wrapper: addClass(undefined, "modal-wrapper"),
      }}
      {...rest}
      closable={false}
      maskClosable={false}
    />
  );
};
