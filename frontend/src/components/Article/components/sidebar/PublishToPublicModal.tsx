import {App, Modal, Tree} from 'antd';
import React, {useCallback, useEffect, useState} from 'react';
import type {CatalogTreeNode, CatalogType,} from '@/types/rt.type';
import {listSpaceCatalogs} from '@/services/share';

interface PublishToPublicModalProps {
  open: boolean;
  resourceType: 'CATALOG' | 'ARTICLE';
  resourceId: number;
  resourceName: string;
  onClose: () => void;
  onPublished: () => void;
  publishApi: (id: number, targetCatalogId: number | null) => Promise<any>;
}

function buildPublicTree(nodes: CatalogType[]): CatalogTreeNode[] {
  return nodes.map((cat) => ({
    key: `public-${cat.id}`,
    title: cat.name,
    children: cat.children ? buildPublicTree(cat.children) : undefined,
    data: cat,
    type: 'catalog' as const,
    isLeaf: !cat.children || cat.children.length === 0,
  }));
}

export default function PublishToPublicModal({
  open,
  resourceType,
  resourceId,
  resourceName,
  onClose,
  onPublished,
  publishApi,
}: PublishToPublicModalProps) {
  const { message } = App.useApp();
  const [treeData, setTreeData] = useState<CatalogTreeNode[]>([]);
  const [selectedKey, setSelectedKey] = useState<React.Key | null>(null);
  const [loading, setLoading] = useState(false);
  const [publishing, setPublishing] = useState(false);

  useEffect(() => {
    if (!open) return;
    setLoading(true);
    listSpaceCatalogs()
      .then((data) => {
        setTreeData(buildPublicTree(data?.publicSpace ?? []));
      })
      .catch(() => message.error('获取公共空间目录失败'))
      .finally(() => setLoading(false));
  }, [open, message]);

  const handleOk = useCallback(async () => {
    if (!selectedKey && resourceType === 'ARTICLE') {
      message.warning('请选择公共空间中的目标目录');
      return;
    }
    const targetCatalogId = selectedKey
      ? Number(String(selectedKey).replace('public-', ''))
      : null;
    setPublishing(true);
    try {
      await publishApi(resourceId, targetCatalogId);
      message.success('已发布至公共空间');
      onPublished();
      onClose();
    } catch {
      message.error('发布失败');
    } finally {
      setPublishing(false);
    }
  }, [
    selectedKey,
    resourceId,
    resourceType,
    publishApi,
    message,
    onPublished,
    onClose,
  ]);

  return (
    <Modal
      title={`发布至公共空间 — "${resourceName}"`}
      open={open}
      onCancel={onClose}
      onOk={handleOk}
      confirmLoading={publishing}
      okText="发布"
      cancelText="取消"
      destroyOnHidden={true}
    >
      <div className="mb-3 text-[13px] text-[#666]">
        请选择{resourceType === 'CATALOG' ? '目录' : '文章'}
        在公共空间中的目标位置：
      </div>
      {loading ? (
        <div className="p-6 text-center">加载中...</div>
      ) : (
        <Tree
          treeData={treeData as any}
          selectedKeys={selectedKey ? [selectedKey] : []}
          onSelect={(keys) => setSelectedKey(keys[0] ?? null)}
          defaultExpandAll
          className="max-h-[300px] overflow-auto"
        />
      )}
    </Modal>
  );
}
