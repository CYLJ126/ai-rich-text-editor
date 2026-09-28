import {Card, Empty, Space, Tag, Timeline, Typography} from 'antd';
import dayjs from 'dayjs';
import React from 'react';
import {formatDuration, TOOL_EVENT_PRESENTATION, traceDurationMs,} from '@/features/ai-tool';
import type {ToolExecutionTrace} from '@/types/ai.tool.type';

export interface TraceTimelineProps {
  trace?: ToolExecutionTrace;
}

export default function TraceTimeline({trace}: TraceTimelineProps) {
  if (!trace?.events.length) {
    return <Empty description="暂无轨迹事件"/>;
  }

  return (
    <Space direction="vertical" size="middle" style={{width: '100%'}}>
      <Card size="small">
        <Space wrap size="large">
          <Typography.Text>
            轨迹 ID：<Typography.Text copyable>{trace.traceId}</Typography.Text>
          </Typography.Text>
          <Typography.Text>
            调用 ID：<Typography.Text copyable>{trace.callId}</Typography.Text>
          </Typography.Text>
          <Typography.Text>
            轨迹跨度：{formatDuration(traceDurationMs(trace.events))}
          </Typography.Text>
          <Typography.Text>事件数：{trace.events.length}</Typography.Text>
        </Space>
      </Card>
      <Timeline
        items={trace.events.map((event) => {
          const presentation = TOOL_EVENT_PRESENTATION[event.type];
          return {
            color: presentation.color,
            children: (
              <div>
                <Space wrap>
                  <Tag color={presentation.color}>{presentation.label}</Tag>
                  <Typography.Text type="secondary">
                    {formatTime(event.occurredAt)}
                  </Typography.Text>
                  {event.spanId && (
                    <Typography.Text type="secondary" copyable>
                      span: {event.spanId}
                    </Typography.Text>
                  )}
                </Space>
                <Typography.Paragraph
                  type="secondary"
                  style={{marginTop: 6, marginBottom: 4}}
                >
                  {event.tool.namespace}.{event.tool.name}@{event.tool.version}
                </Typography.Paragraph>
                {Object.keys(event.attributes || {}).length > 0 && (
                  <pre
                    style={{
                      margin: 0,
                      padding: 8,
                      overflow: 'auto',
                      borderRadius: 6,
                      background: 'rgba(127, 127, 127, 0.08)',
                      whiteSpace: 'pre-wrap',
                      wordBreak: 'break-word',
                    }}
                  >
                    {JSON.stringify(event.attributes, null, 2)}
                  </pre>
                )}
              </div>
            ),
          };
        })}
      />
    </Space>
  );
}

function formatTime(value: string): string {
  const time = dayjs(value);
  return time.isValid() ? time.format('YYYY-MM-DD HH:mm:ss.SSS') : value;
}
