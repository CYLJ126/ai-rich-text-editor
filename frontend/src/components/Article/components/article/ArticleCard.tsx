import {i18nText} from '@/utils/i18n';
import React, {FC, useCallback} from 'react';
import {Card, Checkbox, Tooltip, Typography} from "antd";
import {createStyles} from "antd-style";
import {ActiveSelectedInfo, ArticleType} from "@/types/rt.type";
import {Dayjs} from "dayjs";
import {ExportOutlined, FileTextOutlined} from "@ant-design/icons";
import ArticlePopover from "./ArticlePopover";

export interface ArticleCardProps {
  showType: 'card' | 'list' | 'popover';
  key: number | string;
  id: number;
  chunkId?: string;
  nodeId?: string;
  sectionHeading?: string;
  sectionHeadingId?: string;
  breadcrumb?: string;
  content?: string;
  cover?: string;
  title?: string;
  summary?: string;
  highlights?: string[];
  characterCount?: number;
  createBy?: string;
  createTime?: Dayjs;
  updateBy?: string;
  updateTime?: Dayjs;
  tags?: number[];
  catalogId?: number;
  author?: string;
  articleType?: ArticleType;
  onArticleSelect?: (activeSelectedInfo: ActiveSelectedInfo) => void | Promise<void>;
}

const {Title, Paragraph} = Typography;

const useStyles = createStyles(({token}) => ({
  cardCard: {
    '& .ant-card-body': {
      padding: '16px',
    },
  },
  listCard: {
    '& .ant-card-body': {
      display: 'grid',
      gridTemplateColumns: '180px minmax(0, 1fr)',
      gap: '16px',
      alignItems: 'center',
    },
  },
  minimalistsCard: {
    '& .ant-card-head': {
      minHeight: '30px !important',
      maxHeight: '30px !important',
      paddingLeft: '10px',
      paddingRight: '10px',
    },
    '& .ant-card-body': {
      padding: '10px',
    },
  },
}));

// ── 封面渲染 ──
export const renderCover = (cover?: string, title?: string) => (
  <div className="flex aspect-video w-full items-center justify-center overflow-hidden bg-[#f5f7fb] text-[#8c8c8c]">
    {cover ? (
      <img
        src={cover}
        alt={title || i18nText("app.article.article.articlecard.63d211db")}
        className="h-full w-full object-cover"
      />
    ) : (
      <FileTextOutlined className="text-[32px]"/>
    )}
  </div>
);

// ── 高亮文本渲染 ──
export const renderHighlightedText = (highlights?: string[], chunkId?: string) => {
  if (!highlights || highlights.length === 0) {
    return <span>{i18nText("app.article.article.articlecard.a934b067")}</span>;
  }

  /**
   * 将单条包含 <em> 标签的字符串解析为 React 节点数组
   * 例："从<em>苏轼</em>的作品" => ["从", <mark>苏轼</mark>, "的作品"]
   */
  const parseEmToHighlight = (
    text: string,
    baseKey: string,
  ): React.ReactNode[] => {
    // 按 <em>...</em> 分割，捕获组保留分隔符内容
    const parts = text.split(/(<em>.*?<\/em>)/g);
    let offset = 0;
    return parts.map((part) => {
      const partOffset = offset;
      offset += part.length;
      const emMatch = part.match(/^<em>(.*?)<\/em>$/);
      if (emMatch) {
        // <em> 包裹的内容 → 高亮样式
        return (
          <mark
            key={`${baseKey}-em-${partOffset}-${emMatch[1]}`}
            className="bg-[#fff1b8] text-[#ad4e00]"
          >
            {emMatch[1]}
          </mark>
        );
      }
      // 普通文本
      return part ? (
        <span key={`${baseKey}-text-${partOffset}-${part}`}>{part}</span>
      ) : null;
    });
  };

  return (
    <>
      {highlights.map((content: string, index: number) => {
        const baseKey = `${chunkId}-${index}`;
        return (
          // 每条 highlight 之间用省略号分隔，与 ES 高亮片段展示保持一致
          <span key={baseKey}>
              {parseEmToHighlight(content, baseKey)}
            {index < highlights.length - 1 && (
              <span className="mx-1 text-[#999]">...</span>
            )}
            </span>
        );
      })}
    </>
  );
};

// ── 文章展示组件 ──
const ArticleCard: FC<ArticleCardProps> = ({
                                             showType,
                                             id,
                                             chunkId,
                                             nodeId,
                                             sectionHeading,
                                             sectionHeadingId,
                                             breadcrumb,
                                             content,
                                             cover,
                                             title,
                                             highlights,
                                             summary,
                                             characterCount,
                                             createBy,
                                             createTime,
                                             updateBy,
                                             updateTime,
                                             tags,
                                             catalogId,
                                             author,
                                             articleType,
                                             onArticleSelect
                                           }) => {
  const {styles} = useStyles();

  if (showType === 'card') {
    return (
      <Card
        hoverable
        className={`${styles.cardCard} w-[calc(25%-11.25px)] min-w-[200px] flex-shrink-0`}
        cover={renderCover(cover, title)}
        onClick={() => onArticleSelect?.({checked: true, articleId: id, chunkId})
        }
      >
        <Title level={5} ellipsis>{title || i18nText("app.article.article.articlecard.90c2ecec")}</Title>

        <Paragraph type="secondary" ellipsis={{rows: 2}} className="!mb-0">
          {summary || content || i18nText("app.article.article.articlecard.a934b067")}
        </Paragraph>
      </Card>
    )
  } else if (showType === 'list') {
    return (
      <Card
        hoverable
        onClick={() => onArticleSelect?.({
          checked: true,
          articleId: id,
          chunkId,
          nodeId,
          sectionHeading,
          sectionHeadingId
        })}
        className={`${styles.listCard} w-[80%] mb-4`}
      >
        {renderCover(cover, title)}
        <div className="min-w-0">
          <Title level={5} ellipsis className="!mb-2">
            {breadcrumb || title || i18nText("app.article.article.articlecard.90c2ecec")}
          </Title>

          <Paragraph type="secondary" ellipsis={{rows: 2}} className="!mb-0">
            {renderHighlightedText(highlights, chunkId)}
          </Paragraph>
        </div>
      </Card>
    )
  }

  const handleOpenArticle = useCallback(() => {
    window.open(`/Writing/BasicWriting/?articleId=${id}`, '_blank');
  }, [id])

  return <Card
    title={title || i18nText("app.article.article.articlecard.90c2ecec")}
    className={`w-full ${styles.minimalistsCard} `}
    extra={<div className="flex items-center gap-1">
      <Tooltip title={i18nText("app.article.article.articlecard.4ff7fa02")}>
        <Checkbox onChange={(e) => {
          onArticleSelect?.({checked: e.target.checked, articleId: id, chunkId});
        }}/>
      </Tooltip>
      <Tooltip title={i18nText("app.article.article.articlecard.cf59b962")}>
        <ExportOutlined
          style={{fontSize: 16, color: 'var(--ant-color-border)'}}
          onClick={handleOpenArticle}/>
      </Tooltip>
    </div>}>
    <ArticlePopover
      width={500}
      title={title || i18nText("app.article.article.articlecard.90c2ecec")}
      breadcrumb={breadcrumb}
      cover={cover}
      chunkId={chunkId}
      highlights={highlights}
      summary={summary}
      characterCount={characterCount}
      createBy={createBy}
      createTime={createTime}
      updateBy={updateBy}
      updateTime={updateTime}
      tags={tags}
      catalogId={catalogId}
      author={author}
      articleType={articleType}
    >
      <Paragraph type="secondary" ellipsis={{rows: 2}} className="!mb-0">
        {highlights ? renderHighlightedText(highlights, chunkId) : summary || content || i18nText("app.article.article.articlecard.a934b067")}
      </Paragraph>
    </ArticlePopover>
  </Card>
}

export default ArticleCard;
