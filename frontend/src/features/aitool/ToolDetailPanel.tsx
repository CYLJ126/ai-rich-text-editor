import React, {useEffect, useState} from 'react';
import {Alert, Button, Collapse, Descriptions, Flex, Form, Spin, Tag, Typography,} from 'antd';
import type {AiScope} from '@/services/arte-ai';
import {getTool, type ToolDetail, type ToolSummary,} from '@/services/arte-ai/tool';
import ConfigurationEditor from './ConfigurationEditor';
import {toolFailure} from './useToolCommand';
import {parseConfiguration} from '@/services/arte-ai/toolJson';

export const releaseName: Record<string, string> = {
  PUBLISHED: '已发布',
  DEPRECATED: '已废弃',
  DISABLED: '已停用',
  DRAFT: '草稿',
};
export default function ToolDetailPanel({
                                          scope,
                                          tool,
                                          disabled,
                                          onPrepare,
                                          onDirty,
                                          configurationError,
                                        }: {
  scope: AiScope;
  tool: ToolSummary;
  disabled: boolean;
  onPrepare: (configuration: string) => void;
  onDirty: (dirty: boolean) => void;
  configurationError?: boolean;
}) {
  const [detail, setDetail] = useState<ToolDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [configuration, setConfiguration] = useState('{}');
  const [revision, setRevision] = useState(0);
  const [configure, setConfigure] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    getTool(scope, tool.tool, {signal: controller.signal})
      .then(({body}) => {
        if (!controller.signal.aborted) setDetail(body.data);
      })
      .catch((failure) => {
        if (!controller.signal.aborted) setError(toolFailure(failure));
      });
    return () => {
      controller.abort();
      onDirty(false);
    };
  }, [scope, tool, revision, onDirty]);
  useEffect(() => {
    if (configurationError) setConfigure(true);
  }, [configurationError]);
  const prepare = () => {
    try {
      parseConfiguration(configuration);
      setError(null);
      onPrepare(configuration);
    } catch (failure) {
      setError((failure as Error).message);
    }
  };
  return (
    <Flex vertical gap={16}>
      <Typography.Title level={4}>{tool.title}</Typography.Title>
      <Typography.Paragraph>{tool.description}</Typography.Paragraph>
      <Flex gap={8} wrap>
        <Tag>{releaseName[tool.release.state] ?? tool.release.state}</Tag>
        <Tag>
          {tool.risk.sideEffect === 'READ_ONLY' ? '只读' : '有业务副作用'}
        </Tag>
        {tool.risk.destructive && <Tag color="red">破坏性操作</Tag>}
      </Flex>
      <Typography.Text type="secondary">
        准备配置不会执行工具。工具执行资格仍需在使用时校验。
      </Typography.Text>
      {error && (
        <Alert
          type="warning"
          title={error}
          action={
            <Button
              onClick={() => {
                setError(null);
                setRevision((value) => value + 1);
              }}
            >
              重新读取
            </Button>
          }
        />
      )}
      {!detail && !error && <Spin/>}
      {detail && (
        <>
          <Button
            type="primary"
            disabled={disabled || !tool.release.permitsNewBinding}
            onClick={prepare}
          >
            准备默认配置
          </Button>
          {!tool.release.permitsNewBinding && (
            <Alert
              type="info"
              title="此版本不接受新配置；已有配置仍按其生命周期规则使用。"
            />
          )}
          <Button type="link" onClick={() => setConfigure((value) => !value)}>
            {configure ? '收起配置项' : '需要补充配置？'}
          </Button>
          {configure && (
            <Form layout="vertical" disabled={disabled}>
              <ConfigurationEditor
                schema={detail.configurationSchema}
                value={configuration}
                onChange={(value) => {
                  setConfiguration(value);
                  onDirty(value !== '{}');
                }}
              />
            </Form>
          )}
          <Collapse
            items={[
              {
                key: 'advanced',
                label: '高级：版本、限制与 Schema',
                children: (
                  <>
                    <Descriptions
                      size="small"
                      column={1}
                      items={[
                        {
                          key: 'tool',
                          label: '工具',
                          children: `${tool.tool.id} / ${tool.tool.version}`,
                        },
                        {
                          key: 'provider',
                          label: '提供者',
                          children: `${tool.provider.id} / ${tool.provider.version}`,
                        },
                        {
                          key: 'limits',
                          label: '工具默认上限',
                          children: `${tool.defaultPolicy.timeoutMillis / 1000}s / ${tool.defaultPolicy.maxAttempts} 次尝试`,
                        },
                        {
                          key: 'network',
                          label: '外发范围',
                          children:
                            tool.risk.networkOrigins.join('、') || '不外发',
                        },
                      ]}
                    />
                    {(
                      [
                        'inputSchema',
                        'outputSchema',
                        'configurationSchema',
                      ] as const
                    ).map((key) => (
                      <div key={key}>
                        <Typography.Text strong>
                          {
                            {
                              inputSchema: '参数 Schema',
                              outputSchema: '结果 Schema',
                              configurationSchema: '配置 Schema',
                            }[key]
                          }
                        </Typography.Text>
                        <pre className="tool-json">
                          {detail[key].documentJson}
                        </pre>
                      </div>
                    ))}
                  </>
                ),
              },
            ]}
          />
        </>
      )}
    </Flex>
  );
}
