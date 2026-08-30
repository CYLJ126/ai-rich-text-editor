import {App, Modal, Tree} from 'antd';
import React, {useCallback, useEffect, useState} from 'react';
import type {CatalogTreeNode, CatalogType,} from '@/types/rt.type';
import {listSpaceCatalogs} from '@/services/share';

interface SelectTargetCatalogModalProps {
  open: boolean;
  title: string;
  instruction: string;
  resourceName: string;
  targetSpace?: 'my' | 'public';
  onClose: () => void;
  /** targetCatalogId null means no catalog was selected. */
  onSubmit: (targetCatalogId: number | null) => Promise<void>;
}

function buildCatalogTree(
  nodes: CatalogType[],
  keyPrefix: string,
  onlyOwner = false,
): CatalogTreeNode[] {
  return nodes
    .filter((cat) => !onlyOwner || cat.canDelete)
    .map((cat) => {
      const children =
        cat.children && cat.children.length > 0
          ? buildCatalogTree(cat.children, keyPrefix, onlyOwner)
          : undefined;
      return {
        key: `${keyPrefix}-${cat.id}`,
        title: cat.name,
        children,
        data: cat,
        type: 'catalog' as const,
        isLeaf: !children || children.length === 0,
      };
    });
}

export default function SelectTargetCatalogModal({
  open,
  title,
  instruction,
  resourceName,
  targetSpace = 'my',
  onClose,
  onSubmit,
}: SelectTargetCatalogModalProps) {
  const { message } = App.useApp();
  const [treeData, setTreeData] = useState<CatalogTreeNode[]>([]);
  const [selectedKey, setSelectedKey] = useState<React.Key | null>(null);
  const [loading, setLoading] = useState(false);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!open) return;
    setLoading(true);
    setSelectedKey(null);
    listSpaceCatalogs()
      .then((data) => {
        const nodes =
          targetSpace === 'public'
            ? (data?.publicSpace ?? [])
            : (data?.mySpace ?? []);
        setTreeData(
          buildCatalogTree(nodes, targetSpace, targetSpace === 'public'),
        );
      })
      .catch(() => message.error('获取目标目录失败'))
      .finally(() => setLoading(false));
  }, [open, message, targetSpace]);

  const handleOk = useCallback(async () => {
    const targetCatalogId = selectedKey
      ? Number(String(selectedKey).replace(`${targetSpace}-`, ''))
      : null;
    setSubmitting(true);
    try {
      await onSubmit(targetCatalogId);
      onClose();
    } catch {
      message.error('操作失败');
    } finally {
      setSubmitting(false);
    }
  }, [selectedKey, targetSpace, onSubmit, message, onClose]);

  return (
    <Modal
      title={`${title} - "${resourceName}"`}
      open={open}
      onCancel={onClose}
      onOk={handleOk}
      confirmLoading={submitting}
      okText="确定"
      cancelText="取消"
      destroyOnHidden={true}
    >
      <div className="mb-3 text-[13px] text-[#666]">{instruction}</div>
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
