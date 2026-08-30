import {i18nText} from '@/utils/i18n';
import React, {useEffect, useState} from "react";
import {Tag} from "antd";
import {MAX_CHARACTER_COUNT} from "@/components/Article/components";
import {CloseOutlined} from "@ant-design/icons";
import dayjs, {Dayjs} from "dayjs";
import {ArticleSaveStatus} from "@/types/rt.article.type";

export interface ArticleMetaInfoProps {
  createBy?: string | undefined;
  createTime?: string | Dayjs | undefined;
  updateBy?: string | undefined;
  updateTime?: string | Dayjs | undefined;
  effectivePermission?: string | undefined;
  savingState?: ArticleSaveStatus;
  characterCount?: number | undefined;
  onClose?: () => void;
}

function getSavingStateTag(savingState?: ArticleSaveStatus) {
  if (savingState === undefined) return {label: i18nText("app.article.article.articlemetainfo.c41cdef1"), color: 'default'};
  const tags: Record<ArticleSaveStatus, { label: string; color: string }> = {
    0: {label: i18nText("app.article.article.articlemetainfo.dc81e0e5"), color: 'cyan'},
    1: {label: i18nText("app.article.article.articlemetainfo.dc6acae2"), color: 'lime'},
    2: {label: i18nText("app.article.article.articlemetainfo.d90f1a4a"), color: 'green'},
    3: {label: i18nText("app.article.article.articlemetainfo.77eb28ec"), color: 'volcano'},
    4: {label: i18nText("app.article.article.articlemetainfo.69110004"), color: 'gold'},
  };
  return tags[savingState];
}

function getPermissionTag(permission?: string) {
  const tags: Record<string, { label: string; color: string }> = {
    READ: {label: i18nText("app.article.article.articlemetainfo.c79d59f9"), color: 'gold'},
    COMMENT: {label: i18nText("app.article.article.articlemetainfo.7cbe191f"), color: 'lime'},
    READ_WRITE: {label: i18nText("app.article.article.articlemetainfo.24e1530f"), color: 'green'},
    FULL_CONTROL: {label: i18nText("app.article.article.articlemetainfo.b313084b"), color: 'cyan'},
  };
  return permission
    ? (tags[permission] ?? {label: permission, color: 'default'})
    : {label: i18nText("app.article.article.articlemetainfo.c41cdef1"), color: 'default'};
}

const ArticleMetaInfo: React.FC<ArticleMetaInfoProps> = ({
                                                           createBy,
                                                           createTime,
                                                           updateBy,
                                                           updateTime,
                                                           effectivePermission,
                                                           characterCount,
                                                           savingState = 0,
                                                           onClose
                                                         }) => {
  const [savingStateTag, setSavingStateTag] = useState(getSavingStateTag(savingState));
  const [permissionTag, setPermissionTag] = useState(getPermissionTag(effectivePermission));

  useEffect(() => {
    setSavingStateTag(getSavingStateTag(savingState));
  }, [savingState]);

  useEffect(() => {
    setPermissionTag(getPermissionTag(effectivePermission));
  }, [effectivePermission]);

  return (
    <div className="relative flex items-center justify-start gap-6 h-6.5 px-4 text-sm rounded">
      {/* 居左显示 */}
      <div className="flex items-center space-x-4">
        {createBy && <span>{i18nText("app.article.article.articlemetainfo.8cca2c36")}{createBy}</span>}
        {createTime && <span>{i18nText("app.article.article.articlemetainfo.d59f41e1")}{dayjs(createTime).format('YYYY-MM-DD HH:mm:ss')}</span>}
        {updateBy && <span>{i18nText("app.article.article.articlemetainfo.88245809")}{updateBy}</span>}
        {updateTime && <span>{i18nText("app.article.article.articlemetainfo.3add4155")}{dayjs(updateTime).format('YYYY-MM-DD HH:mm:ss')}</span>}
      </div>
      {/* 居右显示 */}
      <div className="absolute right-1.25 top-px flex items-center">
        <Tag
          color={savingStateTag.color}
          style={{marginRight: 12, fontSize: 13, marginTop: 3}}
        >{i18nText("app.article.article.articlemetainfo.9e428673")}{savingStateTag.label}</Tag>

        {effectivePermission && <Tag
          style={{marginRight: 12, fontSize: 13, marginTop: 3}}
          color={permissionTag.color}
        >{i18nText("app.article.article.articlemetainfo.3b0cc08e")}{permissionTag.label}</Tag>}

        {characterCount && <span className="mr-3">{i18nText("app.article.article.articlemetainfo.bcf8f5f6")}{`${characterCount}/${MAX_CHARACTER_COUNT}`}</span>}

        {/* 不显示文章元数据 */}
        <CloseOutlined onClick={() => onClose?.()}/>
      </div>
    </div>
  );
};

export default ArticleMetaInfo;
