import {i18nText} from '@/utils/i18n';
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
      .catch(() => message.error(i18nText("app.article.sidebar.publishtopublicmodal.fce6d565")))
      .finally(() => setLoading(false));
  }, [open, message]);

  const handleOk = useCallback(async () => {
    if (!selectedKey && resourceType === 'ARTICLE') {
      message.warning(i18nText("app.article.sidebar.publishtopublicmodal.2e50661f"));
      return;
    }
    const targetCatalogId = selectedKey
      ? Number(String(selectedKey).replace('public-', ''))
      : null;
    setPublishing(true);
    try {
      await publishApi(resourceId, targetCatalogId);
      message.success(i18nText("app.article.sidebar.publishtopublicmodal.caac65d1"));
      onPublished();
      onClose();
    } catch {
      message.error(i18nText("app.article.sidebar.publishtopublicmodal.a870e400"));
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
      title={i18nText("app.article.sidebar.publishtopublicmodal.1457d415", {value0: resourceName})}
      open={open}
      onCancel={onClose}
      onOk={handleOk}
      confirmLoading={publishing}
      okText={i18nText("app.article.sidebar.publishtopublicmodal.369c1b48")}
      cancelText={i18nText("app.article.sidebar.publishtopublicmodal.b6d39045")}
      destroyOnHidden={true}
    >
      <div className="mb-3 text-[13px] text-[#666]">
        {i18nText("app.article.sidebar.publishtopublicmodal.4473dfbc")}{resourceType === 'CATALOG' ? i18nText("app.article.sidebar.publishtopublicmodal.5a696bdf") : i18nText("app.article.sidebar.publishtopublicmodal.688555dc")}
        {i18nText("app.article.sidebar.publishtopublicmodal.6400bd3c")}
      </div>
      {loading ? (
        <div className="p-6 text-center">{i18nText("app.article.sidebar.publishtopublicmodal.55aadc8d")}</div>
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
