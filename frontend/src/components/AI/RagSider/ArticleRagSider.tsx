import React, {useCallback, useEffect, useRef, useState} from 'react';
import {ArticleCard, ArticleCardProps, ArticleSearch} from "@/components/Article/components";
import {ActiveSelectedInfo, ArticleSearchParam} from "@/types/rt.type";
import {searchArticlesChunks} from "@/services/ant-design-pro/richText";
import dayjs from "dayjs";
import {Button, Spin} from "antd";
import {VerticalAlignTopOutlined} from "@ant-design/icons";

export type RagSiderProps = {
  onSelect?: (articleIds: any) => void;
}

const PAGE_SIZE = 10;

const ArticleRagSider: React.FC<RagSiderProps> = ({onSelect}) => {
  // 搜索文章列表
  const [articles, setArticles] = useState<ArticleCardProps[]>([]);
  // 已选择的文章分块映射<分块 ID, 文章 ID>
  const articleMapRef = useRef<Map<string, number>>(new Map());
  // 当前页码
  const currentPageRef = useRef<number>(1);
  // 是否还有更多文章
  const [hasMore, setHasMore] = useState<boolean>(false);
  // 控制加载更多
  const hasMoreRef = useRef<boolean>(false);
  const [loadingState, setLoadingState] = useState<'initial' | 'loadMore' | 'idle'>('idle');
  const lastSearchParamRef = useRef<ArticleSearchParam>({});
  const sentinelRef = useRef<HTMLDivElement>(null);
  const isLoadingRef = useRef<boolean>(false);
  const hasSearchedRef = useRef<boolean>(false);
  const scrollTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  // 内部滚动容器 ref
  const listContainerRef = useRef<HTMLDivElement>(null);
  // 回到顶部按钮显示状态
  const [showBackTop, setShowBackTop] = useState<boolean>(false);

  // 搜索文章
  const handleSearch = useCallback(
    async (searchParam: ArticleSearchParam, loadMore: boolean) => {
      if (isLoadingRef.current) return;
      isLoadingRef.current = true;
      setLoadingState(loadMore ? 'loadMore' : 'initial');

      if (!loadMore) {
        lastSearchParamRef.current = searchParam;
        currentPageRef.current = 1;
      }

      try {
        const page = loadMore ? currentPageRef.current + 1 : 1;
        const data = await searchArticlesChunks({
          ...lastSearchParamRef.current,
          current: page,
          size: PAGE_SIZE,
        });

        const more = data.hasMore ?? false;
        setHasMore(more);
        hasMoreRef.current = more;

        const currentResults: ArticleCardProps[] = data.hits.length > 0
          ? data.hits
            .filter((item: any) => item.source)
            .map((item: any) => {
              const chunkInfo = item.source;
              const articleInfo = item.source.article_meta;
              const highlight = item.highlights;
              return {
                showType: 'popover',
                key: chunkInfo.chunk_id,
                id: chunkInfo.article_id,
                chunkId: chunkInfo.chunk_id,
                nodeId: chunkInfo.tiptap_node_id,
                sectionHeading: chunkInfo.section_heading,
                sectionHeadingId: chunkInfo.section_heading_id,
                breadcrumb: chunkInfo.breadcrumb,
                content: chunkInfo.content,
                title: articleInfo.title,
                highlights: highlight.content,
                cover: articleInfo.cover,
                summary: articleInfo.summary,
                characterCount: articleInfo.character_count,
                createBy: articleInfo.create_by,
                createTime: articleInfo.create_time,
                updateBy: articleInfo.update_by,
                updateTime: articleInfo.update_time,
                tags: articleInfo.tag_ids,
                catalogId: articleInfo.catalog_id,
                author: articleInfo.author,
                articleType: articleInfo.article_type,
              };
            })
          : [];

        if (loadMore) {
          setArticles(prev => [...prev, ...currentResults]);
        } else {
          setArticles(currentResults);
        }

        currentPageRef.current = page;

        if (!loadMore) {
          hasSearchedRef.current = true;
        }
      } catch {
        if (!loadMore) {
          setArticles([]);
        }
      } finally {
        isLoadingRef.current = false;
        setLoadingState('idle');
      }
    },
    [],
  );

  // 检查哨兵是否接近底部（相对于内部滚动容器）
  const checkSentinelVisible = useCallback(() => {
    const sentinel = sentinelRef.current;
    const container = listContainerRef.current;
    if (!sentinel || !container) return false;
    if (!hasMoreRef.current || isLoadingRef.current || !hasSearchedRef.current) return false;

    const containerRect = container.getBoundingClientRect();
    const sentinelRect = sentinel.getBoundingClientRect();
    // 哨兵距容器底部 80px 内即触发
    return sentinelRect.top <= containerRect.bottom + 80;
  }, []);

  // 滚动处理：更新回到顶部按钮 + 触底加载
  const handleScroll = useCallback(() => {
    if (scrollTimerRef.current) return;
    scrollTimerRef.current = setTimeout(() => {
      scrollTimerRef.current = null;

      const container = listContainerRef.current;
      if (!container) return;

      // 滚动超过 200px 显示回到顶部按钮
      setShowBackTop(container.scrollTop > 200);

      // 触底加载更多
      if (checkSentinelVisible()) {
        handleSearch(lastSearchParamRef.current, true).then();
      }
    }, 100);
  }, [checkSentinelVisible, handleSearch]);

  // 直接绑定内部滚动容器，无需动态查找
  useEffect(() => {
    const container = listContainerRef.current;
    if (!container) return;

    container.addEventListener('scroll', handleScroll, {passive: true});

    return () => {
      container.removeEventListener('scroll', handleScroll);
      if (scrollTimerRef.current) {
        clearTimeout(scrollTimerRef.current);
      }
    };
  }, [handleScroll]);

  // 回到顶部
  const handleBackTop = useCallback(() => {
    listContainerRef.current?.scrollTo({top: 0, behavior: 'smooth'});
  }, []);

  const handleSearchChange = useCallback(
    (searchParam: ArticleSearchParam) => {
      hasSearchedRef.current = false;
      lastSearchParamRef.current = searchParam;
      setShowBackTop(false);
      handleSearch(searchParam, false).then();
    },
    [handleSearch],
  );

  // 选中文章时触发
  const handleArticleSelect = useCallback(
    ({articleId, chunkId, checked}: ActiveSelectedInfo) => {
      if (!chunkId) return;
      const map = articleMapRef.current;
      if (checked) {
        if (!map.has(chunkId)) {
          map.set(chunkId, articleId);
        }
      } else {
        map.delete(chunkId);
      }
      const articleIds = Array.from(new Set(Array.from(map.values()).filter(Boolean)));
      if (articleIds.length > 0) {
        onSelect?.({articleIds});
      } else {
        onSelect?.({})
      }
    },
    [onSelect],
  );

  const isInitialLoading = loadingState === 'initial';
  const isLoadingMore = loadingState === 'loadMore';

  return (
    // 外层不滚动，撑满父容器高度
    <div className="flex flex-col h-full">

      {/* 搜索栏：固定不滚动 */}
      <div className="flex-shrink-0">
        <ArticleSearch size="medium" onChange={handleSearchChange}/>
      </div>

      {/* 滚动区域：flex-1 撑满剩余高度，relative 给 sticky 按钮提供定位基准 */}
      <div
        ref={listContainerRef}
        className="flex-1 overflow-y-auto scrollbar-none relative"
      >
        <Spin spinning={isInitialLoading} description="搜索中...">
          <div className="flex flex-wrap gap-[10px] justify-center">
            {articles.map((article) => (
              <ArticleCard
                showType={article.showType}
                key={article.key}
                id={article.id}
                chunkId={article.chunkId}
                nodeId={article.nodeId}
                sectionHeading={article.sectionHeading}
                sectionHeadingId={article.sectionHeadingId}
                breadcrumb={article.breadcrumb}
                content={article.content}
                cover={article.cover}
                title={article.title}
                highlights={article.highlights}
                characterCount={article.characterCount}
                createBy={article.createBy}
                createTime={dayjs(article.createTime)}
                updateBy={article.updateBy}
                updateTime={dayjs(article.updateTime)}
                tags={article.tags}
                catalogId={article.catalogId}
                author={article.author}
                articleType={article.articleType}
                summary={article.summary}
                onArticleSelect={handleArticleSelect}
              />
            ))}

            {!isInitialLoading && articles.length === 0 && (
              <div className="w-full text-center text-gray-400 py-10">
                暂无相关文章
              </div>
            )}
          </div>
        </Spin>

        {isLoadingMore && (
          <div className="flex justify-center items-center py-4">
            <Spin size="small"/>
            <span className="ml-2 text-gray-400 text-sm">加载更多...</span>
          </div>
        )}

        {!hasMore && articles.length > 0 && !isInitialLoading && (
          <div className="text-center text-gray-400 text-sm py-4">
            已加载全部结果
          </div>
        )}

        {/* 哨兵元素 */}
        <div ref={sentinelRef} style={{height: 1}}/>

        {/* 回到顶部悬浮按钮，sticky 定位在滚动容器内右下角 */}
        <div
          style={{
            position: 'sticky',
            bottom: 16,
            // 不占布局高度
            height: 0,
            // 贴右侧
            display: 'flex',
            justifyContent: 'flex-end',
            paddingRight: 12,
            zIndex: 10,
            // 淡入淡出动画
            opacity: showBackTop ? 1 : 0,
            transform: showBackTop ? 'translateY(0)' : 'translateY(10px)',
            pointerEvents: showBackTop ? 'auto' : 'none',
            transition: 'opacity 0.25s ease, transform 0.25s ease',
          }}
        >
          <Button
            onClick={handleBackTop}
            style={{
              width: 36,
              height: 36,
              borderRadius: '50%',
              border: 'none',
              cursor: 'pointer',
              backgroundColor: 'var(--ant-color-primary)',
              color: '#fff',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              boxShadow: '0 2px 8px rgba(0,0,0,0.18)',
              // 抵消父 div height:0 带来的视觉偏移
              transform: 'translateY(-36px)',
            }}
          >
            <VerticalAlignTopOutlined style={{fontSize: 16}}/>
          </Button>
        </div>
      </div>
    </div>
  );
};

export default ArticleRagSider;
