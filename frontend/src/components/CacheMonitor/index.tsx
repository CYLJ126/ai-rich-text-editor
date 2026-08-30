// src/components/CacheMonitor/ArticleTags.tsx
import React, {useEffect, useState} from 'react';
import {Button, Card, List, Tag} from 'antd';
import {useAliveController} from 'react-activation';

const CacheMonitor: React.FC = () => {
  const { getCachingNodes, drop, refresh, clear } = useAliveController();
  const [cacheNodes, setCacheNodes] = useState<any[]>([]);

  const updateCacheNodes = () => {
    const nodes = getCachingNodes();
    setCacheNodes(nodes);
    console.log('当前缓存的节点:', nodes);
  };

  useEffect(() => {
    updateCacheNodes();
    // 定时更新缓存状态
    const interval = setInterval(updateCacheNodes, 2000);
    return () => clearInterval(interval);
  }, []);

  return (
    <Card
      title="KeepAlive 缓存监控"
      size="small"
      extra={
        <Button size="small" onClick={updateCacheNodes}>
          刷新
        </Button>
      }
    >
      <div style={{ marginBottom: 8 }}>
        <Tag color="blue">缓存节点数量: {cacheNodes.length}</Tag>
        <Button size="small" danger onClick={() => { clear(); updateCacheNodes(); }}>
          清空所有缓存
        </Button>
      </div>

      <List
        size="small"
        dataSource={cacheNodes}
        renderItem={(node, index) => (
          <List.Item
            actions={[
              <Button
                size="small"
                onClick={() => { refresh(node.name); updateCacheNodes(); }}
              >
                刷新
              </Button>,
              <Button
                size="small"
                danger
                onClick={() => { drop(node.name); updateCacheNodes(); }}
              >
                删除
              </Button>
            ]}
          >
            <div>
              <strong>名称:</strong> {node.name || `节点-${index}`}
              <br />
              <strong>缓存Key:</strong> {node.name}
            </div>
          </List.Item>
        )}
        locale={{ emptyText: '暂无缓存节点' }}
      />
    </Card>
  );
};

export default CacheMonitor;
