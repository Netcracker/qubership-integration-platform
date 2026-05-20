import {
  TransformationInfo,
  TRANSFORMATIONS,
} from "../../mapper/model/transformations.ts";
import React, { PropsWithChildren, useEffect, useState } from "react";
import { TransformationInfoCard } from "./TransformationInfo.tsx";
import { Transformation } from "../../mapper/model/model.ts";
import { Tooltip } from "antd";

export type TransformationInfoTooltipProps = {
  transformation?: Transformation;
  transformationsInfo?: TransformationInfo[];
};

export const TransformationInfoTooltip: React.FC<
  PropsWithChildren<TransformationInfoTooltipProps>
> = ({ transformation, transformationsInfo, children }) => {
  const [transformationInfo, setTransformationInfo] = useState<
    TransformationInfo | undefined
  >(undefined);

  useEffect(() => {
    setTransformationInfo(
      (transformationsInfo ?? TRANSFORMATIONS).find(
        (info) => info.name === transformation?.name,
      ),
    );
  }, [transformation, transformationsInfo]);
  return (
    <Tooltip
      title={
        transformationInfo ? (
          <TransformationInfoCard
            transformationInfo={transformationInfo}
            parameters={transformation?.parameters ?? []}
          />
        ) : (
          ""
        )
      }
    >
      {children}
    </Tooltip>
  );
};
