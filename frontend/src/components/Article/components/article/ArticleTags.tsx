import React from 'react';
import { useArticleInfoStore } from '@/components/Article';
import MyTagTree from '@/components/MyTagTree';
import { useComponentHeight } from '@/utils/useDynamicHeight';
import EmptySidebar from '../sidebar/EmptySidebar';

/**
 * 文章标签组件
 * sourceId: 文章 id，只有文章 id 才会显示标签树
 * @constructor
 */
const ArticleTags: React.FC<{ active: boolean }> = ({ active }) => {
  const articleInfo = useArticleInfoStore((state) => state.articleInfo);
  const setTags = useArticleInfoStore((state) => state.setTags);
  const tagPageHeight = useComponentHeight(50, 500);

  if (!active || !articleInfo?.id) {
    return <EmptySidebar />;
  }

  return (
    articleInfo?.id && (
      <MyTagTree
        tagTypes={['article']}
        defaultExpanded={true}
        sourceId={articleInfo?.id}
        pageHeight={tagPageHeight}
        onChecked={setTags}
      />
    )
  );
};

export default ArticleTags;
