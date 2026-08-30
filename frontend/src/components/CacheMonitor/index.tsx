import {i18nText} from '@/utils/i18n';
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
      title={i18nText("app.common.cachemonitor.d0088af9")}
      size="small"
      extra={
        <Button size="small" onClick={updateCacheNodes}>
          {i18nText("app.common.cachemonitor.5910789e")}
        </Button>
      }
    >
      <div style={{ marginBottom: 8 }}>
        <Tag color="blue">{i18nText("app.common.cachemonitor.9e36543c")} {cacheNodes.length}</Tag>
        <Button size="small" danger onClick={() => { clear(); updateCacheNodes(); }}>
          {i18nText("app.common.cachemonitor.0e14cb4f")}
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
                {i18nText("app.common.cachemonitor.5910789e")}
              </Button>,
              <Button
                size="small"
                danger
                onClick={() => { drop(node.name); updateCacheNodes(); }}
              >
                {i18nText("app.common.cachemonitor.d10ae479")}
              </Button>
            ]}
          >
            <div>
              <strong>{i18nText("app.common.cachemonitor.e18661b9")}</strong> {node.name || i18nText("app.common.cachemonitor.0c51f07e", {value0: index})}
              <br />
              <strong>{i18nText("app.common.cachemonitor.1343744b")}</strong> {node.name}
            </div>
          </List.Item>
        )}
        locale={{ emptyText: i18nText("app.common.cachemonitor.eb35d0c7") }}
      />
    </Card>
  );
};

export default CacheMonitor;
