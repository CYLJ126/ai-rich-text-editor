import {ArrowLeftOutlined, StopOutlined, UploadOutlined,} from '@ant-design/icons';
import {useAccess, useNavigate, useSearchParams} from '@umijs/max';
import {
  Alert,
  Button,
  Card,
  Descriptions,
  Form,
  Input,
  message,
  Modal,
  Result,
  Select,
  Skeleton,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
} from 'antd';
import type {ColumnsType} from 'antd/es/table';
import dayjs from 'dayjs';
import React, {useCallback, useEffect, useMemo, useState} from 'react';
import {JsonEditor} from '@/components';
import {JsonSchemaForm, ToolErrorAlert, ToolStatusTag,} from '@/components/AITool';
import {normalizeToolError} from '@/features/ai-tool';
import {
  deprecateToolVersion,
  disableToolVersion,
  getToolUpgradePreview,
  getToolVersionDetail,
  listToolVersions,
  publishToolVersion,
} from '@/services/ant-design-pro/ai.tool';
import type {
  JsonObject,
  ToolPublishCommand,
  ToolReference,
  ToolUpgradePreview,
  ToolVersionDetailView,
  ToolVersionView,
} from '@/types/ai.tool.type';
import {ToolManagementPage} from '../components';

type TransitionAction = 'deprecate' | 'disable';

export default function ToolVersionDetailPage() {
  const access = useAccess();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const reference = useMemo<ToolReference | null>(() => {
    const namespace = searchParams.get('namespace');
    const name = searchParams.get('name');
    const version = searchParams.get('version');
    return namespace && name && version ? {namespace, name, version} : null;
  }, [searchParams]);
  const [detail, setDetail] = useState<ToolVersionDetailView>();
  const [versions, setVersions] = useState<ToolVersionView[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>();
  const [publishing, setPublishing] = useState(false);
  const [publishOpen, setPublishOpen] = useState(false);
  const [publishForm] = Form.useForm<ToolPublishCommand>();
  const [preview, setPreview] = useState<ToolUpgradePreview>();
  const [previewLoading, setPreviewLoading] = useState(false);
  const baseVersion = Form.useWatch('compatibilityBaseVersion', publishForm);
  const [transitionAction, setTransitionAction] = useState<TransitionAction>();
  const [reason, setReason] = useState('');
  const [transitioning, setTransitioning] = useState(false);

  const load = useCallback(async () => {
    if (!reference) return;
    setLoading(true);
    setError(undefined);
    try {
      const [versionDetail, versionList] = await Promise.all([
        getToolVersionDetail(reference),
        listToolVersions(reference.namespace, reference.name),
      ]);
      if (!versionDetail) throw new Error('工具版本不存在或已被删除');
      setDetail(versionDetail);
      setVersions(versionList);
    } catch (nextError) {
      setError(nextError);
    } finally {
      setLoading(false);
    }
  }, [reference]);

  useEffect(() => {
    load().then();
  }, [load]);

  useEffect(() => {
    setPreview(undefined);
    if (!publishOpen || !reference || !baseVersion) {
      setPreviewLoading(false);
      return;
    }
    let active = true;
    setPreviewLoading(true);
    getToolUpgradePreview(reference, baseVersion)
      .then(result => {
        if (active) setPreview(result);
      })
      .catch(error => {
        if (active) message.error(normalizeToolError(error).message);
      })
      .finally(() => {
        if (active) setPreviewLoading(false);
      });
    return () => {
      active = false;
    };
  }, [publishOpen, reference, baseVersion]);

  const changeVersion = (version: string) => {
    if (!reference) return;
    const params = new URLSearchParams({...reference, version});
    navigate(`/AI/ToolManagement/Tools/Detail?${params.toString()}`);
  };

  const handlePublish = async () => {
    if (!reference) return;
    const command = await publishForm.validateFields();
    if (command.compatibilityBaseVersion && !preview?.compatible) return;
    setPublishing(true);
    try {
      await publishToolVersion(reference, command);
      message.success(detail?.lifecycleState === 'published' ? '兼容升级关系已确认' : '工具版本已发布').then();
      setPublishOpen(false);
      await load();
    } catch (nextError) {
      message.error(normalizeToolError(nextError).message).then();
    } finally {
      setPublishing(false);
    }
  };

  const handleTransition = async () => {
    if (!reference || !transitionAction) return;
    setTransitioning(true);
    try {
      if (transitionAction === 'deprecate') {
        await deprecateToolVersion(reference, reason.trim() || undefined);
        message.success('工具版本已废弃').then();
      } else {
        await disableToolVersion(reference, reason.trim() || undefined);
        message.success('工具版本已禁用').then();
      }
      setTransitionAction(undefined);
      setReason('');
      await load();
    } catch (nextError) {
      message.error(normalizeToolError(nextError).message).then();
    } finally {
      setTransitioning(false);
    }
  };

  if (!reference) {
    return <Result status="warning" title="缺少工具版本参数"/>;
  }

  const columns: ColumnsType<ToolVersionView> = [
    {
      title: '版本',
      dataIndex: ['reference', 'version'],
      render: (_, record) => (
        <Typography.Link
          strong={record.reference.version === reference.version}
          onClick={() => changeVersion(record.reference.version)}
        >
          {record.reference.version}
        </Typography.Link>
      ),
    },
    {
      title: '状态',
      dataIndex: 'lifecycleState',
      render: (value) => <ToolStatusTag status={value}/>,
    },
    {title: '兼容基准', dataIndex: 'compatibilityBaseVersion', render: value => value || '独立发布'},
    {title: '升级说明', dataIndex: 'releaseNotes', ellipsis: true},
    {title: '校验和', dataIndex: 'checksum', ellipsis: true},
    {
      title: '发布时间',
      dataIndex: 'publishedAt',
      render: (value) =>
        value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-',
    },
  ];

  const compatibilityCandidates = versions.filter(item => item.lifecycleState === 'published'
    && item.reference.version !== reference.version
    && (detail?.lifecycleState === 'draft' || Boolean(item.publishedAt && detail?.publishedAt
      && dayjs(item.publishedAt).isBefore(dayjs(detail.publishedAt)))));

  const actionButtons =
    detail && access.canManageAiTools ? (
      <Space>
        {(detail.lifecycleState === 'draft' || (detail.lifecycleState === 'published'
          && !detail.compatibilityBaseVersion && compatibilityCandidates.length > 0)) && (
          <Button
            type="primary"
            icon={<UploadOutlined/>}
            loading={publishing}
            onClick={() => {
              publishForm.resetFields();
              setPublishOpen(true);
            }}
          >
            {detail.lifecycleState === 'draft' ? '发布' : '设置兼容升级'}
          </Button>
        )}
        {detail.lifecycleState === 'published' && (
          <Button onClick={() => setTransitionAction('deprecate')}>废弃</Button>
        )}
        {detail.lifecycleState !== 'disabled' && (
          <Button
            danger
            icon={<StopOutlined/>}
            onClick={() => setTransitionAction('disable')}
          >
            禁用
          </Button>
        )}
      </Space>
    ) : undefined;

  return (
    <ToolManagementPage
      activeKey="catalog"
      title={detail?.title || `${reference.namespace}.${reference.name}`}
      subTitle={`固定版本 ${reference.version}`}
      extra={
        <Space>
          <Button
            icon={<ArrowLeftOutlined/>}
            onClick={() => navigate('/AI/ToolManagement/Tools')}
          >
            返回目录
          </Button>
          {actionButtons}
        </Space>
      }
    >
      {loading ? (
        <Card>
          <Skeleton active/>
        </Card>
      ) : error ? (
        <ToolErrorAlert error={error} onRetry={load}/>
      ) : detail ? (
        <Space orientation="vertical" size={16} style={{width: '100%'}}>
          {detail.lifecycleState === 'draft' && (
            <Alert
              type="info"
              showIcon
              title="草稿由工具提供者维护"
              description="修改本地 Java、HTTP、OpenAPI 或 MCP 提供者中的源定义并刷新，即可更新草稿或生成新版本。发布后当前版本将保持不可变。"
            />
          )}
          <Card title="版本信息">
            <Descriptions
              bordered
              column={{xs: 1, sm: 2, lg: 3}}
              size="small"
            >
              <Descriptions.Item label="工具标识" span={2}>
                <Typography.Text copyable>
                  {detail.reference.namespace}.{detail.reference.name}@
                  {detail.reference.version}
                </Typography.Text>
              </Descriptions.Item>
              <Descriptions.Item label="状态">
                <ToolStatusTag status={detail.lifecycleState}/>
              </Descriptions.Item>
              <Descriptions.Item label="提供者">
                {detail.providerId}
              </Descriptions.Item>
              <Descriptions.Item label="风险等级">
                <ToolStatusTag
                  status={
                    stringValue(detail.riskProfile.level) as
                      | 'low'
                      | 'medium'
                      | 'high'
                      | 'critical'
                  }
                />
              </Descriptions.Item>
              <Descriptions.Item label="兼容基准">{detail.compatibilityBaseVersion || '独立发布'}</Descriptions.Item>
              <Descriptions.Item label="升级说明" span={3}>{detail.releaseNotes || '-'}</Descriptions.Item>
              <Descriptions.Item label="标签" span={3}>
                {detail.tags.length
                  ? detail.tags.map((tag) => <Tag key={tag}>{tag}</Tag>)
                  : '-'}
              </Descriptions.Item>
              <Descriptions.Item label="描述" span={3}>
                {detail.description}
              </Descriptions.Item>
              <Descriptions.Item label="校验和" span={3}>
                <Typography.Text copyable code>
                  {detail.checksum}
                </Typography.Text>
              </Descriptions.Item>
              <Descriptions.Item label="创建时间">
                {formatTime(detail.createTime)}
              </Descriptions.Item>
              <Descriptions.Item label="更新时间">
                {formatTime(detail.updateTime)}
              </Descriptions.Item>
              <Descriptions.Item label="发布时间">
                {formatTime(detail.publishedAt)}
              </Descriptions.Item>
            </Descriptions>
          </Card>

          <Card title="定义与策略">
            <Tabs
              items={[
                {
                  key: 'input-preview',
                  label: '输入表单预览',
                  children: (
                    <JsonSchemaForm schema={detail.inputSchema} disabled/>
                  ),
                },
                {
                  key: 'input-schema',
                  label: '输入 Schema',
                  children: (
                    <JsonEditor
                      value={detail.inputSchema}
                      readOnly
                      height={360}
                    />
                  ),
                },
                {
                  key: 'output-schema',
                  label: '输出 Schema',
                  children: (
                    <JsonEditor
                      value={detail.outputSchema}
                      readOnly
                      height={360}
                    />
                  ),
                },
                {
                  key: 'capabilities',
                  label: '能力',
                  children: (
                    <JsonEditor
                      value={detail.capabilities}
                      readOnly
                      height={320}
                    />
                  ),
                },
                {
                  key: 'risk',
                  label: '风险画像',
                  children: (
                    <JsonEditor
                      value={detail.riskProfile}
                      readOnly
                      height={320}
                    />
                  ),
                },
                {
                  key: 'policy',
                  label: '默认配置与策略',
                  children: (
                    <Space orientation="vertical" style={{width: '100%'}}>
                      <Typography.Title level={5}>默认配置</Typography.Title>
                      <JsonEditor
                        value={detail.defaultConfiguration}
                        readOnly
                        height={220}
                      />
                      <Typography.Title level={5}>默认策略</Typography.Title>
                      <JsonEditor
                        value={detail.defaultPolicy}
                        readOnly
                        height={260}
                      />
                    </Space>
                  ),
                },
              ]}
            />
          </Card>

          <Card title="版本历史">
            <Table<ToolVersionView>
              rowKey={(record) => record.reference.version}
              columns={columns}
              dataSource={versions}
              pagination={false}
              size="small"
            />
          </Card>
        </Space>
      ) : null}

      <Modal title={`${detail?.lifecycleState === 'published' ? '设置兼容升级' : '发布版本'} ${reference.version}`}
             open={publishOpen} confirmLoading={publishing}
             okText={detail?.lifecycleState === 'published' ? '确认兼容升级' : '确认发布'} onOk={handlePublish}
             onCancel={() => setPublishOpen(false)}
             okButtonProps={{disabled: previewLoading || Boolean(baseVersion && !preview?.compatible)}}>
        <Alert type="info" showIcon message="发布后版本定义不可修改" style={{marginBottom: 16}}
               description="兼容发布会自动更新跟随兼容升级的绑定，保留用户配置和助手关联。独立发布由用户主动选择。"/>
        <Form form={publishForm} layout="vertical">
          <Form.Item name="compatibilityBaseVersion" label="兼容基准"
                     rules={[{required: detail?.lifecycleState === 'published', message: '请选择兼容基准'}]}
                     extra={detail?.lifecycleState === 'published' ? '仅补充一次兼容升级关系，保留发布定义与原发布时间。' : '留空为独立发布，不自动更新旧绑定。'}>
            <Select allowClear placeholder={detail?.lifecycleState === 'published' ? '选择更早发布的版本' : '独立发布'}
                    options={compatibilityCandidates
                      .map(item => ({value: item.reference.version, label: item.reference.version}))}/>
          </Form.Item>
          <Form.Item name="releaseNotes" label="升级说明"
                     rules={[{required: true, whitespace: true, message: '请填写面向用户的升级说明'}]}>
            <Input.TextArea rows={4} maxLength={2000} showCount placeholder="说明变化，以及用户是否需要调整配置"/>
          </Form.Item>
        </Form>
        {previewLoading && <Skeleton active paragraph={{rows: 1}}/>}
        {preview && <Alert showIcon type={preview.compatible ? 'success' : 'error'}
                           message={preview.compatible ? '兼容检查通过' : '不能作为兼容升级发布'}
                           description={preview.compatible ? `${preview.followingBindings} 个绑定会自动更新；${preview.pinnedBindings} 个锁定绑定保持版本。`
                             : preview.problems.join('；')}/>}
      </Modal>

      <Modal
        title={
          transitionAction === 'deprecate' ? '废弃工具版本' : '禁用工具版本'
        }
        open={Boolean(transitionAction)}
        confirmLoading={transitioning}
        okButtonProps={{danger: transitionAction === 'disable'}}
        okText="确认"
        onOk={handleTransition}
        onCancel={() => {
          setTransitionAction(undefined);
          setReason('');
        }}
      >
        <Typography.Paragraph type="secondary">
          {transitionAction === 'deprecate'
            ? '废弃后该版本不可调用；兼容跟随绑定会选择其他可用的兼容版本，锁定此版本的绑定将不可用。'
            : '禁用后该版本将立即从可调用注册表移除。'}
        </Typography.Paragraph>
        <Input.TextArea
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          placeholder="请输入操作原因（可选）"
          maxLength={500}
          showCount
        />
      </Modal>
    </ToolManagementPage>
  );
}

function formatTime(value?: string) {
  return value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '-';
}

function stringValue(value: JsonObject[string]): string {
  return typeof value === 'string' ? value : 'low';
}
