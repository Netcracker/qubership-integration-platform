import { ValueSupplier } from "../../mapper/model/model.ts";
import { Tag, Tooltip } from "antd";
import React, { useEffect, useState } from "react";
import styles from "./ConstantValue.module.css";
import { GENERATORS } from "../../mapper/model/generators.ts";
import { TransformationInfo } from "../../mapper/model/transformations.ts";
import { TransformationInfoCard } from "./TransformationInfo.tsx";

export type ConstantValueProps = React.HTMLAttributes<HTMLDivElement> & {
  valueSupplier: ValueSupplier;
};

export const ConstantValue: React.FC<ConstantValueProps> = ({
  valueSupplier,
  ...props
}) => {
  const [transformationInfo, setTransformationInfo] = useState<
    TransformationInfo | undefined
  >(undefined);

  useEffect(() => {
    setTransformationInfo(
      valueSupplier.kind === "generated"
        ? GENERATORS.find((g) => g.name === valueSupplier.generator.name)
        : undefined,
    );
  }, [valueSupplier]);

  return (
    <div className={styles["constant-value-content"]} {...props}>
      {valueSupplier.kind === "given" ? (
        <div className={styles["constant-value"]}>{valueSupplier.value}</div>
      ) : (
        <>
          <Tag className={styles["constant-generator-tag"]}>G</Tag>
          <Tooltip
            title={
              transformationInfo ? (
                <TransformationInfoCard
                  transformationInfo={transformationInfo}
                  parameters={valueSupplier.generator.parameters}
                />
              ) : (
                ""
              )
            }
          >
            <div className={styles["constant-generator-details"]}>
              <span className={styles["constant-generator-title"]}>
                {transformationInfo?.title ?? valueSupplier.generator.name}
              </span>
              <div className={styles["constant-generator-parameters"]}>
                {valueSupplier.generator.parameters.map((parameter, index) => (
                  <span
                    className={styles["constant-generator-parameter-value"]}
                    key={index}
                  >
                    {parameter}
                  </span>
                ))}
              </div>
            </div>
          </Tooltip>
        </>
      )}
    </div>
  );
};
