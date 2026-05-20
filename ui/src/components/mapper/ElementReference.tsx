import {
  AttributeReference,
  ConstantReference,
  MappingDescription,
} from "../../mapper/model/model.ts";
import { Tag, Tooltip } from "antd";
import React, { useEffect, useState } from "react";
import { MappingActions } from "../../mapper/util/actions.ts";
import { capitalize } from "../../misc/format-utils.ts";
import styles from "./ElementReference.module.css";

export type ElementReferenceProps = {
  mapping: MappingDescription;
  reference: ConstantReference | AttributeReference;
  isTarget: boolean;
  closable: boolean;
  onClose?: () => void;
};

export const ElementReference: React.FC<ElementReferenceProps> = ({
  mapping,
  reference,
  isTarget,
  closable,
  onClose,
}) => {
  const [name, setName] = useState<string>("");
  const [tooltipText, setTooltipText] = useState<string>("");
  const [type, setType] = useState<string>("");

  useEffect(() => {
    const result = MappingActions.resolveReference(
      reference,
      isTarget,
      mapping,
    );
    if (result) {
      if ("path" in result) {
        setType(result.kind[0].toUpperCase());
        setTooltipText(
          `${capitalize(result.kind)} ${result.path.map((a) => a.name).join(".")}`,
        );
        setName(result.path.slice(-1).pop()?.name ?? "");
      } else {
        setType("C");
        setTooltipText(`Constant ${result.name}`);
        setName(result.name);
      }
    } else {
      setType("");
      setTooltipText("");
      setName("");
    }
  }, [mapping, reference, isTarget]);

  return (
    <Tooltip title={tooltipText}>
      <Tag
        className={styles["element-reference"]}
        closable={closable}
        onClose={(e: React.MouseEvent<HTMLElement>) => {
          e.preventDefault();
          onClose?.();
        }}
      >
        <span className={styles["element-type"]}>{type}</span>
        <span className={styles["element-name"]}>{name}</span>
      </Tag>
    </Tooltip>
  );
};
