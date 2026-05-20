import { ModalProps } from "antd/es/modal/interface";
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
              onClick={onCancel}
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
        ...classNames,
        content: addClass(classNames?.content, "modal-content"),
        header: addClass(classNames?.header, "modal-header"),
        footer: addClass(classNames?.footer, "modal-footer"),
        body: addClass(classNames?.body, "modal-body"),
        wrapper: addClass(classNames?.wrapper, "modal-wrapper"),
      }}
      {...rest}
      closable={false}
      maskClosable={false}
    />
  );
};
