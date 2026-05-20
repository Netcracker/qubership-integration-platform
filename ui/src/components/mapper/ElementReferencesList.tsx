import {
  AttributeReference,
  Constant,
  ConstantReference,
  MappingDescription,
} from "../../mapper/model/model.ts";
import { Col, Row } from "antd";
import React from "react";
import { MappingActions } from "../../mapper/util/actions.ts";
import {
  AttributeDetail,
  isAttributeDetail,
} from "../../mapper/util/schema.ts";

export type ElementReferencesListProps = {
  references: (ConstantReference | AttributeReference)[];
  isTarget: boolean;
  mappingDescription: MappingDescription;
};

function getKindLabel(reference: AttributeDetail | Constant): string {
  return isAttributeDetail(reference) ? reference.kind : "constant";
}

export const ElementReferencesList: React.FC<ElementReferencesListProps> = ({
  references,
  isTarget,
  mappingDescription,
}) => {
  return references
    .map((reference) =>
      MappingActions.resolveReference(reference, isTarget, mappingDescription),
    )
    .filter((res) => !!res)
    .sort((res1, res2) => {
      const s1 = getKindLabel(res1);
      const s2 = getKindLabel(res2);
      return s1.localeCompare(s2);
    })
    .map((res, index) => {
      return (
        <Row key={index} wrap={false} gutter={[16, 8]} justify={"start"}>
          <Col key={"kind"}>
            {isAttributeDetail(res) ? res.kind : "constant"}
          </Col>
          <Col key={"path"}>
            {isAttributeDetail(res)
              ? res.path.map((a) => a.name).join(".")
              : res.name}
          </Col>
        </Row>
      );
    });
};
