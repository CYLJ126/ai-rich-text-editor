import {i18nText} from '@/utils/i18n';
import React, {forwardRef, useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState} from 'react';
import {ArticleSearchParam, ArticleType, articleTypeOptions} from '@/types/rt.type';
import {DownOutlined, SearchOutlined, UpOutlined} from '@ant-design/icons';
import {Button, Checkbox, Input, InputNumber, Select, Slider, TreeSelect} from 'antd';
import {MAX_CHARACTER_COUNT} from '@/components/Article/components';
import {listRecursive} from '@/services/ant-design-pro/base';
import {listUser} from '@/services/ant-design-pro/rbac';
import {listSpaceCatalogs} from '@/services/share';
import {SizeType} from "antd/es/config-provider/SizeContext";

export interface ArticleSearchInfo {
  width?: number | string; // 宽度
  size?: SizeType; // 大小
  expandedSearchKeys?: string[]; // 展开时显示哪些搜索条件
  onChange?: (param: ArticleSearchParam, expanded: boolean) => void; // 搜索条件变化时的回调
  onExpand?: (expanded: boolean) => void;// expanded 为 true 表示展开更多搜索条件
}

export interface ArticleSearchRef {
  searchParam: () => ArticleSearchParam;
}

// ────────────────────────────────────────────────────────────
// 工具：将 listSpaceCatalogs 返回的目录树节点转换为 TreeSelect 格式
// 同时过滤掉 articles 字段，只保留目录结构
// ────────────────────────────────────────────────────────────
interface RawCatalog {
  id: number;
  name: string;
  fatherId?: number | null;
  children?: RawCatalog[] | null;
  articles?: any[];
  articleCount?: any;
  description?: string | null;
}

interface RawTag {
  id: number;
  name: string;
  fatherId?: number | null;
  children?: RawTag[] | null;
}

interface TreeNode {
  title: string;
  value: number | string;
  key: number | string;
  disabled?: boolean;
  children?: TreeNode[];
}

function catalogToTreeNode(catalog: RawCatalog): TreeNode {
  const node: TreeNode = {
    title: catalog.name,
    value: catalog.id,
    key: catalog.id,
  };
  if (catalog.children && catalog.children.length > 0) {
    node.children = catalog.children.map(catalogToTreeNode);
  }
  return node;
}

/** 将 listSpaceCatalogs data（含 mySpace/publicSpace/sharedWithMe）转为分组 TreeNode */
function buildCatalogTree(data: {
  mySpace?: RawCatalog[];
  publicSpace?: RawCatalog[];
  sharedWithMe?: RawCatalog[];
}): TreeNode[] {
  const groups = [
    {label: i18nText("app.article.article.articlesearch.2df1c531"), key: 'mySpace', list: data.mySpace},
    {label: i18nText("app.article.article.articlesearch.4290c88f"), key: 'publicSpace', list: data.publicSpace},
    {label: i18nText("app.article.article.articlesearch.c0088b15"), key: 'sharedWithMe', list: data.sharedWithMe},
  ];

  return groups
    .filter((g) => g.list && g.list.length > 0)
    .map((g) => ({
      title: g.label,
      value: `group_${g.key}`, // 分组节点用字符串，不可选
      key: `group_${g.key}`,
      disabled: true, // 分组不可选中
      children: (g.list ?? []).map(catalogToTreeNode),
    }));
}

function tagToTreeNode(tag: RawTag): TreeNode {
  const node: TreeNode = {
    title: tag.name,
    value: tag.id,
    key: tag.id,
  };
  if (tag.children && tag.children.length > 0) {
    node.children = tag.children.map(tagToTreeNode);
  }
  return node;
}

// ────────────────────────────────────────────────────────────
// 主组件
// ────────────────────────────────────────────────────────────
const ArticleSearch = forwardRef<ArticleSearchRef, ArticleSearchInfo>(
  ({
     width = '100%',
     size = 'large',
     onChange,
     onExpand
   }, ref) => {
    // 关键词，支持空格分隔多个关键词
    const [searchBingoText, setSearchBingoText] = useState<string>('');
    // 为 true 则只搜索标题
    const [searchTitle, setSearchTitle] = useState<boolean>(false);
    // 文章类型
    const [articleTypes, setArticleTypes] = useState<ArticleType[]>([]);
    // 作者
    const [authors, setAuthors] = useState<string[]>([]);
    // 所属目录 ID
    const [catalogIds, setCatalogIds] = useState<number[]>([]);
    // 标签 ID 集合
    const [tags, setTags] = useState<number[]>([]);
    // 字数范围
    const [characterCountRange, setCharacterCountRange] = useState<number[]>([0, MAX_CHARACTER_COUNT]);
    // 是否语义搜索，为 true 则与关键词相关的内容都会搜索到
    const [semanticSearch, setSemanticSearch] = useState<boolean>(false);
    // 搜索条件展开状态
    const [expanded, setExpanded] = useState<boolean>(false);

    // 用户下拉
    const [authorOptions, setAuthorOptions] = useState<{ value: string; label: string }[]>([]);
    // 标签树下拉
    const [tagTree, setTagTree] = useState<TreeNode[]>([]);
    // 目录树下拉
    const [catalogTree, setCatalogTree] = useState<TreeNode[]>([]);

    // 是否已加载过的标志，避免重复请求
    const authorLoaded = useRef(false);
    const tagLoaded = useRef(false);
    const catalogLoaded = useRef(false);

    // 加载状态
    const [authorLoading, setAuthorLoading] = useState(false);
    const [tagLoading, setTagLoading] = useState(false);
    const [catalogLoading, setCatalogLoading] = useState(false);

    // 组装搜索参数
    const assembleSearch = useCallback(() => {
      const param: ArticleSearchParam = {};
      param.searchBingoText = searchBingoText;
      param.characterCountFloor = characterCountRange[0];
      param.characterCountCeil = characterCountRange[1];
      return {...param, searchTitle, articleTypes, authors, catalogIds, tags, semanticSearch};
    }, [searchTitle, searchBingoText, catalogIds, authors, characterCountRange, tags, semanticSearch]);

    // ── 懒加载：作者 ──
    const loadAuthors = useCallback(() => {
      if (authorLoaded.current) return;
      setAuthorLoading(true);
      listUser({})
        .then((result) => {
          const users = (result.records ?? []).map((user: any) => ({
            value: user.userName,
            label: user.userName,
          }));
          setAuthorOptions(users);
          authorLoaded.current = true;
        })
        .finally(() => setAuthorLoading(false));
    }, []);

    // ── 懒加载：标签 ──
    const loadTags = useCallback(() => {
      if (tagLoaded.current) return;
      setTagLoading(true);
      listRecursive({tagTypes: ['article']})
        .then((result) => {
          const tree = result ? result.map(tagToTreeNode) : [];
          setTagTree(tree);
          tagLoaded.current = true;
        })
        .finally(() => setTagLoading(false));
    }, []);

    // ── 懒加载：目录 ──
    const loadCatalogs = useCallback(() => {
      if (catalogLoaded.current) return;
      setCatalogLoading(true);
      listSpaceCatalogs()
        .then((result: any) => {
          const tree = result ? buildCatalogTree(result) : [];
          setCatalogTree(tree);
          catalogLoaded.current = true;
        })
        .finally(() => setCatalogLoading(false));
    }, []);

    // ── 展开/收起 ──
    const toggleExpand = () => {
      const next = !expanded;
      setExpanded(next);
      onExpand?.(next);
    };

    // ── TreeSelect 搜索过滤 ──
    const filterTreeNode = (input: string, node: any): boolean =>
      String(node.title ?? '')
        .toLowerCase()
        .includes(input.toLowerCase());

    // ── TreeSelect onChange：包含父节点的多选处理 ──
    // treeCheckable + SHOW_ALL 会同时显示父子，但勾选父时子也被全选
    // 改为 SHOW_PARENT：只展示勾选的最高层节点，语义上"选了整个目录"
    // 如需父子均独立可选（父不连带子），则关掉 treeCheckable，用 multiple + onSelect 手动控制
    const handleTagChange = useCallback(
      (val: number[]) => {
        setTags(val);
        // 让 React 先 setState，再通过 ref 读最新值触发搜索
        // 用 setTimeout 0 确保 setState 完成
        setTimeout(() => onChange?.(assembleSearch(), expanded), 0);
      },
      [onChange, assembleSearch, expanded],
    );

    const handleCatalogChange = useCallback(
      (val: number[]) => {
        setCatalogIds(val);
        setTimeout(() => onChange?.(assembleSearch(), expanded), 0);
      },
      [onChange, assembleSearch, expanded],
    );

    const handleArticleTypeChange = useCallback(
      (val: ArticleType[]) => {
        setArticleTypes(val);
        setTimeout(() => onChange?.(assembleSearch(), expanded), 0);
      },
      [onChange, assembleSearch, expanded],
    );

    const handleAuthorsChange = useCallback(
      (val: string[]) => {
        setAuthors(val);
        setTimeout(() => onChange?.(assembleSearch(), expanded), 0);
      },
      [onChange, assembleSearch, expanded],
    );

    const handleSemanticChange = useCallback(
      (checked: boolean) => {
        setSemanticSearch(checked);
        setTimeout(() => onChange?.(assembleSearch(), expanded), 0);
      },
      [onChange, assembleSearch, expanded],
    );

    const handleSearchTitleChange = useCallback(
      (checked: boolean) => {
        setSearchTitle(checked);
        setTimeout(() => onChange?.(assembleSearch(), expanded), 0);
      },
      [onChange, assembleSearch, expanded],
    );

    const characterComponent = useMemo(() => {
      if (size === 'large') {
        return <div className="col-span-2 flex items-center gap-2 flex-1 min-w-0">
          <span className="flex-shrink-0">{i18nText("app.article.article.articlesearch.6f323566")}</span>
          <Slider
            range
            step={500}
            min={0}
            max={MAX_CHARACTER_COUNT}
            value={characterCountRange}
            // 滑动过程中只更新显示值，不触发搜索
            onChange={(val) => setCharacterCountRange(val)}
            // 鼠标释放后才触发搜索
            onChangeComplete={() => onChange?.(assembleSearch(), expanded)}
            tooltip={{
              formatter: (v) => i18nText("app.article.article.articlesearch.6a7ccc6a", {value0: v}),
            }}
            className="flex-1 w-[300px] min-w-[300px] max-w-[300px]"
          />
          <span className="flex-shrink-0 text-[#8c8c8c] text-xs">
                {characterCountRange[0]} – {characterCountRange[1]}
              </span>
        </div>;
      }
      return <div className="col-span-2 flex items-center gap-2 flex-1">
        <span className="flex-shrink-0">{i18nText("app.article.article.articlesearch.6f323566")}</span>
        <InputNumber
          className="w-[130px]"
          min={0}
          max={MAX_CHARACTER_COUNT}
          step={500}
          value={characterCountRange[0]}
          onChange={(val) => {
            const newVal = val ?? 0;
            const newRange: [number, number] = [
              Math.min(newVal, characterCountRange[1]),
              characterCountRange[1],
            ];
            setCharacterCountRange(newRange);
          }}
          onBlur={() => onChange?.(assembleSearch(), expanded)}
        />
        <span className="text-gray-400 shrink-0">~</span>
        <InputNumber
          className="w-[130px]"
          min={0}
          max={MAX_CHARACTER_COUNT}
          step={500}
          value={characterCountRange[1]}
          onChange={(val) => {
            const newVal = val ?? MAX_CHARACTER_COUNT;
            const newRange: [number, number] = [
              characterCountRange[0],
              Math.max(newVal, characterCountRange[0]),
            ];
            setCharacterCountRange(newRange);
          }}
          onBlur={() => onChange?.(assembleSearch(), expanded)}
        />
      </div>;
    }, [characterCountRange, onChange, assembleSearch, expanded]);

    useEffect(() => {
      if (!searchBingoText && !expanded) {
        // 其他条件未展开，且内容为空时触发，这样写不是太优雅，逻辑很突兀，不是组件的合理逻辑，后续可考虑履行
        onChange?.(assembleSearch(), expanded);
      }
    }, [searchBingoText, expanded, assembleSearch]);

    // 暴露方法给父组件
    useImperativeHandle(ref, () => ({
      searchParam: () => assembleSearch(),
    }));

    return (
      <div className="flex flex-col gap-3 mb-[20px]" style={{width}}>
        {/* ── 第 0 行：搜索框 + 收起/展开按钮 ── */}
        <div className="flex gap-2 items-center">
          <div className="flex-1">
            <Input.Search
              placeholder={i18nText("app.article.article.articlesearch.73e5f0c2")}
              enterButton={<SearchOutlined/>}
              value={searchBingoText}
              // 输入时只更新文本，不触发搜索
              onChange={(e) => setSearchBingoText(e.target.value)}
              // 按 Enter 或点击搜索按钮时才触发
              onSearch={() => onChange?.(assembleSearch(), expanded)}
              size={size}
            />
          </div>
          <Button
            size={size}
            icon={expanded ? <UpOutlined/> : <DownOutlined/>}
            onClick={toggleExpand}
            className="flex-shrink-0"
            style={{width: size === 'large' ? undefined : 70}}
          >
            {expanded ? i18nText("app.article.article.articlesearch.0e56f79c") : i18nText("app.article.article.articlesearch.05708f44")}
          </Button>
        </div>

        {/* ── 展开区域 ── */}
        {expanded && (
          <div className="border border-[#f0f0f0] rounded-lg px-4 py-3 flex flex-col gap-3">
            {/* 第 1 行：文章类型 / 作者 / 标签 / 目录 */}
            <div className={`grid gap-3 ${size === 'large' ? 'grid-cols-4' : 'grid-cols-2'}`}>
              {/* 文章类型 */}
              <Select
                allowClear
                mode="multiple"
                placeholder={i18nText("app.article.article.articlesearch.ce187aeb")}
                options={articleTypeOptions}
                onChange={handleArticleTypeChange}
                className="w-full"
                maxTagCount="responsive"
              />

              {/* 作者 */}
              <Select
                allowClear
                mode="multiple"
                placeholder={i18nText("app.article.article.articlesearch.f13fb86b")}
                loading={authorLoading}
                options={authorOptions}
                onFocus={loadAuthors}
                onChange={handleAuthorsChange}
                showSearch={true}
                className="w-full"
                maxTagCount="responsive"
              />

              {/* 标签：treeCheckable=false，multiple，点击节点（含父节点）均可选中 */}
              <TreeSelect
                multiple
                allowClear
                placeholder={i18nText("app.article.article.articlesearch.55a9f9a5")}
                treeData={tagTree}
                loading={tagLoading}
                value={tags}
                onOpenChange={(open) => open && loadTags()}
                onChange={handleTagChange}
                showSearch={{filterTreeNode}}
                className="w-full"
                maxTagCount="responsive"
                // 不使用 treeCheckable，点击父节点仅选中父节点自身
                treeCheckable={false}
              />

              {/* 目录：同标签 */}
              <TreeSelect
                multiple
                allowClear
                placeholder={i18nText("app.article.article.articlesearch.fe2c3a26")}
                treeData={catalogTree}
                loading={catalogLoading}
                value={catalogIds}
                onOpenChange={(open) => open && loadCatalogs()}
                onChange={handleCatalogChange}
                showSearch={{filterTreeNode}}
                className="w-full"
                maxTagCount="responsive"
                treeCheckable={false}
              />
            </div>

            {/* 第 2 行：搜索标题 + 相关性搜索 + 字数范围 */}
            <div className={`grid gap-3  ${size === 'large' ? 'grid-cols-4' : 'grid-cols-2'}`}>
              <Checkbox
                checked={searchTitle}
                onChange={(e) => handleSearchTitleChange(e.target.checked)}
                className="flex-shrink-0 whitespace-nowrap"
              >
                {i18nText("app.article.article.articlesearch.43a5a207")}
              </Checkbox>

              <Checkbox
                checked={semanticSearch}
                onChange={(e) => handleSemanticChange(e.target.checked)}
                className="flex-shrink-0 whitespace-nowrap"
              >
                {i18nText("app.article.article.articlesearch.d8d78e37")}
              </Checkbox>
              {/* 字数范围 */}
              {characterComponent}
            </div>
          </div>
        )}
      </div>
    );
  });

export default ArticleSearch;
