import {DeleteOutlined, PlusOutlined} from '@ant-design/icons';
import {Button, Empty, Space, Tag, theme, Tooltip} from 'antd';
import React, {useMemo, useRef} from 'react';
import {moveWorkflowNode, nodeTypeLabel, workflowNodePosition,} from '@/features/ai-tool';
import type {WorkflowEdge, WorkflowNode, WorkflowNodeType,} from '@/types/ai.tool.type';

interface WorkflowCanvasProps {
  nodes: WorkflowNode[];
  edges: WorkflowEdge[];
  selectedNodeId?: string;
  readOnly?: boolean;
  onChange: (nodes: WorkflowNode[]) => void;
  onSelect: (nodeId: string) => void;
  onAdd: (type: WorkflowNodeType) => void;
  onDelete: (nodeId: string) => void;
}

const NODE_WIDTH = 180;
const NODE_HEIGHT = 76;

export default function WorkflowCanvas({
                                         nodes,
                                         edges,
                                         selectedNodeId,
                                         readOnly,
                                         onChange,
                                         onSelect,
                                         onAdd,
                                         onDelete,
                                       }: WorkflowCanvasProps) {
  const {token} = theme.useToken();
  const canvasRef = useRef<HTMLDivElement>(null);
  const nodeMap = useMemo(
    () => new Map(nodes.map((node) => [node.nodeId, node])),
    [nodes],
  );

  const moveNode = (event: React.DragEvent, node: WorkflowNode) => {
    if (readOnly || !canvasRef.current) return;
    const bounds = canvasRef.current.getBoundingClientRect();
    const next = moveWorkflowNode(
      node,
      event.clientX - bounds.left - NODE_WIDTH / 2,
      event.clientY - bounds.top - NODE_HEIGHT / 2,
    );
    onChange(nodes.map((item) => (item.nodeId === node.nodeId ? next : item)));
  };

  return (
    <div>
      <Space wrap style={{marginBottom: 12}}>
        {(['tool', 'router', 'parallel', 'join'] as WorkflowNodeType[]).map(
          (type) => (
            <Button
              key={type}
              icon={<PlusOutlined/>}
              disabled={readOnly}
              onClick={() => onAdd(type)}
            >
              {nodeTypeLabel(type)}节点
            </Button>
          ),
        )}
      </Space>
      <div
        ref={canvasRef}
        style={{
          height: 500,
          minWidth: 760,
          position: 'relative',
          overflow: 'auto',
          border: `1px solid ${token.colorBorderSecondary}`,
          borderRadius: token.borderRadiusLG,
          backgroundColor: token.colorFillAlter,
          backgroundImage: `radial-gradient(${token.colorBorder} 1px, transparent 1px)`,
          backgroundSize: '20px 20px',
        }}
      >
        {nodes.length === 0 && <Empty style={{marginTop: 150}}/>}
        <svg
          width="1600"
          height="900"
          aria-label="工作流连线"
          style={{position: 'absolute', inset: 0, pointerEvents: 'none'}}
        >
          <defs>
            <marker
              id="workflow-arrow"
              markerWidth="8"
              markerHeight="8"
              refX="7"
              refY="4"
              orient="auto"
            >
              <path d="M0,0 L8,4 L0,8 Z" fill={token.colorPrimary}/>
            </marker>
          </defs>
          {edges.map((edge) => {
            const source = nodeMap.get(edge.sourceNodeId);
            const target = nodeMap.get(edge.targetNodeId);
            if (!source || !target) return null;
            const from = workflowNodePosition(source);
            const to = workflowNodePosition(target);
            const x1 = from.x + NODE_WIDTH;
            const y1 = from.y + NODE_HEIGHT / 2;
            const x2 = to.x;
            const y2 = to.y + NODE_HEIGHT / 2;
            const bend = Math.max(40, Math.abs(x2 - x1) / 2);
            return (
              <path
                key={edge.edgeId}
                d={`M ${x1} ${y1} C ${x1 + bend} ${y1}, ${x2 - bend} ${y2}, ${x2} ${y2}`}
                fill="none"
                stroke={token.colorPrimary}
                strokeWidth="2"
                markerEnd="url(#workflow-arrow)"
              />
            );
          })}
        </svg>
        {nodes.map((node) => {
          const position = workflowNodePosition(node);
          const selected = selectedNodeId === node.nodeId;
          return (
            <div
              key={node.nodeId}
              draggable={!readOnly}
              onDragEnd={(event) => moveNode(event, node)}
              onClick={() => onSelect(node.nodeId)}
              style={{
                position: 'absolute',
                left: position.x,
                top: position.y,
                width: NODE_WIDTH,
                minHeight: NODE_HEIGHT,
                padding: 12,
                cursor: readOnly ? 'pointer' : 'move',
                border: `2px solid ${selected ? token.colorPrimary : token.colorBorder}`,
                borderRadius: token.borderRadiusLG,
                background: token.colorBgContainer,
                boxShadow: selected
                  ? token.boxShadowSecondary
                  : token.boxShadowTertiary,
                zIndex: selected ? 3 : 2,
              }}
            >
              <Space style={{width: '100%', justifyContent: 'space-between'}}>
                <Tag color={nodeColor(node.type)}>
                  {nodeTypeLabel(node.type)}
                </Tag>
                {!readOnly && !['start', 'end'].includes(node.type) && (
                  <Tooltip title="删除节点及关联连线">
                    <Button
                      type="text"
                      danger
                      size="small"
                      icon={<DeleteOutlined/>}
                      onClick={(event) => {
                        event.stopPropagation();
                        onDelete(node.nodeId);
                      }}
                    />
                  </Tooltip>
                )}
              </Space>
              <div style={{fontWeight: 600, marginTop: 5}}>{node.name}</div>
              <div
                style={{fontSize: 11, color: token.colorTextTertiary}}
                title={node.nodeId}
              >
                {node.nodeId.slice(0, 24)}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function nodeColor(type: WorkflowNodeType): string {
  return {
    start: 'green',
    end: 'red',
    tool: 'blue',
    router: 'orange',
    parallel: 'purple',
    join: 'cyan',
  }[type];
}
