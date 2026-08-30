import React, {FC, ReactNode, useMemo} from 'react';
import {Avatar, Divider, Popover, Tag, Tooltip} from 'antd';
import {
  CalendarOutlined,
  ClockCircleOutlined,
  FileTextOutlined,
  FolderOutlined,
  TagOutlined,
  UserOutlined,
} from '@ant-design/icons';
import {createStyles} from 'antd-style';
import {Dayjs} from 'dayjs';
import type {AlignType} from '@rc-component/trigger';
import {ArticleType, articleTypeOptions} from "@/types/rt.type";
import {renderCover, renderHighlightedText} from "./ArticleCard";
import {getColorByIndex} from "@/utils/colorUtil";
import {loadArticleTagMap} from "@/pages/User/login/initialBaseInfo";

export interface ArticlePopoverProps {
  width?: number;
  title?: string;
  cover?: string;
  breadcrumb?: string;
  summary?: string;
  chunkId?: string;
  highlights?: string[];
  characterCount?: number;
  createBy?: string;
  createTime?: Dayjs;
  updateBy?: string;
  updateTime?: Dayjs;
  tags?: number[]; // TODO 后端控制只有自己的文章才显示
  catalogId?: number; // TODO 后端控制只有自己的文章才显示
  author?: string;
  articleType?: ArticleType;
  children?: ReactNode;
  align?: AlignType;
}

// ── Styles 样式 ──
const useStyles = createStyles(({token, css}, {width}: { width: number | undefined }) => ({
  popoverWrapper: css`
    .ant-popover-inner {
      padding: 0;
      border-radius: ${token.borderRadiusLG}px;
      overflow: hidden;
      box-shadow: ${token.boxShadowSecondary};
    }

    .ant-popover-inner-content {
      padding: 0;
    }
  `,

  card: css`
    width: ${width || 360}px;
    background: ${token.colorBgContainer};
    border-radius: ${token.borderRadiusLG}px;
    overflow: hidden;
  `,

  coverWrapper: css`
    position: relative;
    width: 100%;
    height: 160px;
    overflow: hidden;

    &::after {
      content: '';
      position: absolute;
      bottom: 0;
      left: 0;
      right: 0;
      height: 60px;
      background: linear-gradient(to bottom, transparent, rgba(0, 0, 0, 0.45));
    }
  `,

  articleTypeBadge: css`
    position: absolute;
    top: 10px;
    right: 10px;
    z-index: 1;
  `,

  body: css`
    padding: 14px 16px 6px;
  `,

  titleRow: css`
    display: flex;
    align-items: flex-start;
    gap: 8px;
    margin-bottom: 6px;
  `,

  titleIcon: css`
    font-size: 15px;
    color: ${token.colorPrimary};
    margin-top: 2px;
    flex-shrink: 0;
  `,

  title: css`
    font-size: 15px;
    font-weight: 600;
    color: ${token.colorText};
    line-height: 1.5;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  `,

  breadcrumb: css`
    display: flex;
    align-items: center;
    gap: 4px;
    font-size: 11px;
    color: ${token.colorTextTertiary};
    margin-bottom: 8px;

    .anticon {
      font-size: 11px;
    }
  `,

  divider: css`
    margin: 8px 0;
  `,

  summary: css`
    font-size: 12px;
    color: ${token.colorTextSecondary};
    line-height: 1.7;
    display: -webkit-box;
    -webkit-line-clamp: 3;
    -webkit-box-orient: vertical;
    overflow: hidden;
    margin-bottom: 8px;
  `,

  highlightSection: css`
    background: ${token.colorFillQuaternary};
    border-left: 3px solid ${token.colorPrimary};
    border-radius: 0 ${token.borderRadius}px ${token.borderRadius}px 0;
    padding: 8px 10px;
    margin-bottom: 8px;
    font-size: 12px;
    color: ${token.colorTextSecondary};
    line-height: 1.7;
  `,

  highlightLabel: css`
    font-size: 11px;
    font-weight: 600;
    color: ${token.colorPrimary};
    margin-bottom: 4px;
    letter-spacing: 0.5px;
  `,

  footer: css`
    padding: 8px 16px 12px;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 10px;
    border-top: 1px solid ${token.colorBorderSecondary};
  `,

  metaItem: css`
    display: flex;
    align-items: center;
    gap: 4px;
    font-size: 11px;
    color: ${token.colorTextTertiary};

    .anticon {
      font-size: 11px;
    }
  `,

  tagsRow: css`
    display: flex;
    flex-wrap: wrap;
    gap: 4px;
    padding: 0 16px 10px;
  `,

  tag: css`
    font-size: 11px;
    padding: 0 6px;
    border-radius: ${token.borderRadiusSM}px;
    line-height: 20px;
    height: 20px;
  `,
}));

// ── 主组件 ──
const ArticlePopover: FC<ArticlePopoverProps> = ({
                                                   width,
                                                   breadcrumb,
                                                   chunkId,
                                                   cover,
                                                   title,
                                                   highlights,
                                                   summary,
                                                   children,
                                                   align,
                                                   author,
                                                   createTime,
                                                   updateTime,
                                                   tags,
                                                   articleType,
                                                   characterCount,
                                                 }) => {
  const articleTagMap: Record<number, string> = loadArticleTagMap();
  const {styles, cx} = useStyles({width});

  const typeConfig = articleType
    ? articleTypeOptions.find((item) => item.value === articleType)
    : null;

  const popoverContent = useMemo(() => {
    return (
      <div className={styles.card}>
        {/* ── 封面 ── */}
        {cover ? (
          <div className={styles.coverWrapper}>
            {typeConfig && (
              <div className={styles.articleTypeBadge}>
                <Tag color={typeConfig.color}>{typeConfig.label}</Tag>
              </div>
            )}
            {renderCover(cover, title)}
          </div>
        ) : null}

        <div className={styles.body}>
          {/* 标题 */}
          {title && (
            <div className={styles.titleRow}>
              {!cover && <FileTextOutlined className={styles.titleIcon}/>}
              <span className={styles.title}>{title}</span>
              {!cover && typeConfig && (
                <Tag
                  color={typeConfig.color}
                  style={{flexShrink: 0, marginTop: 2}}
                >
                  {typeConfig.label}
                </Tag>
              )}
            </div>
          )}

          {/* Breadcrumb TODO 加上目录翻译 */}
          {breadcrumb && (
            <div className={styles.breadcrumb}>
              <FolderOutlined/>
              <span>{breadcrumb}</span>
            </div>
          )}

          <Divider className={styles.divider}/>

          {/* 总结 */}
          {summary && <p className={styles.summary}>{summary}</p>}

          {/* Highlights */}
          {highlights && highlights.length > 0 && (
            <div className={styles.highlightSection}>
              <div className={styles.highlightLabel}>相关内容</div>
              {renderHighlightedText(highlights, chunkId)}
            </div>
          )}
        </div>

        {/* 标签列表 */}
        {tags && tags.length > 0 && (
          <div className={styles.tagsRow}>
            {tags.map((tag, index) => (
              <Tag
                key={tag}
                icon={<TagOutlined/>}
                color={getColorByIndex(index)}
                className={styles.tag}
              >
                {articleTagMap[tag] || tag || ''}
              </Tag>
            ))}
          </div>
        )}

        {/* ── Footer Meta ── */}
        {(author || createTime || updateTime || characterCount) && (
          <div className={styles.footer}>
            {author && (
              <Tooltip title="作者">
                <span className={styles.metaItem}>
                  <Avatar
                    size={16}
                    icon={<UserOutlined/>}
                    style={{fontSize: 10}}
                  />
                  {author}
                </span>
              </Tooltip>
            )}
            {characterCount !== undefined && (
              <Tooltip title="字数">
                <span className={styles.metaItem}>
                  <FileTextOutlined/>
                  {characterCount.toLocaleString()} 字
                </span>
              </Tooltip>
            )}
            {createTime && (
              <Tooltip title="创建时间">
                <span className={styles.metaItem}>
                  <CalendarOutlined/>
                  {createTime?.format('YYYY-MM-DD HH:mm')}
                </span>
              </Tooltip>
            )}
            {updateTime && (
              <Tooltip title="更新时间">
                <span className={styles.metaItem}>
                  <ClockCircleOutlined/>
                  {updateTime?.format('YYYY-MM-DD HH:mm')}
                </span>
              </Tooltip>
            )}
          </div>
        )}
      </div>
    );
  }, [
    cover,
    title,
    breadcrumb,
    summary,
    highlights,
    chunkId,
    tags,
    author,
    createTime,
    updateTime,
    characterCount,
    articleType,
  ]);

  return (
    <Popover
      content={popoverContent}
      align={align}
      classNames={{
        root: styles.popoverWrapper,
      }}
      arrow={false}
    >
      {children}
    </Popover>
  );
};

export default ArticlePopover;
