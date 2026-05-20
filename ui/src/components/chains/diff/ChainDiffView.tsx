import { Flex, FlexProps } from "antd";
import { Chain } from "../../../api/apiTypes.ts";
import React, { useState } from "react";
import {
  ChainDiffViewControls,
  DiffViewType,
} from "./ChainDiffViewControls.tsx";
import { Change } from "./compare/types.ts";
import { ChainDiffTableView } from "./ChainDiffTableView.tsx";
import { ChainDiffGraphView } from "./ChainDiffGraphView.tsx";
import { ElementSchemasProvider } from "./ElementSchemasProvider.tsx";
import { ChainDiffTextView } from "./ChainDiffTextView.tsx";

export type ChainDiffViewProps = {
  chain1?: Chain;
  chain2?: Chain;
  changes: Change[];
  selectedChangeId?: string;
  onSelectChange: (id: string) => void;
} & FlexProps;

export const ChainDiffView: React.FC<ChainDiffViewProps> = ({
  chain1,
  chain2,
  changes,
  selectedChangeId,
  onSelectChange,
  ...rest
}): React.ReactNode => {
  const [viewType, setViewType] = useState<DiffViewType>("graph");
  return (
    <Flex {...rest} vertical>
      <ChainDiffViewControls
        changes={changes}
        selectedChangeId={selectedChangeId}
        onSelectChange={onSelectChange}
        onViewTypeChange={(viewType) => setViewType(viewType)}
      />
      <ElementSchemasProvider>
        {viewType === "graph" ? (
          <ChainDiffGraphView
            chain1={chain1}
            chain2={chain2}
            changes={changes}
            selectedChangeId={selectedChangeId}
            onSelectChange={onSelectChange}
          />
        ) : viewType === "table" ? (
          <ChainDiffTableView
            chain1={chain1}
            chain2={chain2}
            changes={changes}
            selectedChangeId={selectedChangeId}
            onSelectChange={onSelectChange}
          />
        ) : (
          <ChainDiffTextView
            chain1={chain1}
            chain2={chain2}
            changes={changes}
            selectedChangeId={selectedChangeId}
            onSelectChange={onSelectChange}
          />
        )}
      </ElementSchemasProvider>
    </Flex>
  );
};
