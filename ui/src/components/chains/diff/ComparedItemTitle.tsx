import { Chain, ChainSnapshot } from "../../../api/apiTypes.ts";
import React from "react";
import { LinkToChain } from "./ChangedEntityView.tsx";
import { Space } from "antd";

export type ComparedItemTitleProps = {
  chain: Chain | undefined;
};

function isChainSnapshot(chain: object): chain is ChainSnapshot {
  return "chain" in chain;
}

export const ComparedItemTitle: React.FC<ComparedItemTitleProps> = ({
  chain,
}): React.ReactNode => {
  return chain && isChainSnapshot(chain) ? (
    <Space>
      <LinkToChain chain={chain.chain} />
      {`(${chain.name})`}
    </Space>
  ) : (
    <LinkToChain chain={chain} />
  );
};
