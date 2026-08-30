import {CopyOutlined} from '@ant-design/icons';
import type {SelectProps} from 'antd';
import {App, Button, Modal, Segmented, Select, Space, Tag} from 'antd';
import React, {useCallback, useEffect, useMemo, useState} from 'react';
import type {ArticlePermission, CatalogPermission, ShareRecord} from '@/types/rt.type';
import {listShares, searchRoles, searchUsers, share, unshare,} from '@/services/share';

interface ShareSettingModalProps {
  open: boolean;
  resourceType: string;
  resourceId: number;
  resourceName: string;
  onClose: () => void;
}

type ShareTargetType = 'USER' | 'ROLE';
type ResourcePermission = ArticlePermission | CatalogPermission;
type PermissionOption<T extends string> = { label: string; value: T };

const ARTICLE_PERMISSION_OPTIONS: PermissionOption<ArticlePermission>[] = [
  {label: '可读', value: 'READ'},
  {label: '可批注', value: 'COMMENT'},
  {label: '可编辑', value: 'READ_WRITE'},
  {label: '完全控制', value: 'FULL_CONTROL'},
];

const CATALOG_PERMISSION_OPTIONS: PermissionOption<CatalogPermission>[] = [
  {label: '可访问', value: 'ACCESS'},
  {label: '可新建子内容', value: 'CREATE_CHILD'},
  {label: '完全控制', value: 'FULL_CONTROL'},
];

const TARGET_TYPE_OPTIONS: { label: string; value: ShareTargetType }[] = [
  { label: '用户', value: 'USER' },
  { label: '角色', value: 'ROLE' },
];

export default function ShareSettingModal({
  open,
  resourceType,
  resourceId,
  resourceName,
  onClose,
}: ShareSettingModalProps) {
  const { message } = App.useApp();
  const isCatalog = resourceType === 'CATALOG';
  const [existingShares, setExistingShares] = useState<ShareRecord[]>([]);
  const [targetType, setTargetType] = useState<ShareTargetType>('USER');
  const [selectedTargets, setSelectedTargets] = useState<string[]>([]);
  const [permission, setPermission] = useState<ResourcePermission>(
    isCatalog ? 'ACCESS' : 'READ',
  );
  const [articlePermission, setArticlePermission] = useState<ArticlePermission>('READ');
  const [saving, setSaving] = useState(false);
  const [targetOptions, setTargetOptions] = useState<SelectProps['options']>(
    [],
  );

  const resourcePermissionOptions = useMemo<
    PermissionOption<ResourcePermission>[]
  >(
    () => (isCatalog ? CATALOG_PERMISSION_OPTIONS : ARTICLE_PERMISSION_OPTIONS),
    [isCatalog],
  );

  const refreshShares = useCallback(async () => {
    const data = await listShares(resourceType, resourceId);
    setExistingShares(Array.isArray(data) ? data : []);
  }, [resourceType, resourceId]);

  const loadTargetOptions = useCallback(
    async (keyword: string) => {
      try {
        if (targetType === 'ROLE') {
          const roles = await searchRoles(keyword);
          setTargetOptions(
            (roles ?? []).map((role) => ({
              label: role.roleName
                ? `${role.roleName} (${role.roleCode})`
                : role.roleCode,
              value: role.roleCode,
            })),
          );
          return;
        }

        const users = await searchUsers(keyword);
        setTargetOptions(
          (users ?? []).map((user) => ({
            label: user.email
              ? `${user.userName} (${user.email})`
              : user.userName,
            value: user.userName,
          })),
        );
      } catch {
        setTargetOptions([]);
      }
    },
    [targetType],
  );

  useEffect(() => {
    if (!open || !resourceId) return;
    refreshShares().catch(() => message.error('加载分享列表失败'));
  }, [open, resourceId, refreshShares, message]);

  useEffect(() => {
    if (!open) return;
    setPermission(
      isCatalog ? 'ACCESS' : 'READ',
    );
    setArticlePermission('READ');
    setSelectedTargets([]);
  }, [isCatalog, open]);

  useEffect(() => {
    if (!open) return;
    setSelectedTargets([]);
    loadTargetOptions('').then();
  }, [open, targetType, loadTargetOptions]);

  const handleAddTargets = useCallback(async () => {
    if (selectedTargets.length === 0) {
      message.warning(targetType === 'ROLE' ? '请选择角色' : '请选择用户');
      return;
    }
    setSaving(true);
    try {
      await share({
        resourceType: resourceType as 'CATALOG' | 'ARTICLE',
        resourceId,
        targetType,
        targetUsers: targetType === 'USER' ? selectedTargets : undefined,
        targetRoles: targetType === 'ROLE' ? selectedTargets : undefined,
        permission,
        articlePermission: isCatalog ? articlePermission : undefined,
      });
      message.success('分享成功');
      await refreshShares();
      setSelectedTargets([]);
    } catch {
      message.error('分享失败');
    } finally {
      setSaving(false);
    }
  }, [
    selectedTargets,
    message,
    targetType,
    resourceType,
    resourceId,
    permission,
    isCatalog,
    articlePermission,
    refreshShares,
  ]);

  const handleRemoveTarget = useCallback(
    async (record: ShareRecord) => {
      const type = record.targetType ?? 'USER';
      const target = type === 'ROLE' ? record.targetRole : record.targetUser;
      if (!target) return;
      try {
        await unshare(resourceType, resourceId, type, target);
        message.success(`已移除 ${target} 的权限`);
        await refreshShares();
      } catch {
        message.error('移除失败');
      }
    },
    [resourceType, resourceId, message, refreshShares],
  );

  const handleChangePermission = useCallback(
    async (
      record: ShareRecord,
      nextPermission: ArticlePermission | CatalogPermission,
      nextArticlePermission?: ArticlePermission,
    ) => {
      const type = record.targetType ?? 'USER';
      const target = type === 'ROLE' ? record.targetRole : record.targetUser;
      if (!target) return;

      try {
        await share({
          resourceType: resourceType as 'CATALOG' | 'ARTICLE',
          resourceId,
          targetType: type,
          targetUsers: type === 'USER' ? [target] : undefined,
          targetRoles: type === 'ROLE' ? [target] : undefined,
          permission: nextPermission,
          articlePermission: isCatalog ? nextArticlePermission : undefined,
        });
        await refreshShares();
        message.success('权限已更新');
      } catch {
        message.error('权限更新失败');
      }
    },
    [message, resourceId, resourceType, isCatalog, refreshShares],
  );

  const renderTargetName = (record: ShareRecord) => {
    const type = record.targetType ?? 'USER';
    const target = type === 'ROLE' ? record.targetRole : record.targetUser;
    return (
      <Space size={6}>
        <Tag color={type === 'ROLE' ? 'blue' : 'default'}>
          {type === 'ROLE' ? '角色' : '用户'}
        </Tag>
        <span>{target}</span>
      </Space>
    );
  };

  return (
    <Modal
      title={`分享设置 - "${resourceName}"`}
      open={open}
      onCancel={onClose}
      footer={null}
      width={680}
      destroyOnHidden
    >
      {existingShares.length > 0 && (
        <div className="mb-4">
          <div className="mb-2 text-[13px] font-medium">已添加的权限</div>
          {existingShares.map((record) => (
            <div
              key={record.id}
              className="flex items-center justify-between gap-3 border-b border-[#f5f5f5] py-1.5"
            >
              {renderTargetName(record)}
              <Space>
                <Select<ResourcePermission>
                  size="small"
                  value={record.permission}
                  className="w-[150px]"
                  options={resourcePermissionOptions}
                  onChange={(value) =>
                    handleChangePermission(
                      record,
                      value,
                      (record.articlePermission as
                        | ArticlePermission
                        | undefined) ?? 'READ',
                    )
                  }
                />
                {isCatalog && (
                  <Select<ArticlePermission>
                    size="small"
                    value={
                      (record.articlePermission as
                        | ArticlePermission
                        | undefined) ?? 'READ'
                    }
                    className="w-32"
                    options={ARTICLE_PERMISSION_OPTIONS}
                    onChange={(value) =>
                      handleChangePermission(record, record.permission, value)
                    }
                  />
                )}
                <Button
                  size="small"
                  danger
                  type="link"
                  onClick={() => handleRemoveTarget(record)}
                >
                  移除
                </Button>
              </Space>
            </div>
          ))}
        </div>
      )}

      <div className="mb-3">
        <div className="mb-2 text-[13px] font-medium">添加权限</div>
        <Space.Compact className="w-full">
          <Segmented
            value={targetType}
            options={TARGET_TYPE_OPTIONS}
            onChange={(value) => setTargetType(value as ShareTargetType)}
          />
          <Select<string[]>
            mode="multiple"
            className="w-full"
            placeholder={
              targetType === 'ROLE'
                ? '输入角色编码或名称搜索'
                : '输入用户名或邮箱搜索'
            }
            value={selectedTargets}
            onChange={(values) => setSelectedTargets(values)}
            showSearch={{filterOption: false, onSearch: loadTargetOptions}}
            options={targetOptions}
          />
        </Space.Compact>
      </div>

      <Space className="mb-4 w-full" align="start">
        <div className="w-[150px]">
          <div className="mb-2 text-[13px] font-medium">
            {isCatalog ? '目录权限' : '文章权限'}
          </div>
          <Select<ResourcePermission>
            className="w-full"
            value={permission}
            onChange={(value) => setPermission(value)}
            options={resourcePermissionOptions}
          />
        </div>
        {isCatalog && (
          <div className="w-[130px]">
            <div className="mb-2 text-[13px] font-medium">已有文章权限</div>
            <Select<ArticlePermission>
              className="w-full"
              value={articlePermission}
              onChange={(value) => setArticlePermission(value)}
              options={ARTICLE_PERMISSION_OPTIONS}
            />
          </div>
        )}
      </Space>

      <div className="flex items-center justify-between">
        {resourceType === 'ARTICLE' ? (
          <Button
            icon={<CopyOutlined />}
            onClick={async () => {
              const url = `${window.location.origin}/Learn?articleId=${resourceId}`;
              try {
                await navigator.clipboard.writeText(url);
                message.success('文章链接已复制到剪贴板');
              } catch {
                message.error('复制失败');
              }
            }}
          >
            复制文章链接
          </Button>
        ) : (
          <span />
        )}
        <Space>
          <Button onClick={onClose}>关闭</Button>
          <Button type="primary" onClick={handleAddTargets} loading={saving}>
            保存
          </Button>
        </Space>
      </div>
    </Modal>
  );
}
