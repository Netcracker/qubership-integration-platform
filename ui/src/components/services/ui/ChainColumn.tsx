import React, { useState } from "react";
import { Dropdown, Button } from "antd";
import type { MenuProps } from "antd";
import {
  isVsCode,
  VSCodeExtensionApi,
} from "../../../api/rest/vscodeExtensionApi";
import { api } from "../../../api/api";
import { OverridableIcon } from "../../../icons/IconProvider.tsx";

export interface Chain {
  id: string;
  name: string;
}

interface ChainColumnProps {
  chains: Chain[];
}

export const ChainColumn: React.FC<ChainColumnProps> = ({ chains }) => {
  const [open, setOpen] = useState(false);

  if (!chains || chains.length === 0) {
    return (
      <div
        style={{
          color: "var(--vscode-descriptionForeground, rgba(0, 0, 0, 0.45))",
        }}
      >
        No chains
      </div>
    );
  }

  const handleMenuClick = (chainId: string) => {
    if (isVsCode) {
      if (api instanceof VSCodeExtensionApi) {
        void api.openChainInNewTab(chainId);
      }
    } else {
      window.open(`/chains/${chainId}/graph`, "_blank");
      setOpen(false);
    }
  };

  const items: MenuProps["items"] = chains.map((chain) => ({
    key: chain.id,
    label: chain.name,
    onClick: () => handleMenuClick(chain.id),
  }));

  return (
    <Dropdown
      menu={{ items }}
      trigger={["click"]}
      open={open}
      onOpenChange={setOpen}
    >
      <Button type="link">
        {chains.length > 0 ? (
          <>
            {chains.length} {chains.length === 1 ? "chain" : "chains"}{" "}
            <OverridableIcon name="down" />
          </>
        ) : (
          "No chains"
        )}
      </Button>
    </Dropdown>
  );
};
