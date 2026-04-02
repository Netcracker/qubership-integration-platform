import type React from "react";
import { useReactFlow, useStore, useViewport } from "@xyflow/react";
import { FullscreenOutlined, FullscreenExitOutlined } from "@ant-design/icons";

import styles from "./CustomControls.module.css";
import { useElkDirectionContext } from "../../pages/ElkDirectionContext.tsx";
import { useChainFullscreenContext } from "../../pages/ChainFullscreenContext.tsx";
import { Button } from "antd";
import { OverridableIcon } from "../../icons/IconProvider.tsx";

type CustomControlsProps = {
  extraButtons?: React.ReactNode;
  showLeftPanelToggle?: boolean;
  onExpandAllContainers?: () => void;
  onCollapseAllContainers?: () => void;
};

export const CustomControls = ({
  extraButtons,
  showLeftPanelToggle,
  onExpandAllContainers,
  onCollapseAllContainers,
}: CustomControlsProps) => {
  const { zoomIn, zoomOut, fitView } = useReactFlow();
  const { zoom } = useViewport();
  const { minZoom, maxZoom } = useStore((s) => ({
    minZoom: s.minZoom,
    maxZoom: s.maxZoom,
  }));
  const {
    toggleDirection,
    leftPanel,
    toggleLeftPanel,
    rightPanel,
    toggleRightPanel,
  } = useElkDirectionContext();
  const fullscreenCtx = useChainFullscreenContext();

  return (
    <div className={styles.container}>
      {showLeftPanelToggle && (
        <Button
          className={styles.button}
          type={"text"}
          title="Left Panel"
          data-active={leftPanel}
          onClick={toggleLeftPanel}
          icon={<OverridableIcon name="leftPanel" />}
        />
      )}
      <Button
        className={styles.button}
        type={"text"}
        title="Right Panel"
        data-active={rightPanel}
        onClick={toggleRightPanel}
        icon={<OverridableIcon name="rightPanel" />}
      />
      {fullscreenCtx && (
        <Button
          className={styles.button}
          type={"text"}
          title="Fullscreen"
          data-active={fullscreenCtx.fullscreen}
          onClick={fullscreenCtx.toggleFullscreen}
          icon={
            fullscreenCtx.fullscreen ? (
              <FullscreenExitOutlined />
            ) : (
              <FullscreenOutlined />
            )
          }
        />
      )}
      <Button
        className={styles.button}
        type={"text"}
        title="Zoom In"
        disabled={zoom >= maxZoom}
        onClick={() => void zoomIn()}
        icon={<OverridableIcon name="plus" />}
      />
      <Button
        className={styles.button}
        type={"text"}
        title="Zoom Out"
        disabled={zoom <= minZoom}
        onClick={() => void zoomOut()}
        icon={<OverridableIcon name="minus" />}
      />
      <Button
        className={styles.button}
        type={"text"}
        title="Fit View"
        onClick={() => void fitView()}
        icon={<OverridableIcon name="expand" />}
      />
      <Button
        className={styles.button}
        type={"text"}
        title="Change Layout Direction"
        onClick={toggleDirection}
        icon={<OverridableIcon name="rotateRight" />}
      />
      <Button
        className={styles.button}
        type={"text"}
        title="Expand All"
        onClick={onExpandAllContainers}
        icon={<OverridableIcon name="expandAll" />}
      />
      <Button
        className={styles.button}
        type={"text"}
        title="Collapse All"
        onClick={onCollapseAllContainers}
        icon={<OverridableIcon name="collapseAll" />}
      />
      {extraButtons ? (
        <>
          <span className={styles.extraDivider} />
          {extraButtons}
        </>
      ) : null}
    </div>
  );
};
