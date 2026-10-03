import React, {
  PropsWithChildren,
  ReactNode,
  useCallback,
  useEffect,
  useRef,
  useState,
} from "react";
import { Flex, Space, Typography } from "antd";
import {
  TableToolbar,
  type TableToolbarRefresh,
} from "../table/TableToolbar.tsx";
import { ProtectedButton } from "../../permissions/ProtectedButton.tsx";
import { CreateServiceModal } from "./modals/CreateServiceModal.tsx";
import { useModalsContext } from "../../Modals.tsx";
import { IntegrationSystemType } from "../../api/apiTypes.ts";
import ImportServicesModal from "./modals/ImportServicesModal.tsx";

type GenericServiceListPageProps = PropsWithChildren & {
  refresh?: TableToolbarRefresh;
  title: string;
  icon: ReactNode;
  extraActions: ReactNode[];
  serviceType: IntegrationSystemType;
  onSearch: (searchString: string) => void;
  onExport: () => void;
  onImport: () => void;
  onCreate: (
    name: string,
    description: string,
    properties: Record<string, string>,
  ) => Promise<void>;
};

function getSystemTypeName(systemType: IntegrationSystemType): string {
  return systemType === IntegrationSystemType.MCP
    ? systemType
    : systemType.toLowerCase();
}

export const GenericServiceListPage: React.FC<GenericServiceListPageProps> = ({
  title,
  refresh,
  icon,
  extraActions,
  serviceType,
  children,
  onSearch,
  onExport,
  onImport,
  onCreate,
}): ReactNode => {
  const { showModal } = useModalsContext();
  const [searchString, setSearchString] = useState("");
  const [debouncedSearch, setDebouncedSearch] = useState("");
  const searchDebounceRef = useRef<ReturnType<typeof setTimeout>>();

  useEffect(() => {
    onSearch(debouncedSearch);
  }, [debouncedSearch, onSearch]);

  const handleSearchChange = useCallback((value: string) => {
    setSearchString(value);
    clearTimeout(searchDebounceRef.current);
    if (value.trim() === "") {
      setDebouncedSearch("");
    } else {
      searchDebounceRef.current = setTimeout(() => {
        setDebouncedSearch(value);
      }, 500);
    }
  }, []);

  return (
    <Flex vertical gap={8} style={{ height: "100%" }}>
      <Flex vertical={false} align={"center"} justify={"space-between"}>
        <Flex vertical={false} gap={8} flex={1} align={"center"}>
          <Typography.Title level={4} style={{ marginTop: 0 }}>
            <Space size={"large"}>
              {icon}
              {title}
            </Space>
          </Typography.Title>
        </Flex>
        <TableToolbar
          variant="admin"
          search={{
            value: searchString,
            onChange: handleSearchChange,
            placeholder: "Search services...",
            allowClear: true,
            onSearchConfirm: (value) => {
              clearTimeout(searchDebounceRef.current);
              setDebouncedSearch(value);
            },
          }}
          refresh={refresh}
          actions={
            <>
              {extraActions.map((extraAction, index) => (
                <React.Fragment key={index}>{extraAction}</React.Fragment>
              ))}
              <ProtectedButton
                require={{ service: ["export"] }}
                tooltipProps={{
                  title: "Download selected services",
                  placement: "bottom",
                }}
                buttonProps={{
                  iconName: "cloudDownload",
                  onClick: () => onExport(),
                }}
              />
              <ProtectedButton
                require={{ service: ["import"] }}
                tooltipProps={{ title: "Upload services", placement: "bottom" }}
                buttonProps={{
                  iconName: "cloudUpload",
                  onClick: () => {
                    showModal({
                      component: (
                        <ImportServicesModal
                          onSuccess={() => onImport()}
                          systemType={serviceType}
                        />
                      ),
                    });
                  },
                }}
              />
              <ProtectedButton
                require={{ service: ["create"] }}
                tooltipProps={{ title: "Create service", placement: "bottom" }}
                buttonProps={{
                  type: "primary",
                  iconName: "plus",
                  onClick: () => {
                    showModal({
                      component: (
                        <CreateServiceModal
                          serviceType={serviceType}
                          defaultName={`New ${getSystemTypeName(serviceType)} service`}
                          onSubmit={onCreate}
                        />
                      ),
                    });
                  },
                }}
              />
            </>
          }
        />
      </Flex>
      {children}
    </Flex>
  );
};
