import {
  Button,
  Flex,
  Modal,
  Table,
  Tag,
  Typography,
} from "antd";
import { useNotificationService } from "../../../hooks/useNotificationService";
import { OverridableIcon } from "../../../icons/IconProvider";
import commonStyles from "../CommonStyle.module.css";
import React, { useEffect, useState } from "react";
import { api } from "../../../api/api";
import { DetailedDesignTemplate } from "../../../api/apiTypes";
import { formatTimestamp } from "../../../misc/format-utils";
import { useModalsContext } from "../../../Modals";
import { CreateDesignTemplateModal } from "./CreateDesignTemplateModal";
import { TableRowSelection } from "antd/es/table/interface";
import { ProtectedButton } from "../../../permissions/ProtectedButton.tsx";
import { useColumnSettingsBasedOnColumnsType } from "../../table/useColumnSettingsButton";
import { ColumnsType } from "antd/lib/table";

const { Title } = Typography;

export const DesignTemplates: React.FC = () => {
  const { showModal } = useModalsContext();
  const notificationService = useNotificationService();
  const [isLoading, setIsLoading] = useState(false);
  const [tableData, setTableData] = useState<DetailedDesignTemplate[]>([]);
  const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]);

  const columns: ColumnsType<DetailedDesignTemplate> = [
    {
      title: "Name",
      dataIndex: "name",
      key: "name",
      sorter: (a, b) => a.name.localeCompare(b.name),
      defaultSortOrder: "ascend",
    },
    {
      title: "Type",
      dataIndex: "type",
      key: "type",
      render: (_, template: DetailedDesignTemplate) => {
        const templateType = template.builtIn ? "Built-in" : "Custom";
        return (
          <Tag {...(!template.builtIn && { color: "blue" })}>
            {templateType}
          </Tag>
        );
      },
      sorter: (a, b) => {
        if (a.builtIn === b.builtIn) {
          return 0;
        } else if (a.builtIn === false && b.builtIn === true) {
          return -1;
        } else {
          return 1;
        }
      },
    },
    {
      title: "Created When",
      dataIndex: "createdWhen",
      key: "createdWhen",
      width: 180,
      hidden: true,
      render: (_, template) => formatTimestamp(template.createdWhen ?? 0),
      sorter: (a, b) => (a.createdWhen ?? 0) - (b.createdWhen ?? 0),
    },
  ];
  const { orderedColumns, columnSettingsButton } =
    useColumnSettingsBasedOnColumnsType<DetailedDesignTemplate>(
      "designTemplatesTable",
      columns,
    );

  const onSelectChange = (newSelectedRowKeys: React.Key[]) => {
    setSelectedRowKeys(newSelectedRowKeys);
  };

  const rowSelection: TableRowSelection<DetailedDesignTemplate> = {
    type: "checkbox",
    selectedRowKeys,
    checkStrictly: false,
    onChange: onSelectChange,
  };

  useEffect(() => {
    void loadTemplates();
  }, []);

  const clearSelectedRowKeys = () => {
    setSelectedRowKeys([]);
  };

  const isDeleteEnabled = (): boolean => {
    let result = true;
    if (selectedRowKeys.length === 0) {
      result = false;
    } else {
      for (const row of tableData) {
        if (selectedRowKeys.includes(row.id) && row.builtIn) {
          result = false;
          break;
        }
      }
    }

    return result;
  };

  const loadTemplates = async () => {
    setIsLoading(true);
    try {
      const templates: DetailedDesignTemplate[] =
        await api.getDetailedDesignTemplates(false);
      setTableData(templates);
    } catch (error) {
      notificationService.requestFailed(
        "Error while loading design templates",
        error,
      );
    } finally {
      setIsLoading(false);
    }
  };

  const handleTemplateCreated = (template: DetailedDesignTemplate) => {
    setTableData((prev) => [
      ...prev.filter((row) => row.id !== template.id),
      template,
    ]);
  };

  const handleCreate = () => {
    showModal({
      component: (
        <CreateDesignTemplateModal onTemplateCreated={handleTemplateCreated} />
      ),
    });
  };

  const handleDelete = async () => {
    setIsLoading(true);
    try {
      const templateIds = selectedRowKeys.map((k) => k.toString());
      await api.deleteDetailedDesignTemplates(templateIds);
      setTableData((prev) =>
        prev.filter((item) => !templateIds.includes(item.id)),
      );
      clearSelectedRowKeys();
    } catch (error) {
      notificationService.requestFailed("Failed to delete templates", error);
    } finally {
      setIsLoading(false);
    }
  };

  const downloadResource = (text: string, filename: string): void => {
    const blob = new Blob([text]);

    const element = document.createElement("a");
    element.href = URL.createObjectURL(blob);
    element.download = filename;
    document.body.appendChild(element);
    element.click();
    element.remove();
  };

  const handleExport = async () => {
    try {
      const template: DetailedDesignTemplate =
        await api.getDetailedDesignTemplate(selectedRowKeys[0].toString());

      if (template.content) {
        downloadResource(template.content, template.name + ".ftl");
      }
      clearSelectedRowKeys();
    } catch (error) {
      notificationService.requestFailed("Failed to export template", error);
    }
  };

  return (
    <Flex vertical className={commonStyles["container"]}>
      <Flex className={commonStyles["header"]}>
        <Title level={4} className={commonStyles["title"]}>
          <OverridableIcon name="fileText" className={commonStyles["icon"]} />
          Design Templates
        </Title>
        <Flex
          vertical={false}
          gap={8}
          className={commonStyles["actions"]}
          align={"center"}
        >
          <ProtectedButton
            require={{ designTemplate: ["delete"] }}
            tooltipProps={{
              title: "Delete selected templates",
              placement: "bottom",
            }}
            buttonProps={{
              disabled: !isDeleteEnabled(),
              iconName: "delete",
              onClick: () => {
                Modal.confirm({
                  title: `Delete template?`,
                  content: `Are you sure you want to permanently delete selected templates?`,
                  onOk: () => void handleDelete(),
                });
              },
            }}
          />
          {columnSettingsButton}
          <ProtectedButton
            require={{ designTemplate: ["export"] }}
            tooltipProps={{
              title: "Export selected template",
              placement: "bottom",
            }}
            buttonProps={{
              disabled: selectedRowKeys.length !== 1,
              iconName: "cloudDownload",
              onClick: () => void handleExport(),
            }}
          />
          <ProtectedButton
            require={{ designTemplate: ["create"] }}
            tooltipProps={{ title: "Create template", placement: "bottom" }}
            buttonProps={{
              type: "primary",
              iconName: "plus",
              onClick: handleCreate,
            }}
          />
        </Flex>
      </Flex>
      <Flex
        style={{
          flex: "1 1 auto",
          minHeight: 0,
          display: "flex",
          flexDirection: "column",
          borderRadius: "8px",
          overflowY: "auto",
        }}
      >
        <Table<DetailedDesignTemplate>
          className="flex-table"
          size="small"
          columns={orderedColumns}
          rowSelection={rowSelection}
          dataSource={tableData}
          pagination={false}
          rowKey="id"
          loading={isLoading}
        />
      </Flex>
    </Flex>
  );
};
