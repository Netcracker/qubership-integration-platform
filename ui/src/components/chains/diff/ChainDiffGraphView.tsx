import React, { useEffect, useMemo, useState } from "react";
import { Chain } from "../../../api/apiTypes.ts";
import { Change } from "./compare/types.ts";
import { Col, Flex, Row, Tag } from "antd";
import { ChangedEntityView } from "./ChangedEntityView.tsx";
import { ChainGraphPanel } from "./ChainGraphPanel.tsx";
import styles from "./ChainDiffGraphView.module.css";
import { ChainGraphChangeProvider } from "./ChainGraphChangeProvider.tsx";
import { buildElementMap } from "./compare/compare.ts";
import { ComparedItemTitle } from "./ComparedItemTitle.tsx";

export function getElementId(
  change: Change | undefined,
  key: "one" | "another",
): string | undefined {
  switch (change?.kind) {
    case "element-property":
      return change[key]?.entityId;
    case "connection":
      return change[key]?.from;
    case "element":
      return change[key]?.id;
    default:
      return undefined;
  }
}

export type ChainDiffGraphViewProps = {
  chain1?: Chain;
  chain2?: Chain;
  changes: Change[];
  selectedChangeId?: string;
  onSelectChange: (id: string) => void;
};

export const ChainDiffGraphView: React.FC<ChainDiffGraphViewProps> = ({
  chain1,
  chain2,
  changes,
  selectedChangeId,
}): React.ReactNode => {
  const [selectedChange, setSelectedChange] = useState<Change | undefined>(
    undefined,
  );

  useEffect(() => {
    setSelectedChange(changes.find((c) => c.id === selectedChangeId));
  }, [changes, selectedChangeId]);

  const elementMap = useMemo(() => {
    return chain1 && chain2
      ? buildElementMap(chain1, chain2)
      : new Map<string, string>();
  }, [chain1, chain2]);

  return (
    <Flex
      vertical
      gap={16}
      style={{ minHeight: 0, flexGrow: 1, flexShrink: 1 }}
    >
      <Row gutter={16}>
        <Col span={12}>
          <ComparedItemTitle chain={chain1} />
        </Col>
        <Col span={12}>
          <ComparedItemTitle chain={chain2} />
        </Col>
      </Row>
      <Row gutter={16}>
        <Col span={12}>
          {selectedChange && chain1 ? (
            <ChangedEntityView
              side="one"
              change={selectedChange}
              chain={chain1}
            />
          ) : null}
        </Col>
        <Col span={12}>
          {selectedChange && chain2 ? (
            <ChangedEntityView
              side="another"
              change={selectedChange}
              chain={chain2}
            />
          ) : null}
        </Col>
      </Row>
      <Row gutter={16} style={{ minHeight: 0, flexGrow: 1, flexShrink: 0 }}>
        <Col span={12}>
          {chain1 ? (
            <ChainGraphChangeProvider
              chain={chain1}
              changes={changes}
              elementMap={elementMap}
              side={"one"}
            >
              <ChainGraphPanel
                chain={chain1}
                className={styles["left-view"]}
                selectedElementId={getElementId(selectedChange, "one")}
              />
            </ChainGraphChangeProvider>
          ) : null}
        </Col>
        <Col span={12}>
          {chain2 ? (
            <ChainGraphChangeProvider
              chain={chain2}
              changes={changes}
              elementMap={elementMap}
              side={"another"}
            >
              <ChainGraphPanel
                chain={chain2}
                className={styles["right-view"]}
                selectedElementId={getElementId(selectedChange, "another")}
              />
            </ChainGraphChangeProvider>
          ) : null}
        </Col>
      </Row>
      <Row>
        <Col span={24}>
          <Tag color={"var(--node-not-changed-color, #727272)"}>Identical</Tag>
          <Tag color={"var(--node-changed-color, #ffcc02)"}>Changed</Tag>
          <Tag color={"var(--node-removed-color, #f48771)"}>Removed</Tag>
          <Tag color={"var(--node-created-color, #4ec9b0)"}>Created</Tag>
        </Col>
      </Row>
    </Flex>
  );
};
