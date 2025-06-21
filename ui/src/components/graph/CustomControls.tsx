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
  const { onChangeDirection } = useElkDirectionContext();

  return (
    <div className={styles.container}>
      <Button
        className={styles.button}
        // TODO disable button if zoomed in on maximum value
        type={"text"}
        title="Zoom In"
        onClick={() => zoomIn()}
        icon={<PlusOutlined />}
      />
      <Button
        className={styles.button}
        // TODO deactivate button if zoomed out on maximum value
        type={"text"}
        title="Zoom Out"
        onClick={() => zoomOut()}
        icon={<MinusOutlined />}
      />
      <Button
        className={styles.button}
        type={"text"}
        title="Fit View"
        onClick={() => fitView()}
        icon={<ExpandOutlined />}
      />
      <Button
        className={styles.button}
        type={"text"}
        title="Change Layout Direction"
        onClick={onChangeDirection}
        icon={<RotateRightOutlined />}
      />
    </div>
  );
};
