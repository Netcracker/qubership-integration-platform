import React, { useEffect, useRef, useState } from "react";
import { useModalContext } from "../../ModalContextProvider";
import { XmlNamespace } from "../../mapper/model/metadata";
import { Button, Flex, Modal } from "antd";
import { OverridableIcon } from "../../icons/IconProvider.tsx";
import {
  KeyValuePairsTable,
  KeyValuePairsTableRef,
} from "../table/kv/KeyValuePairsTable.tsx";
import { KeyValuePair } from "../../misc/key-value-text-util.ts";

export type NamespacesEditDialogProps = {
  namespaces: XmlNamespace[];
  onSubmit: (namespaces: XmlNamespace[]) => void;
};

export const NamespacesEditDialog: React.FC<NamespacesEditDialogProps> = ({
  namespaces,
  onSubmit,
}) => {
  const { closeContainingModal } = useModalContext();
  const [pairs, setPairs] = useState<KeyValuePair[]>([]);
  const tableRef = useRef<KeyValuePairsTableRef>(null);

  useEffect(() => {
    setPairs(namespaces.map(({ alias, uri }) => ({ key: alias, value: uri })));
  }, [namespaces]);

  return (
    <Modal
      title="Edit namespaces"
      open={true}
      onCancel={closeContainingModal}
      footer={[
        <Button key="cancel" onClick={closeContainingModal}>
          Cancel
        </Button>,
        <Button
          key="submit"
          type="primary"
          onClick={() => {
            onSubmit(
              pairs.map(({ key, value }) => ({ alias: key, uri: value })),
            );
            closeContainingModal();
          }}
        >
          Save
        </Button>,
      ]}
    >
      <div style={{ height: "60vh" }}>
        <KeyValuePairsTable
          value={pairs}
          onChange={setPairs}
          actionRef={tableRef}
          columnNames={{ key: "Prefix", value: "URI" }}
          header={
            <Flex wrap="wrap" vertical={false} gap={8}>
              <Button
                size="small"
                icon={<OverridableIcon name="plusCircle" />}
                onClick={() => tableRef.current?.add()}
              >
                Add rule
              </Button>
              <Button
                size="small"
                icon={<OverridableIcon name="delete" />}
                onClick={() => tableRef.current?.clear()}
              >
                Clear rules
              </Button>
            </Flex>
          }
        />
      </div>
    </Modal>
  );
};
