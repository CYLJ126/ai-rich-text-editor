import {listRecursive} from '@/services/ant-design-pro/base';

// TODO 待放到 initialState 中，放到 localstorage 中则每次取用时都要反序列化一遍，不合适
export async function initialUserTags(): Promise<void> {
  const [dailyWorkResult, stickyResult, websiteResult, articleResult] = await Promise.allSettled([
    // 查询"日课"的标签列表
    (async () => {
      const result = await listRecursive({tagTypes: ['daily_work']});
      if (!result || result.length === 0) {
        return;
      }
      const dailyWorkTags = result.map((item: any) => {
        return {
          value: item.id,
          label: item.name,
        }
      })
      localStorage.setItem('dailyWorkTags', JSON.stringify(dailyWorkTags || []));
    })(),

    // 查询"便笺"的标签列表
    (async () => {
      const data = await listRecursive({tagTypes: ['sticky']});

      if (!data || data.length === 0) {
        return;
      }
      localStorage.setItem('stickyTags', JSON.stringify(data || []));
    })(),

    // 查询"新闻资讯"的标签列表
    (async () => {
      const data = await listRecursive({tagTypes: ['news']});

      if (!data || data.length === 0) {
        return;
      }
      localStorage.setItem('websiteTags', JSON.stringify(data || []));
    })(),

    // 查询"文章"的标签列表
    (async () => {
      const data = await listRecursive({tagTypes: ['article']});

      if (!data || data.length === 0) {
        return;
      }
      localStorage.setItem('articleTags', JSON.stringify(data || []));
    })(),
  ]);

  // 打印失败信息
  if (dailyWorkResult.status === 'rejected') {
    console.error('日课标签初始化失败：', dailyWorkResult.reason);
  }
  if (stickyResult.status === 'rejected') {
    console.error('便笺标签初始化失败：', stickyResult.reason);
  }
  if (websiteResult.status === 'rejected') {
    console.error('新闻资讯标签初始化失败：', websiteResult.reason);
  }
  if (articleResult.status === 'rejected') {
    console.error('文章标签初始化失败：', articleResult.reason);
  }
}

function buildTagMap(tree: any[]): Record<number, string> {
  const map: Record<number, string> = {};

  function dfs(nodes: any[]) {
    nodes.forEach(node => {
      map[node.id] = node.name;

      if (node.children?.length) {
        dfs(node.children);
      }
    });
  }

  dfs(tree);
  return map;
}


/** 返回文章标签映射表 TODO 转换成全局状态 */
export function loadArticleTagMap(): Record<number, string> {
  try {
    const raw = localStorage.getItem('articleTags');
    if (!raw) return {};
    return buildTagMap(JSON.parse(raw));
  } catch {
    return {};
  }
}
