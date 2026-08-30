import {ReadOutlined,} from '@ant-design/icons';
import {Empty, Row, Spin, Typography} from 'antd';
import React, {useCallback, useEffect, useRef, useState} from 'react';
import {listRecentAccessibleArticles, searchArticlesChunks,} from '@/services/ant-design-pro/richText';
import {useComponentHeight} from '@/utils/useDynamicHeight';
import type {ActiveSelectedInfo, ArticleSearchParam} from "@/types/rt.type";
import {
  ArticleCard,
  type ArticleCardProps,
  ArticleSearch,
  type ArticleSearchRef,
  ArticleSkeleton
} from "@/components/Article/components";

const {Title} = Typography;

const PAGE_SIZE = 10;

interface ArticleHomeProps {
  onArticleSelect?: (activeJumpInfo: ActiveSelectedInfo) => void | Promise<void>;
}

const ArticleHome: React.FC<ArticleHomeProps> = ({onArticleSelect}) => {
  // 文章列表
  const [articles, setArticles] = useState<ArticleCardProps[]>([]);
  // 加载状态
  const [loading, setLoading] = useState<boolean>(true);
  // 加载更多 loading（底部追加时的小 spinner）
  const [loadingMore, setLoadingMore] = useState<boolean>(false);
  // 当前页码
  const currentPageRef = useRef<number>(1);
  // 是否还有更多文章
  const [hasMore, setHasMore] = useState<boolean>(false);
  // 总命中数
  const [totalHits, setTotalHits] = useState<number>(0);
  // 防止并发请求：同一时刻只允许一个加载更多请求在途
  const loadingMoreRef = useRef<boolean>(false);
  // 底部哨兵元素 ref
  const sentinelRef = useRef<HTMLDivElement>(null);
  // 页面高度自适应
  const pageHeight = useComponentHeight(0, 500);
  // 加载最近文章，初始进入时为 true，手工搜索后为 false，当输入框没有内容且其他条件收起时为 true
  const [showRecent, setShowRecent] = useState<boolean>(true);
  // 查询组件引用
  const searchInfoRef = useRef<ArticleSearchRef>(null);

  /**
   * 核心搜索函数（可复用于首次搜索与加载更多）
   * @param nextKeyword  搜索关键词
   * @param current         页码（0 为首次搜索，>0 为加载更多）
   * @param isLoadMore   是否为加载更多模式
   */
  const handleSearch = useCallback(
    async (searchParam: ArticleSearchParam, loadMore: boolean) => {
      try {
        const data = await searchArticlesChunks({
          ...searchParam,
          size: PAGE_SIZE,
        });
        setHasMore(data.hasMore ?? false);
        setTotalHits(data.totalHits ?? 0);
        let currentResults = [];
        if (data.hits.length > 0) {
          currentResults = data.hits.filter((item: any) => item.source).map((item: any) => {
            const chunkInfo = item.source;
            const articleInfo = item.source.article_meta;
            const highlight = item.highlights;
            return {
              showType: 'list',
              key: chunkInfo.chunk_id,
              id: chunkInfo.article_id,
              chunkId: chunkInfo.chunk_id,
              nodeId: chunkInfo.tiptap_node_id,
              sectionHeading: chunkInfo.section_heading,
              sectionHeadingId: chunkInfo.section_heading_id,
              breadcrumb: chunkInfo.breadcrumb,
              content: chunkInfo.content,
              cover: articleInfo.cover,
              title: articleInfo.title,
              highlights: highlight.content,
            }
          })
        }
        if (loadMore) {
          setArticles(prev => [...prev, ...currentResults]);
        } else {
          setArticles(currentResults);
        }
        // 同步更新当前页码 ref，供下次加载更多使用
        currentPageRef.current = searchParam.current ?? 1;
      } catch {
        if (!loadMore) {
          setArticles([]);
        }
      }
    }, []);

  // ── 加载更多（由 IntersectionObserver 触发） ──────────────────────────────
  const handleLoadMore = useCallback(() => {
    // 防止重复触发
    if (loadingMoreRef.current) return;
    // 没有更多数据
    if (!hasMore) return;
    // 首次搜索还在进行中
    if (loading) return;
    loadingMoreRef.current = true;
    setLoadingMore(true);
    handleSearch({...searchInfoRef.current?.searchParam(), current: currentPageRef.current + 1}, true)
      .finally(() => {
        setLoadingMore(false);
        loadingMoreRef.current = false;
      });
  }, [hasMore, loading, handleSearch]);

  // ── 初始加载最近文章 ────────────────────────
  const loadRecentArticles = useCallback(() => {
    setLoading(true);
    setArticles([]);
    setHasMore(false);
    listRecentAccessibleArticles().then((res) => {
      if (!res || res.length === 0) return;
      const list = res.map((article: any) => {
        return {
          showType: 'card',
          key: article.id,
          id: article.id,
          title: article.title,
          cover: article.cover,
          summary: article.summary,
        } as ArticleCardProps;
      });
      setArticles(list);
      setTotalHits(list.length);
    }).finally(() => {
      setLoading(false);
    });
  }, [])

  // ── IntersectionObserver：监听底部哨兵元素进入视口 ────────────────────────
  useEffect(() => {
    const sentinel = sentinelRef.current;
    if (!sentinel) return;

    const observer = new IntersectionObserver(
      (entries) => {
        // 哨兵元素进入视口且当前处于搜索模式时，触发加载更多
        if (entries[0].isIntersecting) {
          handleLoadMore();
        }
      },
      {
        // 提前 100px 触发，让用户感觉更流畅
        rootMargin: '0px 0px 100px 0px',
        threshold: 0,
      },
    );

    observer.observe(sentinel);
    return () => observer.disconnect();
  }, [handleLoadMore]);

  useEffect(() => {
    loadRecentArticles();
  }, []);

  // ── 渲染 ──
  return (
    <div
      className="w-full flex flex-col p-5 overflow-hidden"
      style={{height: pageHeight}}
    >
      {/* 搜索框 */}
      <div className="mt-2.5 mb-5 text-center">
        <Title level={2}>
          <ReadOutlined className="mr-2"/>
          ARTE - 文章列表
        </Title>
      </div>
      <div className={`flex justify-center ${showRecent ? 'mb-7.5' : ''}`}>
        <ArticleSearch
          width='80%'
          ref={searchInfoRef}
          onChange={(searchParam, expanded) => {
            if (!searchParam.searchBingoText && !expanded) {
              // 输入框没内容且搜索框状态是折叠时，搜索最近文章
              setShowRecent(true);
              loadRecentArticles();
            } else {
              // 按条件搜索
              setShowRecent(false);
              setLoading(true);
              handleSearch({...searchParam, current: 1}, false).finally(() => setLoading(false));
            }
          }}/>
      </div>

      {/* 骨架屏 */}
      {loading && (showRecent ? <Row gutter={[16, 16]} className="mx-24"><ArticleSkeleton showType='cards'/></Row>
        : <div className="ml-[10%] w-[80%]"><ArticleSkeleton showType='list'/></div>)}

      {/* 无文章时 */}
      {!loading && totalHits === 0 && <Empty description='暂无文章'/>}

      {/* 搜索结果统计 */}
      {!showRecent && !loading && totalHits > 0 &&
        <div className="ml-[10%] mb-3 text-[#8c8c8c]">共找到 {totalHits} 条相关内容</div>}

      <div className="flex-1 min-h-0 overflow-y-auto scrollbar-none">
        {/* 内容区 */}
        <div className={`flex flex-wrap gap-3.75 ${!showRecent && 'justify-center'}`}>
          {
            articles.map((article) =>
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
                summary={article.summary}
                onArticleSelect={onArticleSelect}
              />)
          }
        </div>

        {/* 底部哨兵 + 加载更多状态 */}
        <div ref={sentinelRef} className="h-px"/>
        {!showRecent && loadingMore && (
          <div className="py-4 text-center">
            <Spin size="small"/>
            <span className="ml-2 text-[#8c8c8c]">加载更多...</span>
          </div>
        )}
      </div>
    </div>
  );
};

export default ArticleHome;
