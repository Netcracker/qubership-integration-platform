import {
  bindParameterValues,
  TransformationInfo,
} from "../../mapper/model/transformations.ts";
import React, { HTMLAttributes } from "react";
import styles from "./TransformationInfo.module.css";
import { formatOptional } from "../../misc/format-utils.ts";

export type TransformationInfoCardProps = HTMLAttributes<HTMLDivElement> & {
  transformationInfo: TransformationInfo;
  parameters: string[];
};

export const TransformationInfoCard: React.FC<TransformationInfoCardProps> = ({
  transformationInfo,
  parameters,
  ...props
}) => {
  return (
    <div className={styles["content"]} {...props}>
      <div className={styles["title"]}>{transformationInfo.title}</div>
      <div className={styles["parameters"]}>
        {bindParameterValues(transformationInfo.parameters, parameters).map(
          ([info, values]) => (
            <>
              <div className={styles["parameter-name"]}>{info.name}</div>
              <div className={styles["parameter-values"]}>
                {values.map((value, index) => (
                  <div className={styles["parameter-value"]} key={index}>
                    {formatOptional(value)}
                  </div>
                ))}
              </div>
            </>
          ),
        )}
      </div>
    </div>
  );
};
