import { useReactFlow } from "@xyflow/react";
import {
  PlusOutlined,
  MinusOutlined,
  ExpandOutlined,
  RotateRightOutlined,
} from "@ant-design/icons";

import styles from "./CustomControls.module.css";
import { useElkDirectionContext } from "../../pages/ElkDirectionContext.tsx";
import { Button } from "antd";

export const CustomControls = () => {
  const { zoomIn, zoomOut, fitView } = useReactFlow();
  const { toggleDirection } = useElkDirectionContext();

  return (
    <div className={styles.container}>
      <Button
        className={styles.button}
        // TODO disable button if zoomed in on maximum value
        type={"text"}
        title="Zoom In"
        onClick={() => void zoomIn()}
        icon={<PlusOutlined />}
      />
      <Button
        className={styles.button}
        // TODO deactivate button if zoomed out on maximum value
        type={"text"}
        title="Zoom Out"
        onClick={() => void zoomOut()}
        icon={<MinusOutlined />}
      />
      <Button
        className={styles.button}
        type={"text"}
        title="Fit View"
        onClick={() => void fitView()}
        icon={<ExpandOutlined />}
      />
      <Button
        className={styles.button}
        type={"text"}
        title="Change Layout Direction"
        onClick={toggleDirection}
        icon={<RotateRightOutlined />}
      />
    </div>
  );
};
