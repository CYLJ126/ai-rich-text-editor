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
  if (savingState === undefined) return {label: '未知', color: 'default'};
  const tags: Record<ArticleSaveStatus, { label: string; color: string }> = {
    0: {label: '未变化', color: 'cyan'},
    1: {label: '保存中', color: 'lime'},
    2: {label: '保存成功', color: 'green'},
    3: {label: '保存失败', color: 'volcano'},
    4: {label: '未保存', color: 'gold'},
  };
  return tags[savingState];
}

function getPermissionTag(permission?: string) {
  const tags: Record<string, { label: string; color: string }> = {
    READ: {label: '可读', color: 'gold'},
    COMMENT: {label: '可批注', color: 'lime'},
    READ_WRITE: {label: '可编辑', color: 'green'},
    FULL_CONTROL: {label: '完全控制', color: 'cyan'},
  };
  return permission
    ? (tags[permission] ?? {label: permission, color: 'default'})
    : {label: '未知', color: 'default'};
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
        {createBy && <span>作者：{createBy}</span>}
        {createTime && <span>创建时间：{dayjs(createTime).format('YYYY-MM-DD HH:mm:ss')}</span>}
        {updateBy && <span>最后修改人：{updateBy}</span>}
        {updateTime && <span>最后修改时间：{dayjs(updateTime).format('YYYY-MM-DD HH:mm:ss')}</span>}
      </div>
      {/* 居右显示 */}
      <div className="absolute right-1.25 top-px flex items-center">
        <Tag
          color={savingStateTag.color}
          style={{marginRight: 12, fontSize: 13, marginTop: 3}}
        >状态：{savingStateTag.label}</Tag>

        {effectivePermission && <Tag
          style={{marginRight: 12, fontSize: 13, marginTop: 3}}
          color={permissionTag.color}
        >权限：{permissionTag.label}</Tag>}

        {characterCount && <span className="mr-3">字数：{`${characterCount}/${MAX_CHARACTER_COUNT}`}</span>}

        {/* 不显示文章元数据 */}
        <CloseOutlined onClick={() => onClose?.()}/>
      </div>
    </div>
  );
};

export default ArticleMetaInfo;
