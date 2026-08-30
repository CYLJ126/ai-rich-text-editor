import {i18nText} from '@/utils/i18n';
import {
  CaretDownOutlined,
  CaretRightOutlined,
  MenuOutlined,
} from '@ant-design/icons';
import { Tooltip } from 'antd';
import { createStyles } from 'antd-style';
import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { useArticleInfoStore, useEditorStore } from '@/components/Article';
import type { ArticleHeading } from '@/types/rt.type';
import EmptySidebar from './EmptySidebar';

interface TocItem {
  /** 唯一标识：heading 文本转义后的 id，与 DOM 中 id 属性一致 */
  id: string;
  /** 显示标题文本 */
  text: string;
  /** heading 级别 1-6 */
  level: number;
  /** 子章节 */
  children: TocItem[];
}

/** 与 defaultExtensions 中 TiptapHeading renderHTML 保持一致 */
function textToId(text: string): string {
  return text.replaceAll(/\s+/g, '-').toLowerCase();
}

/** 从 tiptap doc JSON 节点递归提取所有文本 */
function extractNodeText(node: any): string {
  if (!node) return '';
  if (node.type === 'text') return node.text ?? '';
  if (Array.isArray(node.content)) {
    return node.content.map(extractNodeText).join('');
  }
  return '';
}

/** 从 tiptap doc JSON 中提取扁平 heading 列表 */
export function extractHeadingsFromDoc(doc: any): Array<ArticleHeading> {
  if (!doc?.content) return [];
  const result: Array<ArticleHeading> = [];

  const walk = (nodes: any[]) => {
    for (const node of nodes) {
      if (node.type === 'heading') {
        const text = extractNodeText(node);
        if (text.trim()) {
          result.push({
            id: (node.attrs.id as string) || textToId(text),
            text: text.trim(),
            level: node.attrs?.level ?? 1,
          });
        }
      }
      if (node.content) walk(node.content);
    }
  };

  walk(doc.content);
  return result;
}

/**
 * 将扁平 heading 列表构建为嵌套树
 * 策略：维护一个「当前路径」栈，level 越小层级越高
 */
function buildTocTree(
  flatList: Array<{ id: string; text: string; level: number }>,
): TocItem[] {
  const root: TocItem[] = [];
  // stack 中存储每一层级的最后一个节点，方便追加子节点
  const stack: TocItem[] = [];

  for (const item of flatList) {
    const node: TocItem = { ...item, children: [] };

    // 弹出所有 level >= 当前 level 的节点
    while (stack.length > 0 && stack[stack.length - 1].level >= node.level) {
      stack.pop();
    }

    if (stack.length === 0) {
      root.push(node);
    } else {
      stack[stack.length - 1].children.push(node);
    }

    stack.push(node);
  }

  return root;
}

/** 递归收集有子节点的 id（作为默认展开键） */
function collectParentIds(items: TocItem[]): string[] {
  const ids: string[] = [];
  const walk = (list: TocItem[]) => {
    for (const item of list) {
      if (item.children.length > 0) {
        ids.push(item.id);
        walk(item.children);
      }
    }
  };
  walk(items);
  return ids;
}

// 样式
const useStyles = createStyles(({ token, css }) => ({
  container: css`
    display: flex;
    flex-direction: column;
    height: 100%;
    overflow: hidden;
    background: ${token.colorBgContainer};
  `,

  header: css`
    height: 30px;
    display: flex;
    align-items: center;
    gap: 6px;
    padding: 10px 14px 8px;
    border-bottom: 1px solid ${token.colorBorderSecondary};
    flex-shrink: 0;
    font-weight: bold;
    color: ${token.colorTextSecondary};
    letter-spacing: 1px;
  `,

  scrollArea: css`
    flex: 1;
    overflow-y: auto;
    overflow-x: hidden;
    padding: 6px 0 12px;

    &::-webkit-scrollbar {
      width: 4px;
    }

    &::-webkit-scrollbar-thumb {
      background: ${token.colorBorderSecondary};
      border-radius: 2px;
    }
  `,

  itemRow: css`
    display: flex;
    align-items: center;
    cursor: pointer;
    border-radius: 4px;
    transition: background 0.15s;
    user-select: none;
    position: relative;

    &:hover {
      background: ${token.colorFillTertiary};
    }
  `,

  itemRowActive: css`
    background: ${token.colorFillSecondary} !important;
  `,

  collapseBtn: css`
    display: flex;
    align-items: center;
    justify-content: center;
    width: 16px;
    height: 16px;
    flex-shrink: 0;
    color: ${token.colorTextSecondary};
    transition: color 0.15s;
    border-radius: 3px;

    &:hover {
      color: ${token.colorTextSecondary};
      background: ${token.colorFill};
    }
  `,

  placeholder: css`
    width: 16px;
    height: 16px;
    flex-shrink: 0;
  `,

  label: css`
    flex: 1;
    color: ${token.colorTextSecondary};
    font-weight: normal;
    padding: 4px 6px 4px 2px;
    line-height: 1.5;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    min-width: 0;
  `,

  labelWithChildren: css`
    font-weight: bold;
  `,

  labelActive: css`
    color: ${token.colorPrimary};
  `,
}));

// 子组件：单行目录项
interface TocItemRowProps {
  item: TocItem;
  depth: number;
  isActive: boolean;
  isExpanded: boolean;
  onToggle: (id: string) => void;
  onNavigate: (id: string) => void;
}

const TocItemRow: React.FC<TocItemRowProps> = React.memo(
  ({ item, depth, isActive, isExpanded, onToggle, onNavigate }) => {
    const { styles, cx } = useStyles();
    const hasChildren = item.children.length > 0;

    // 缩进：每层 12px，第一层从 8px 开始
    const paddingLeft = 8 + depth * 12;

    return (
      <Tooltip title={item.text} placement="right" mouseEnterDelay={0.8}>
        <div
          className={cx(styles.itemRow, isActive && styles.itemRowActive)}
          data-toc-id={item.id}
          style={{ paddingLeft }}
          onClick={() => onNavigate(item.id)}
        >
          {/* 折叠/展开按钮 */}
          {hasChildren ? (
            <span
              className={styles.collapseBtn}
              onClick={(e) => {
                e.stopPropagation();
                onToggle(item.id);
              }}
            >
              {isExpanded ? <CaretDownOutlined /> : <CaretRightOutlined />}
            </span>
          ) : (
            <span className={styles.placeholder} />
          )}

          {/* 标题文本 */}
          <span
            className={cx(
              styles.label,
              hasChildren && styles.labelWithChildren,
              isActive && styles.labelActive,
              'toc-item-label',
            )}
          >
            {item.text}
          </span>
        </div>
      </Tooltip>
    );
  },
);

// 子组件：递归渲染目录树
interface TocTreeProps {
  items: TocItem[];
  depth: number;
  activeId: string;
  expandedIds: Set<string>;
  onToggle: (id: string) => void;
  onNavigate: (id: string) => void;
}

const TocTree: React.FC<TocTreeProps> = ({
  items,
  depth,
  activeId,
  expandedIds,
  onToggle,
  onNavigate,
}) => {
  return (
    <>
      {items.map((item) => {
        const isExpanded = expandedIds.has(item.id);
        const isActive = item.id === activeId;

        return (
          <React.Fragment key={item.id}>
            <TocItemRow
              item={item}
              depth={depth}
              isActive={isActive}
              isExpanded={isExpanded}
              onToggle={onToggle}
              onNavigate={onNavigate}
            />
            {/* 子节点：仅在展开时渲染 */}
            {item.children.length > 0 && isExpanded && (
              <TocTree
                items={item.children}
                depth={depth + 1}
                activeId={activeId}
                expandedIds={expandedIds}
                onToggle={onToggle}
                onNavigate={onNavigate}
              />
            )}
          </React.Fragment>
        );
      })}
    </>
  );
};

// 主组件：文章章节目录
const TableOfContents: React.FC = () => {
  const { styles } = useStyles();
  const editor = useEditorStore((state) => state.editor);
  const articleInfo = useArticleInfoStore((state) => state.articleInfo);

  // 目录树数据
  const [tocItems, setTocItems] = useState<TocItem[]>([]);
  // 当前高亮的 heading id
  const [activeId, setActiveId] = useState<string>('');
  // 展开的节点 id 集合
  const [expandedIds, setExpandedIds] = useState<Set<string>>(new Set());
  // 目录自身的滚动区域，用于让当前章节始终保持可见
  const scrollAreaRef = useRef<HTMLDivElement>(null);
  // 记录上一次文档 JSON 字符串，避免无变化时重复计算
  const lastDocJsonRef = useRef<string>('');
  // 保存当前所有被观察的 heading id（扁平列表），用于滚动时计算激活项
  const headingIdsRef = useRef<string[]>([]);

  // ── 1. 从 editor 提取目录 ──
  const refreshToc = useCallback(() => {
    if (!editor) return;

    const json = editor.getJSON();
    const jsonStr = JSON.stringify(json);

    // 无变化时跳过
    if (jsonStr === lastDocJsonRef.current) return;
    lastDocJsonRef.current = jsonStr;

    const flatHeadings = extractHeadingsFromDoc(json);
    const tree = buildTocTree(flatHeadings);

    setTocItems(tree);
    headingIdsRef.current = flatHeadings.map((h) => h.id);

    // 默认展开所有有子节点的项
    setExpandedIds((prev) => {
      const parentIds = collectParentIds(tree);
      // 保留已有展开状态，新增父节点默认展开
      const next = new Set(prev);
      parentIds.forEach((id) => {
        next.add(id);
      });
      return next;
    });
  }, [editor]);

  // ── 2. 绑定 editor update 事件 ──
  useEffect(() => {
    // editor 可能在 RichTextArea 中异步创建，轮询直到可用
    let rafId: number;
    const tryBind = () => {
      if (!editor) {
        rafId = requestAnimationFrame(tryBind);
        return;
      }
      // 首次提取
      refreshToc();
      // 监听后续变化
      editor.on('update', refreshToc);
    };
    tryBind();
    return () => {
      cancelAnimationFrame(rafId);
      editor?.off('update', refreshToc);
    };
  }, [refreshToc, editor, articleInfo]);

  // ── 3. 根据编辑器滚动位置更新当前章节 ──
  useEffect(() => {
    if (!editor || editor.isDestroyed || headingIdsRef.current.length === 0) {
      setActiveId('');
      return;
    }

    const editorScrollRoot = editor.view.dom.closest<HTMLElement>(
      '.tiptap-editor-content',
    );
    if (!editorScrollRoot) return;

    let rafId: number | null = null;

    const updateActiveHeading = () => {
      rafId = null;
      const headingElements = Array.from(
        editor.view.dom.querySelectorAll<HTMLElement>('h1, h2, h3, h4, h5, h6'),
      );
      const headingById = new Map(
        headingElements.map((element) => [
          element.dataset.id || element.id,
          element,
        ]),
      );
      const orderedHeadings = headingIdsRef.current
        .map((id) => ({ id, element: headingById.get(id) }))
        .filter((heading): heading is { id: string; element: HTMLElement } =>
          Boolean(heading.element),
        );

      if (orderedHeadings.length === 0) return;

      // 经过滚动容器顶部 24px 的最后一个标题，就是当前所在章节。
      const activationTop = editorScrollRoot.getBoundingClientRect().top + 24;
      let currentId = orderedHeadings[0].id;
      for (const heading of orderedHeadings) {
        if (heading.element.getBoundingClientRect().top > activationTop) break;
        currentId = heading.id;
      }
      setActiveId((previousId) =>
        previousId === currentId ? previousId : currentId,
      );
    };

    const scheduleUpdate = () => {
      if (rafId !== null) return;
      rafId = requestAnimationFrame(updateActiveHeading);
    };

    scheduleUpdate();
    editorScrollRoot.addEventListener('scroll', scheduleUpdate, {
      passive: true,
    });
    window.addEventListener('resize', scheduleUpdate);

    return () => {
      if (rafId !== null) cancelAnimationFrame(rafId);
      editorScrollRoot.removeEventListener('scroll', scheduleUpdate);
      window.removeEventListener('resize', scheduleUpdate);
    };
  }, [editor, tocItems]);

  // 当前章节被折叠或滚出目录视口时，自动展开父级并滚动到可见位置。
  useEffect(() => {
    if (!activeId) return;

    const parentPath: string[] = [];
    const findParentPath = (items: TocItem[], path: string[]): boolean => {
      for (const item of items) {
        if (item.id === activeId) {
          parentPath.push(...path);
          return true;
        }
        if (findParentPath(item.children, [...path, item.id])) return true;
      }
      return false;
    };
    findParentPath(tocItems, []);

    if (parentPath.length > 0) {
      setExpandedIds((previousIds) => {
        if (parentPath.every((id) => previousIds.has(id))) return previousIds;
        const nextIds = new Set(previousIds);
        parentPath.forEach((id) => {
          nextIds.add(id);
        });
        return nextIds;
      });
    }

    const rafId = requestAnimationFrame(() => {
      const scrollArea = scrollAreaRef.current;
      const activeRow = Array.from(
        scrollArea?.querySelectorAll<HTMLElement>('[data-toc-id]') ?? [],
      ).find((row) => row.dataset.tocId === activeId);
      if (!scrollArea || !activeRow) return;

      const scrollAreaRect = scrollArea.getBoundingClientRect();
      const activeRowRect = activeRow.getBoundingClientRect();
      if (activeRowRect.top < scrollAreaRect.top) {
        scrollArea.scrollTop -= scrollAreaRect.top - activeRowRect.top;
      } else if (activeRowRect.bottom > scrollAreaRect.bottom) {
        scrollArea.scrollTop += activeRowRect.bottom - scrollAreaRect.bottom;
      }
    });

    return () => cancelAnimationFrame(rafId);
  }, [activeId, tocItems]);

  // ── 4. 点击目录项定位到文档 ──
  const handleNavigate = useCallback(
    (id: string) => {
      if (!editor || editor.isDestroyed) return;
      const el = Array.from(
        editor.view.dom.querySelectorAll<HTMLElement>('h1, h2, h3, h4, h5, h6'),
      ).find((heading) => (heading.dataset.id || heading.id) === id);
      if (!el) return;
      el.scrollIntoView({ behavior: 'smooth', block: 'start' });
      window.history.replaceState(
        window.history.state,
        '',
        `#${encodeURIComponent(id)}`,
      );
      setActiveId(id);
      // 短暂聚焦，方便键盘继续操作
      el.setAttribute('tabindex', '-1');
    },
    [editor],
  );

  // ── 5. 折叠/展开 ──
  const handleToggle = useCallback((id: string) => {
    setExpandedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }, []);

  // ── 6. 全部展开 / 全部折叠 快捷操作 ──
  const parentIds = useMemo(() => collectParentIds(tocItems), [tocItems]);
  const allExpanded =
    parentIds.length > 0 && parentIds.every((id) => expandedIds.has(id));

  const handleToggleAll = useCallback(() => {
    setExpandedIds((prev) => {
      const isAllExpanded =
        parentIds.length > 0 && parentIds.every((id) => prev.has(id));
      return isAllExpanded ? new Set() : new Set(parentIds);
    });
  }, [parentIds]);

  if (!articleInfo) {
    return <EmptySidebar />;
  }

  return (
    <div className={styles.container}>
      {/* 标题栏 */}
      <div className={styles.header}>
        <MenuOutlined />
        <span style={{ flex: 1 }}>{i18nText("app.article.sidebar.tableofcontents.5738d1de")}</span>
        {parentIds.length > 0 && (
          <span
            style={{
              cursor: 'pointer',
              color: 'inherit',
              padding: '2px 4px',
              borderRadius: 3,
            }}
            onClick={handleToggleAll}
          >
            {allExpanded ? i18nText("app.article.sidebar.tableofcontents.66440a48") : i18nText("app.article.sidebar.tableofcontents.b037db96")}
          </span>
        )}
      </div>

      {/* 目录内容 */}
      <div ref={scrollAreaRef} className={styles.scrollArea}>
        <TocTree
          items={tocItems}
          depth={0}
          activeId={activeId}
          expandedIds={expandedIds}
          onToggle={handleToggle}
          onNavigate={handleNavigate}
        />
      </div>
    </div>
  );
};

export default TableOfContents;
