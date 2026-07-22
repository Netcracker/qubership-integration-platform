import { Col, Flex, FlexProps, Row, Spin } from "antd";
import React, { useState } from "react";
import {
  ChainDiffViewControls,
  DiffViewType,
} from "./ChainDiffViewControls.tsx";
import { ChainDiffTableView } from "./ChainDiffTableView.tsx";
import { ChainDiffGraphView } from "./ChainDiffGraphView.tsx";
import { ElementSchemasProvider } from "./ElementSchemasProvider.tsx";
import { ChainDiffTextView } from "./ChainDiffTextView.tsx";
import { ComparedItemSelector } from "./ComparedItemSelector.tsx";
import { ComparableItem, useChainDiff } from "./useChainDiff.tsx";
import styles from "./ChainDiffView.module.css";
import { DiffDocumentContextProvider } from "./DiffDocumentContext.tsx";

export type ChainDiffViewProps = {
  item1: ComparableItem;
  item2: ComparableItem;
  editable1?: boolean;
  editable2?: boolean;
} & FlexProps;

export const ChainDiffView: React.FC<ChainDiffViewProps> = ({
  item1,
  item2,
  editable1,
  editable2,
  ...rest
}): React.ReactNode => {
  const [i1, setI1] = useState<ComparableItem>(item1);
  const [i2, setI2] = useState<ComparableItem>(item2);
  const {
    isLoading,
    chain1,
    chain2,
    changes,
    selectedChangeId,
    setSelectedChangeId,
  } = useChainDiff(i1, i2);

  const [viewType, setViewType] = useState<DiffViewType>("graph");

  return isLoading ? (
    <Spin className={styles.loader} size={"large"}></Spin>
  ) : (
    <Flex {...rest} vertical gap={8}>
      <Row gutter={16} align="middle">
        <Col span={12}>
          <DiffDocumentContextProvider type={"left"}>
            <ComparedItemSelector
              chain={chain1}
              editable={editable1 ?? false}
              onChange={(item) => setI1(item)}
            />
          </DiffDocumentContextProvider>
        </Col>
        <Col span={12}>
          <DiffDocumentContextProvider type={"right"}>
            <ComparedItemSelector
              chain={chain2}
              editable={editable2 ?? false}
              onChange={(item) => setI2(item)}
              imported={i2.kind === "archive"}
            />
          </DiffDocumentContextProvider>
        </Col>
      </Row>
      <ChainDiffViewControls
        changes={changes}
        selectedChangeId={selectedChangeId}
        onSelectChange={setSelectedChangeId}
        onViewTypeChange={(viewType) => setViewType(viewType)}
      />
      <ElementSchemasProvider>
        <div className={styles.viewContent}>
          {viewType === "graph" ? (
            <ChainDiffGraphView
              chain1={chain1}
              chain2={chain2}
              changes={changes}
              selectedChangeId={selectedChangeId}
              onSelectChange={setSelectedChangeId}
            />
          ) : viewType === "table" ? (
            <ChainDiffTableView
              chain1={chain1}
              chain2={chain2}
              changes={changes}
              selectedChangeId={selectedChangeId}
              onSelectChange={setSelectedChangeId}
            />
          ) : (
            <ChainDiffTextView
              chain1={chain1}
              chain2={chain2}
              changes={changes}
              selectedChangeId={selectedChangeId}
              onSelectChange={setSelectedChangeId}
            />
          )}
        </div>
      </ElementSchemasProvider>
    </Flex>
  );
};
