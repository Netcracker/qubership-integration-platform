import { Flex, Select, SelectProps } from "antd";
import { Chain } from "../../../api/apiTypes.ts";
import { LinkToChain } from "./ChangedEntityView.tsx";
import { isChainSnapshot } from "./ComparedItemTitle.tsx";
import React, { useCallback, useEffect, useState } from "react";
import { useNotificationService } from "../../../hooks/useNotificationService.tsx";
import { api } from "../../../api/api.ts";
import { ComparableItem } from "./useChainDiff.tsx";
import { isVsCode } from "../../../api/rest/vscodeExtensionApi.ts";

export type ComparedItemSelectorProps = {
  chain?: Chain;
  editable: boolean;
  onChange?: (item: ComparableItem) => void;
  imported?: boolean;
};

export const ComparedItemSelector: React.FC<ComparedItemSelectorProps> = ({
  chain,
  editable,
  onChange,
  imported,
}): React.ReactNode => {
  const [options, setOptions] = useState<SelectProps["options"]>([]);
  const [loading, setLoading] = useState<boolean>(false);
  const notificationService = useNotificationService();

  const updateOptions = useCallback(async () => {
    if (!chain) {
      setOptions([]);
      return;
    }
    setLoading(true);
    try {
      const chainId = isChainSnapshot(chain) ? chain.chain.id : chain.id;
      const snapshots = await api.getSnapshots(chainId);
      setOptions([
        ...snapshots.map((snapshot) => ({
          label: snapshot.name,
          value: snapshot.id,
        })),
        { value: "", label: "Current" },
      ]);
    } catch (error) {
      notificationService.requestFailed("Failed to get snapshots", error);
    } finally {
      setLoading(false);
    }
  }, [chain, notificationService]);

  useEffect(() => {
    if (!isVsCode) {
      void updateOptions();
    }
  }, [updateOptions]);
  return (
    <Flex gap={8} vertical={false} align={"center"} justify={"space-between"}>
      <LinkToChain chain={chain} />
      {chain && !isVsCode ? (
        <Select
          style={{ width: 100 }}
          loading={loading}
          options={options}
          disabled={!editable}
          defaultValue={
            imported ? "Imported" : isChainSnapshot(chain) ? chain.id : ""
          }
          onChange={(value: string) =>
            onChange?.(
              value
                ? { kind: "snapshot", id: value }
                : {
                    kind: "chain",
                    id: isChainSnapshot(chain) ? chain.chain.id : chain.id,
                  },
            )
          }
        />
      ) : null}
    </Flex>
  );
};
