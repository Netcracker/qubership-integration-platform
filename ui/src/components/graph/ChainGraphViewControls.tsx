import type React from "react";
import { useReactFlow, useStore, useViewport } from "@xyflow/react";

import styles from "./ChainGraphViewControls.module.css";
import { useElkDirectionContext } from "../../pages/ElkDirectionContext.tsx";
import { useChainFullscreenContext } from "../../pages/ChainFullscreenContext.tsx";
import { Button } from "antd";
import { OverridableIcon } from "../../icons/IconProvider.tsx";

export type ChainGraphViewControlsProps = {
  before?: React.ReactNode;
  after?: React.ReactNode;
  onExpandAllContainers?: () => void;
  onCollapseAllContainers?: () => void;
  onExportImage?: () => void;
  exporting?: boolean;
};

export const ChainGraphViewControls = ({
  before,
  after,
  onExpandAllContainers,
  onCollapseAllContainers,
  onExportImage,
  exporting,
}: ChainGraphViewControlsProps) => {
  const { zoomIn, zoomOut, fitView } = useReactFlow();
  const { zoom } = useViewport();
  const { minZoom, maxZoom } = useStore((s) => ({
    minZoom: s.minZoom,
    maxZoom: s.maxZoom,
  }));
  const { toggleDirection } = useElkDirectionContext();
  const fullscreenCtx = useChainFullscreenContext();

  return (
    <div className={styles.container}>
      {before ? (
        <>
          {before}
          <span className={styles.extraDivider} />
        </>
      ) : null}
      {fullscreenCtx && (
        <Button
          className={styles.button}
          type={"text"}
          title="Fullscreen"
          data-active={fullscreenCtx.fullscreen}
          onClick={fullscreenCtx.toggleFullscreen}
          icon={
            <OverridableIcon
              name={fullscreenCtx.fullscreen ? "fullscreenExit" : "fullscreen"}
            />
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
      {onExportImage && (
        <Button
          className={styles.button}
          type={"text"}
          title="Export image"
          loading={exporting}
          disabled={exporting}
          onClick={onExportImage}
          icon={<OverridableIcon name="export" />}
        />
      )}
      {after ? (
        <>
          <span className={styles.extraDivider} />
          {after}
        </>
      ) : null}
    </div>
  );
};
