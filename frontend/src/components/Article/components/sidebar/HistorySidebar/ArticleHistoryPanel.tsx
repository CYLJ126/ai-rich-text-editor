import {i18nText} from '@/utils/i18n';
import { ClockCircleOutlined, UserOutlined } from '@ant-design/icons';
import { List, Skeleton, Tag, Typography } from 'antd';
import dayjs from 'dayjs';
import React, { useEffect, useState } from 'react';
import { listArticleHistory } from '@/services/ant-design-pro/richText';
import type { ArticleHistoryVersion, ArticleInfoType } from '@/types/rt.type';
import EmptySidebar from '../EmptySidebar';

export interface ArticleHistoryPanelProps {
  article?: ArticleInfoType;
  active: boolean;
  selectedId?: number;
  onLoaded: (versions: ArticleHistoryVersion[]) => void;
  onSelect: (version: ArticleHistoryVersion) => void;
}

const ArticleHistoryPanel: React.FC<ArticleHistoryPanelProps> = ({
  article,
  active,
  selectedId,
  onLoaded,
  onSelect,
}) => {
  const [versions, setVersions] = useState<ArticleHistoryVersion[]>([]);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    setVersions([]);
    onLoaded([]);
    setLoading(false);
    if (!article?.id) {
      return;
    }
    if (!active) return;
    let disposed = false;
    setLoading(true);
    listArticleHistory(article.id)
      .then((data) => {
        if (disposed) return;
        const nextVersions = data ?? [];
        setVersions(nextVersions);
        onLoaded(nextVersions);
      })
      .catch(() => {
        if (disposed) return;
        setVersions([]);
        onLoaded([]);
      })
      .finally(() => {
        if (!disposed) setLoading(false);
      });
    return () => {
      disposed = true;
    };
  }, [active, article?.id, article?.updateTime, onLoaded]);

  if (!article?.id) {
    return <EmptySidebar />;
  }

  return (
    <div style={{ height: '100%', overflow: 'auto', scrollbarWidth: 'thin' }}>
      <div style={{ padding: '12px 12px 8px' }}>
        <Typography.Title level={5} style={{ margin: 0 }}>
          {i18nText("app.article.historysidebar.articlehistorypanel.4d05751e")}
        </Typography.Title>
        <Typography.Text type="secondary">
          {i18nText("app.article.historysidebar.articlehistorypanel.6a9c490d")}
        </Typography.Text>
      </div>
      <div style={{ padding: '0 10px 12px' }}>
        <div
          style={{
            padding: '10px 12px',
            marginBottom: 10,
            border: '1px solid var(--ant-color-success-border)',
            borderRadius: 8,
            background: 'var(--ant-color-success-bg)',
          }}
        >
          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'space-between',
              gap: 8,
            }}
          >
            <Typography.Text ellipsis strong>
              {article.title || i18nText("app.article.historysidebar.articlehistorypanel.dd95649a")}
            </Typography.Text>
            <Tag color="green" style={{ marginInlineEnd: 0 }}>
              {i18nText("app.article.historysidebar.articlehistorypanel.9a1879c4")}
            </Tag>
          </div>
          <div
            style={{ color: 'var(--ant-color-text-secondary)', fontSize: 12 }}
          >
            <UserOutlined /> {article.updateBy || article.author || i18nText("app.article.historysidebar.articlehistorypanel.3cc868ab")}
          </div>
          <div
            style={{ color: 'var(--ant-color-text-tertiary)', fontSize: 12 }}
          >
            <ClockCircleOutlined />{' '}
            {article.updateTime
              ? dayjs(article.updateTime).format('YYYY-MM-DD HH:mm:ss')
              : i18nText("app.article.historysidebar.articlehistorypanel.cc1ef391")}
          </div>
        </div>
        <List
          size="small"
          split={false}
          dataSource={versions}
          loading={loading}
          locale={{
            emptyText: loading ? <Skeleton active /> : i18nText("app.article.historysidebar.articlehistorypanel.d6c69180"),
          }}
          renderItem={(version) => (
            <List.Item
              onClick={() => onSelect(version)}
              style={{
                cursor: 'pointer',
                padding: '10px 12px',
                marginBottom: 8,
                border: `1px solid ${
                  selectedId === version.id
                    ? 'var(--ant-color-primary-border)'
                    : 'var(--ant-color-border-secondary)'
                }`,
                borderRadius: 8,
                background:
                  selectedId === version.id
                    ? 'var(--ant-color-primary-bg)'
                    : 'var(--ant-color-bg-container)',
                boxShadow: '0 1px 2px rgba(0, 0, 0, 0.04)',
              }}
            >
              <div style={{ minWidth: 0, width: '100%' }}>
                <div
                  style={{
                    display: 'flex',
                    alignItems: 'center',
                    justifyContent: 'space-between',
                    gap: 8,
                  }}
                >
                  <Typography.Text ellipsis strong>
                    {version.title || i18nText("app.article.historysidebar.articlehistorypanel.dd95649a")}
                  </Typography.Text>
                  <Tag color="blue" style={{ marginInlineEnd: 0 }}>
                    V{version.versionNo}
                  </Tag>
                </div>
                <div
                  style={{
                    color: 'var(--ant-color-text-secondary)',
                    fontSize: 12,
                  }}
                >
                  <UserOutlined /> {version.modifiedBy || i18nText("app.article.historysidebar.articlehistorypanel.3cc868ab")}
                </div>
                <div
                  style={{
                    color: 'var(--ant-color-text-tertiary)',
                    fontSize: 12,
                  }}
                >
                  <ClockCircleOutlined />{' '}
                  {dayjs(version.modifiedTime).format('YYYY-MM-DD HH:mm:ss')}
                </div>
              </div>
            </List.Item>
          )}
        />
      </div>
    </div>
  );
};

export default ArticleHistoryPanel;
