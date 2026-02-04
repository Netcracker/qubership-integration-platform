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
      <div key={"title"} className={styles["title"]}>
        {transformationInfo.title}
      </div>
      <div key={"parameters"} className={styles["parameters"]}>
        {bindParameterValues(transformationInfo.parameters, parameters).flatMap(
          ([info, values], idx) => [
            <div key={idx}>{info.name}</div>,
            <div
              key={`parameter-values-${idx}`}
              className={styles["parameter-values"]}
            >
              {values.map((value, index) => (
                <div key={index}>{formatOptional(value)}</div>
              ))}
            </div>,
          ],
        )}
      </div>
    </div>
  );
};
