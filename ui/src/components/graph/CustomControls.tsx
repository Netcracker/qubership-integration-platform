import { useReactFlow } from "@xyflow/react";
import {
  PlusOutlined,
  MinusOutlined,
  ExpandOutlined,
  RotateRightOutlined,
} from "@ant-design/icons";

import styles from "./CustomControls.module.css";
import { useElkDirectionContext } from "../../pages/ElkDirectionContext.tsx";

export const CustomControls = () => {
  const { zoomIn, zoomOut, fitView } = useReactFlow();
  const { onChangeDirection } = useElkDirectionContext();

  return (
    <div className={styles.container}>
      <button
        onClick={() => zoomIn()}
        title="Zoom In"
        className={styles.button}
      >
        {/*TODO deactivate button if zoomed in on maximum value*/}
        <PlusOutlined />
      </button>
      <button
        onClick={() => zoomOut()}
        title="Zoom Out"
        className={styles.button}
      >
        {/*TODO deactivate button if zoomed out on maximum value*/}
        <MinusOutlined />
      </button>
      <button
        onClick={() => fitView()}
        title="Fit View"
        className={styles.button}
      >
        <ExpandOutlined />
      </button>
      <button
        onClick={onChangeDirection}
        title="Change Layout Direction"
        className={styles.button}
      >
        <RotateRightOutlined />
      </button>
    </div>
  );
};
