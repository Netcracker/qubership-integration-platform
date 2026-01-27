import { Transformation } from "../../mapper/model/model.ts";
import { Flex, Tooltip } from "antd";
import React, { useEffect, useState } from "react";
import {
  TransformationInfo,
  TRANSFORMATIONS,
} from "../../mapper/model/transformations.ts";
import styles from "./TransformationValue.module.css";
import { VerificationError } from "../../mapper/verification/model.ts";
import { PLACEHOLDER } from "../../misc/format-utils.ts";
import { TransformationInfoTooltip } from "./TransformationInfoTooltip.tsx";
import { OverridableIcon } from "../../icons/IconProvider.tsx";

export type TransformationValueProps = {
  transformation?: Transformation;
  errors: VerificationError[];
};

export const TransformationValue: React.FC<TransformationValueProps> = ({
  transformation,
  errors,
}) => {
  const [transformationInfo, setTransformationInfo] = useState<
    TransformationInfo | undefined
  >(undefined);

  useEffect(() => {
    setTransformationInfo(
      TRANSFORMATIONS.find((g) => g.name === transformation?.name),
    );
  }, [transformation]);

  return (
    <div className={styles["transformation-content"]}>
      {transformation ? (
        <TransformationInfoTooltip
          transformation={transformation}
        >
          <div className={styles["transformation-details"]}>
            <span className={styles["transformation-title"]}>
              {transformationInfo?.title ?? transformation.name}
            </span>
            <div className={styles["transformation-parameters"]}>
              {transformation.parameters.map((parameter, index) => (
                <span
                  className={styles["transformation-parameter-value"]}
                  key={index}
                >
                  {parameter}
                </span>
              ))}
            </div>
          </div>
        </TransformationInfoTooltip>
      ) : (
        PLACEHOLDER
      )}
      {errors.length > 0 ? (
        <Tooltip
          title={
            <Flex vertical gap={8}>
              {errors.map((error, index) => (
                <div key={index}>
                  {error.message}
                </div>
              ))}
            </Flex>
          }
        >
          <OverridableIcon name="exclamationCircle" style={{ color: "red" }} />
        </Tooltip>
      ) : (
        <></>
      )}
    </div>
  );
};
