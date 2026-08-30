import {i18nText} from '@/utils/i18n';
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
  {label: i18nText("app.article.sidebar.sharesettingmodal.4993527a"), value: 'READ'},
  {label: i18nText("app.article.sidebar.sharesettingmodal.056e97c7"), value: 'COMMENT'},
  {label: i18nText("app.article.sidebar.sharesettingmodal.a1891177"), value: 'READ_WRITE'},
  {label: i18nText("app.article.sidebar.sharesettingmodal.2f7bb1b0"), value: 'FULL_CONTROL'},
];

const CATALOG_PERMISSION_OPTIONS: PermissionOption<CatalogPermission>[] = [
  {label: i18nText("app.article.sidebar.sharesettingmodal.cfb12895"), value: 'ACCESS'},
  {label: i18nText("app.article.sidebar.sharesettingmodal.1ee7ac1c"), value: 'CREATE_CHILD'},
  {label: i18nText("app.article.sidebar.sharesettingmodal.2f7bb1b0"), value: 'FULL_CONTROL'},
];

const TARGET_TYPE_OPTIONS: { label: string; value: ShareTargetType }[] = [
  { label: i18nText("app.article.sidebar.sharesettingmodal.bc122df2"), value: 'USER' },
  { label: i18nText("app.article.sidebar.sharesettingmodal.a95fadad"), value: 'ROLE' },
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
    refreshShares().catch(() => message.error(i18nText("app.article.sidebar.sharesettingmodal.51571f1f")));
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
      message.warning(targetType === 'ROLE' ? i18nText("app.article.sidebar.sharesettingmodal.53a2285b") : i18nText("app.article.sidebar.sharesettingmodal.df36575a"));
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
      message.success(i18nText("app.article.sidebar.sharesettingmodal.edeabb46"));
      await refreshShares();
      setSelectedTargets([]);
    } catch {
      message.error(i18nText("app.article.sidebar.sharesettingmodal.fdb1c8a3"));
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
        message.success(i18nText("app.article.sidebar.sharesettingmodal.e0f5b9c3", {value0: target}));
        await refreshShares();
      } catch {
        message.error(i18nText("app.article.sidebar.sharesettingmodal.3b96ca3d"));
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
        message.success(i18nText("app.article.sidebar.sharesettingmodal.a9234faf"));
      } catch {
        message.error(i18nText("app.article.sidebar.sharesettingmodal.ae4048a6"));
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
          {type === 'ROLE' ? i18nText("app.article.sidebar.sharesettingmodal.a95fadad") : i18nText("app.article.sidebar.sharesettingmodal.bc122df2")}
        </Tag>
        <span>{target}</span>
      </Space>
    );
  };

  return (
    <Modal
      title={i18nText("app.article.sidebar.sharesettingmodal.07b876ab", {value0: resourceName})}
      open={open}
      onCancel={onClose}
      footer={null}
      width={680}
      destroyOnHidden
    >
      {existingShares.length > 0 && (
        <div className="mb-4">
          <div className="mb-2 text-[13px] font-medium">{i18nText("app.article.sidebar.sharesettingmodal.93f4c10e")}</div>
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
                  {i18nText("app.article.sidebar.sharesettingmodal.925ad117")}
                </Button>
              </Space>
            </div>
          ))}
        </div>
      )}

      <div className="mb-3">
        <div className="mb-2 text-[13px] font-medium">{i18nText("app.article.sidebar.sharesettingmodal.110bda0f")}</div>
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
                ? i18nText("app.article.sidebar.sharesettingmodal.13aed24a")
                : i18nText("app.article.sidebar.sharesettingmodal.946c2d4a")
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
            {isCatalog ? i18nText("app.article.sidebar.sharesettingmodal.652f9ab6") : i18nText("app.article.sidebar.sharesettingmodal.0e062413")}
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
            <div className="mb-2 text-[13px] font-medium">{i18nText("app.article.sidebar.sharesettingmodal.5d90aa2f")}</div>
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
                message.success(i18nText("app.article.sidebar.sharesettingmodal.92329e10"));
              } catch {
                message.error(i18nText("app.article.sidebar.sharesettingmodal.f2eca02d"));
              }
            }}
          >
            {i18nText("app.article.sidebar.sharesettingmodal.0f9528ad")}
          </Button>
        ) : (
          <span />
        )}
        <Space>
          <Button onClick={onClose}>{i18nText("app.article.sidebar.sharesettingmodal.61929b75")}</Button>
          <Button type="primary" onClick={handleAddTargets} loading={saving}>
            {i18nText("app.article.sidebar.sharesettingmodal.1cc01d34")}
          </Button>
        </Space>
      </div>
    </Modal>
  );
}
