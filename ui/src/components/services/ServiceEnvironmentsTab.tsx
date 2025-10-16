import React, { useEffect, useState, useRef, useMemo, useCallback } from "react";
import { Table, Spin, Flex, Button, Tooltip, message, Tag, Modal } from "antd";
import { Radio } from "antd";
import { Environment, EnvironmentSourceType, IntegrationSystemType } from "../../api/apiTypes";
import { useServiceContext, useChainsContext } from "./ServiceParametersPage";
import { api } from "../../api/api";
import { EnvironmentParamsModal } from "./EnvironmentParamsModal.tsx";
import { EnvironmentRequest } from "../../api/apiTypes";
import type { ColumnsType } from 'antd/es/table';
import { ChainColumn } from './ChainColumn';
import { useNotificationService } from "../../hooks/useNotificationService";
import { getErrorMessage } from '../../misc/error-utils';
import { useLocation } from "react-router-dom";
import { Icon } from "../../IconProvider.tsx";
import {environmentLabels} from "./utils.tsx";

interface ServiceEnvironmentsTabProps {
  formatTimestamp: (val: number) => string;
  setSystem?: (system: unknown) => void;
}

export const ServiceEnvironmentsTab: React.FC<ServiceEnvironmentsTabProps> = ({
  formatTimestamp,
  setSystem,
}) => {
  const system = useServiceContext();
  const protocol = system?.protocol || '';
  const systemId = system?.id || "";
  const activeEnvironmentId = system?.activeEnvironmentId;
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const chains = useChainsContext() || [];
  const [environments, setEnvironments] = useState<Environment[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const cacheRef = useRef<{ [key: string]: Environment[] }>({});
  const [editModalOpen, setEditModalOpen] = useState(false);
  const [editingEnv, setEditingEnv] = useState<Environment | null>(null);
  const [saving, setSaving] = useState(false);
  const [addModalOpen, setAddModalOpen] = useState(false);
  const [switchingEnvId, setSwitchingEnvId] = useState<string | null>(null);
  const notificationService = useNotificationService();
  const location = useLocation();

  const getDefaultProperties = useCallback((protocol: string, sourceType: EnvironmentSourceType) => {
    if (sourceType === EnvironmentSourceType.MANUAL) {
      const proto = protocol.toLowerCase();
      if (proto === 'amqp' || proto === 'rabbit') {
        return {
          password: '',
          username: '',
          routingKey: '',
          acknowledgeMode: 'AUTO',
        };
      }
      if (proto === 'kafka') {
        return {
          key: '',
          sslProtocol: '',
          saslMechanism: '',
          saslJaasConfig: '',
          securityProtocol: '',
          sslEnabledProtocols: '',
          sslEndpointAlgorithm: '',
        };
      }
      if (proto === 'http') {
        return {
          connectTimeout: '120000',
          soTimeout: '120000',
          connectionRequestTimeout: '120000',
          responseTimeout: '120000',
          getWithBody: 'false',
          deleteWithBody: 'false',
        };
      }
    }
    if (sourceType === EnvironmentSourceType.MAAS_BY_CLASSIFIER) {
      const proto = protocol.toLowerCase();
      if (proto === 'amqp' || proto === 'rabbit') {
        return {
          routingKey: '',
          acknowledgeMode: 'AUTO',
        };
      }
      return {};
    }
    return {};
  }, []);

  const defaultNewEnv: Environment = {
    id: '',
    systemId,
    name: 'New Environment',
    address: '',
    labels: [],
    properties: getDefaultProperties(protocol, EnvironmentSourceType.MANUAL),
    sourceType: EnvironmentSourceType.MANUAL,
  };

  const handleSwitchEnvironment = useCallback((env: Environment) => {
    Modal.confirm({
      title: 'Are you sure you want to switch to this Environment?',
      onOk: async () => {
        setSwitchingEnvId(env.id);
        try {
          await api.updateService(systemId, {
            activeEnvironmentId: env.id,
            name: system?.name,
            type: system?.type
          });
          delete cacheRef.current[systemId];
          setLoading(true);
          const newEnvs = await api.getEnvironments(systemId);
          setEnvironments(newEnvs);
          if (setSystem) {
            const updatedSystem = await api.getService(systemId);
            setSystem(updatedSystem);
          }
          message.success('Active environment switched');
        } catch (e: unknown) {
          notificationService.requestFailed(getErrorMessage(e, 'Switch failed'), e);
        } finally {
          setSwitchingEnvId(null);
          setLoading(false);
        }
      },
    });
  }, [systemId, system, setSystem, notificationService]);

  const loadEnvironments = useCallback(async (silent = false) => {
    if (!systemId) return;
    if (!silent) {
      setError(null);
      setLoading(true);
    }
    try {
      const data = await api.getEnvironments(systemId);
      setEnvironments(data);
      cacheRef.current[systemId] = data;
    } catch (e: unknown) {
      if (!silent) {
        setError(getErrorMessage(e, 'Environments load error'));
      }
    } finally {
      if (!silent) {
        setLoading(false);
      }
    }
  }, [systemId]);

  useEffect(() => {
    void loadEnvironments();
  }, [loadEnvironments]);

  useEffect(() => {
    if (systemId && location.pathname.includes('/environments')) {
      void loadEnvironments(true);
    }
  }, [location.pathname, systemId, loadEnvironments]);

  const handleEditClick = useCallback((env: Environment) => {
    setEditingEnv(env);
    setEditModalOpen(true);
  }, []);

  const handleDelete = useCallback((envId: string) => {
    Modal.confirm({
      title: 'Are you sure you want to delete this Environment?',
      onOk: async () => {
        try {
          setLoading(true);
          await api.deleteEnvironment(systemId, envId);
          setEnvironments(envs => envs.filter(e => e.id !== envId));
          message.success('Environment deleted');
        } catch (e: unknown) {
          notificationService.requestFailed(getErrorMessage(e, 'Delete failed'), e);
        } finally {
          setLoading(false);
        }
      },
    });
  }, [systemId, notificationService]);

  const memoChains = useMemo(() => chains, [chains]);

  const columns: ColumnsType<Environment> = useMemo(() => [
    {
      title: '',
      key: 'select',
      width: 48,
      align: 'center',
      render: (_: unknown, record: Environment) => (
        <Radio
          checked={record.id === activeEnvironmentId}
          onChange={() => handleSwitchEnvironment(record)}
          disabled={record.id === activeEnvironmentId || switchingEnvId === record.id}
        />
      ),
    },
    {
      title: <b>Name</b>,
      dataIndex: 'name',
      key: 'name',
      render: (text: string, record: Environment) => (
        <span
          style={{ fontWeight: 600, color: '#1677ff', cursor: 'pointer' }}
          onClick={() => handleEditClick(record)}
        >
          {text}
        </span>
      ),
    },
    {
      title: 'Address',
      dataIndex: 'address',
      key: 'address',
      render: (text?: string) => text || <span style={{ color: '#bbb' }}>/</span>,
    },
    {
      title: 'Source',
      dataIndex: 'sourceType',
      key: 'sourceType',
      render: (val?: string) => {
        if (!val) return '-';
        if (val === 'MAAS' || val === 'MAAS_BY_CLASSIFIER') return 'MaaS';
        return val.charAt(0) + val.slice(1).toLowerCase();
      },
    },
    {
      title: 'Modified',
      dataIndex: 'modifiedWhen',
      key: 'modifiedWhen',
      render: (val?: number) => val ? formatTimestamp(val) : '',
    },
    {
      title: 'Labels',
      dataIndex: 'labels',
      key: 'labels',
      render: (labels?: unknown[]) => {
        if (!Array.isArray(labels) || labels.length === 0) {
          return <span style={{ color: '#bbb' }}>-</span>;
        }

        return (
          <span>
            {labels.map((label, idx) => {
              let name =
                typeof label === 'string'
                  ? label
                  : typeof label === 'object' && label && 'name' in label
                    ? String((label as { name?: unknown }).name)
                    : String(label);
              name = environmentLabels[name] || name;

              return <Tag color="blue" key={`${name}-${idx}`}>{name}</Tag>;
            })}
          </span>
        );
      }
    },
    {
      title: 'Used by',
      key: 'usedBy',
      align: 'center',
      render: (_: unknown, record: Environment) => {
        const isActive = system && record.id === system.activeEnvironmentId;
        return <ChainColumn chains={isActive ? memoChains : []} />;
      },
    },
    {
      title: '',
      key: 'actions',
      width: 48,
      align: 'center',
      render: (_: unknown, record: Environment) => (
        <Tooltip title="Delete">
          <Button
            type="text"
            icon={<Icon name="delete" />}
            danger
            onClick={() => handleDelete(record.id)}
          />
        </Tooltip>
      ),
    },
  ], [activeEnvironmentId, memoChains, formatTimestamp, handleDelete, handleEditClick, handleSwitchEnvironment, switchingEnvId, system]);

  if (loading) return <Spin style={{ margin: 32 }} />;
  if (error) return <div style={{ color: 'red', margin: 32 }}>{error}</div>;
  if (!systemId) return null;

  return (
    <Flex vertical>
      {system?.type === IntegrationSystemType.EXTERNAL && (
          <Button type="primary" style={{ marginBottom: 16, alignSelf: 'flex-start' }} onClick={() => setAddModalOpen(true)}>
            Add Environment
          </Button>
      )}
      <Table
        dataSource={environments}
        rowKey="id"
        pagination={false}
        bordered
        size="small"
        style={{ background: "#fff", borderRadius: 12, width: '100%' }}
        columns={columns}
      />
      <EnvironmentParamsModal
        open={editModalOpen}
        environment={editingEnv}
        onClose={() => {
          setEditModalOpen(false);
          setEditingEnv(null);
        }}
        onSave={async (envRequest: EnvironmentRequest) => {
          try {
            setSaving(true);
            if (!editingEnv) throw new Error('No environment selected');
            const updated = await api.updateEnvironment(systemId, editingEnv.id, envRequest);
            setEnvironments(envs => envs.map(e => e.id === updated.id ? updated : e));
            setEditModalOpen(false);
            message.success('Environment updated');
          } catch (e: unknown) {
            notificationService.requestFailed(getErrorMessage(e, 'Update failed'), e);
          } finally {
            setSaving(false);
          }
        }}
        saving={saving}
      />
      <EnvironmentParamsModal
        open={addModalOpen}
        environment={defaultNewEnv}
        onClose={() => setAddModalOpen(false)}
        onSave={async (envRequest: EnvironmentRequest) => {
          try {
            setSaving(true);
            const created = await api.createEnvironment(systemId, envRequest);
            setEnvironments(envs => [...envs, created]);
            setAddModalOpen(false);
            message.success('Environment created');
          } catch (e: unknown) {
            notificationService.requestFailed(getErrorMessage(e, 'Create failed'), e);
          } finally {
            setSaving(false);
          }
        }}
        saving={saving}
      />
    </Flex>
  );
};
