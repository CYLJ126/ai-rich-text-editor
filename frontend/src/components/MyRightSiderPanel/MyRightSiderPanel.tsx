import {i18nText} from '@/utils/i18n';
import React, {forwardRef, useCallback, useEffect, useImperativeHandle, useMemo, useRef, useState,} from "react";
import {createStyles} from "antd-style";
import {Button, Input, Spin, Tooltip} from "antd";
import {ArrowUpOutlined, LoadingOutlined, SearchOutlined,} from "@ant-design/icons";
import {LoadFuncResult, RightSiderHandleRef, RightSiderItem, RightSiderItemOption, RightSiderProps,} from "./type";
import SiderListItem from "./SiderListItem";
import PinnedSection from "./PinnedSection";

const useStyles = createStyles(({token, css}) => ({
  container: css`
    display: flex;
    flex-direction: column;
    height: 100%;
    min-height: 0;
    background: ${token.colorBgContainer};
    overflow: hidden;
    position: relative;
  `,
  listWrapper: css`
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    overflow-x: hidden;
    position: relative;
    scrollbar-width: none;
    -ms-overflow-style: none;

    &::-webkit-scrollbar {
      display: none;
    }
  `,
  virtualItemWrapper: css`
    flex-shrink: 0;
    padding-bottom: 4px;
    border-bottom: 1px solid ${token.colorBorderSecondary};
  `,
  backTopBtn: css`
    position: absolute;
    bottom: 16px;
    right: 16px;
    transform: translateX(-50%);
    z-index: 10;
    display: flex;
    align-items: center;
    justify-content: center;
    width: 32px;
    height: 32px;
    border-radius: 50%;
    border: 1px solid ${token.colorBorderSecondary};
    background: ${token.colorBgElevated};
    box-shadow: ${token.boxShadowSecondary};
    cursor: pointer;
    transition: transform 0.2s ease, opacity 0.2s ease;

    &:hover {
      transform: translateX(-50%) scale(1.1);
    }

    &:active {
      transform: translateX(-50%) scale(0.95);
    }
  `,
}));

// ── 核心工具：将 onPin 拦截，注入本地 pinFlag 更新逻辑 ──
function injectPinHandler(
  item: RightSiderItem,
  onPinToggle: (item: RightSiderItem) => void
): RightSiderItem {
  // 没有 key=pin 的操作项，直接返回原始 item
  if (!item.operations?.some((op) => op.key === "pin")) return item;

  return {
    ...item,
    operations: item.operations.map((op): RightSiderItemOption => {
      // 非置顶操作，原样返回
      if (op.key !== "pin") return op;
      return {
        ...op,
        // 拦截 onClick：先执行原始业务逻辑（接口请求），再更新本地 pinFlag
        onClick: (targetItem: RightSiderItem) => {
          op.onClick?.(targetItem);
          onPinToggle(targetItem);
        },
      };
    }),
  };
}

// ── 主组件：侧边栏容器组件 ──
const MyRightSiderPanel = forwardRef<RightSiderHandleRef, RightSiderProps>(
  (props, ref) => {
    const {styles} = useStyles();

    const propsRef = useRef(props);
    useEffect(() => {
      propsRef.current = props;
    });

    const {header, searchInputKey, virtualItem, virtualTip, activeKey, emptyRender} = props;

    // ── 状态 ──
    const searchInputRef = useRef("");
    const [itemList, setItemList] = useState<RightSiderItem[]>([]);
    const [loadingUI, setLoadingUI] = useState(false);
    const [noMore, setNoMore] = useState(false);
    const [showBackTop, setShowBackTop] = useState(false);

    // ── 内部标志位（ref，不触发渲染） ──
    const loadingRef = useRef(false);
    const hasMoreRef = useRef(true);
    const pageRef = useRef(0);
    const initializedRef = useRef(false);
    const scrollRef = useRef<HTMLDivElement>(null);

    // ── 置顶本地切换：只更新 itemList 中对应项的 pinFlag ──
    const handlePinToggle = useCallback((targetItem: RightSiderItem) => {
      setItemList((prev) =>
        prev.map((it) =>
          it.key === targetItem.key
            ? {...it, pinFlag: !it.pinFlag}
            : it
        )
      );
    }, []);

    // ── 派生置顶列表（从 itemList 中筛选 pinFlag=true 的项） ──
    const pinnedItems = useMemo(
      () => {
        return itemList.filter((it) => it.pinFlag);
      },
      [itemList]
    );

    // ── 普通列表（剔除置顶项，避免重复显示） ──
    const normalItems = useMemo(
      () => itemList.filter((it) => !it.pinFlag),
      [itemList]
    );

    // ── 为每个 item 注入 pin 拦截器（保持引用稳定用 useMemo） ──
    const pinnedItemsWithHandler = useMemo(
      () => pinnedItems.map((it) => injectPinHandler(it, handlePinToggle)),
      [pinnedItems, handlePinToggle]
    );

    const normalItemsWithHandler = useMemo(
      () => normalItems.map((it) => injectPinHandler(it, handlePinToggle)),
      [normalItems, handlePinToggle]
    );

    // ── loadMore ──
    const loadMore = useCallback(async () => {
      const {loadFunc, size = 10} = propsRef.current;
      if (!loadFunc || loadingRef.current || !hasMoreRef.current) return;

      loadingRef.current = true;
      setLoadingUI(true);
      try {
        const current = pageRef.current + 1;
        const searchParam: Record<string, unknown> = {current, size};
        if (searchInputKey) {
          searchParam[searchInputKey] = searchInputRef.current;
        }
        const res: LoadFuncResult = await loadFunc(searchParam as any);
        const newItems = res?.records ?? [];
        pageRef.current = current;
        setItemList((prev) => [...prev, ...newItems]);
        if ((current - 1) * size + newItems.length >= res.total) {
          hasMoreRef.current = false;
          setNoMore(true);
        }
      } catch (err) {
        console.error("[MyRightSiderPanel] loadMore error:", err);
      } finally {
        loadingRef.current = false;
        setLoadingUI(false);
      }
    }, []);

    // ── 初始化 ──
    useEffect(() => {
      if (initializedRef.current) return;
      initializedRef.current = true;

      const {items, loadFunc} = propsRef.current;
      if (items && items.length > 0) {
        setItemList(items);
        hasMoreRef.current = false;
        setNoMore(true);
      } else if (loadFunc) {
        loadMore().then();
      }
    }, []);

    // ── 滚动监听 ──
    useEffect(() => {
      const el = scrollRef.current;
      if (!el) return;

      const handleScroll = () => {
        const {scrollTop, scrollHeight, clientHeight} = el;
        setShowBackTop(scrollTop > 200);
        const distanceToBottom = scrollHeight - scrollTop - clientHeight;
        if (
          distanceToBottom <= 100 &&
          hasMoreRef.current &&
          !loadingRef.current
        ) {
          loadMore().then();
        }
      };

      el.addEventListener("scroll", handleScroll, {passive: true});
      return () => el.removeEventListener("scroll", handleScroll);
    }, []);

    // ── 回到顶部 ──
    const scrollToTop = useCallback(() => {
      scrollRef.current?.scrollTo({top: 0, behavior: "smooth"});
    }, []);

    // ── 处理点击事件 ──
    const handleItemClick = useCallback((item: RightSiderItem) => {
      propsRef.current.onItemClick?.(item);
    }, []);

    // ── 处理双击事件 ──
    const handleItemDbClick = useCallback((item: RightSiderItem) => {
      propsRef.current.onItemDoubleClick?.(item);
    }, []);

    // ── 重置并刷新 ──
    const doRefresh = useCallback(async () => {
      setItemList([]);
      setNoMore(false);
      pageRef.current = 0;
      hasMoreRef.current = true;
      await loadMore();
    }, [loadMore]);

    useImperativeHandle(ref, () => ({
      refresh: doRefresh,
      getList: () => itemList,
      setList: (param) => {
        if (param instanceof Function) {
          setItemList((prev) => param(prev));
        } else {
          setItemList(param);
        }
      }
    }));

    return (
      <div className={styles.container}>
        {/* Header */}
        {header && (
          <div className="shrink-0 pt-2 px-2 flex flex-col">
            {header}
          </div>
        )}

        {/* 搜索框 */}
        {searchInputKey && (
          <div className="shrink-0 pt-2 px-2 mb-1 flex flex-col">
            <Input
              placeholder={i18nText("app.common.myrightsiderpanel.myrightsiderpanel.1cd22814")}
              prefix={<SearchOutlined/>}
              allowClear
              onChange={(e) => (searchInputRef.current = e.target.value)}
              onPressEnter={() => {
                setItemList([]);
                setNoMore(false);
                pageRef.current = 0;
                hasMoreRef.current = true;
                loadMore().then();
              }}
            />
          </div>
        )}

        {/* 虚拟固定项 */}
        {virtualItem && (
          <div className={styles.virtualItemWrapper}>
            {/* 虚拟区标题 */}
            {
              virtualTip &&
              <div className="mx-2 -mb-0.5 flex items-center px-1 pt-1.5 select-none">
                <span className="text-xs font-medium tracking-wide text-[var(--ant-color-text-quaternary)]">{virtualTip}</span>
              </div>
            }
            <SiderListItem
              item={virtualItem}
              isActive={activeKey === virtualItem.key}
              onClick={handleItemClick}
              onDoubleClick={handleItemDbClick}
            />
          </div>
        )}

        {/* 置顶区域：在虚拟固定项下方，滚动列表外部，不参与滚动 */}
        <PinnedSection
          pinnedItems={pinnedItemsWithHandler}
          activeKey={activeKey}
          onItemClick={handleItemClick}
          onItemDoubleClick={handleItemDbClick}
        />

        {/* 列表区域 */}
        <div ref={scrollRef} className={styles.listWrapper}>
          {/* 空状态：置顶和普通列表均为空时显示 */}
          {itemList.length === 0 && !loadingUI && emptyRender && (
            <div className="flex flex-col items-center justify-center h-full py-12 opacity-50">
              {emptyRender}
            </div>
          )}

          {/* 普通列表项（剔除置顶项） */}
          {normalItemsWithHandler.map((item) => (
            <SiderListItem
              key={item.key}
              item={item}
              isActive={activeKey === item.key}
              onClick={handleItemClick}
              onDoubleClick={handleItemDbClick}
            />
          ))}

          {/* 加载指示器 */}
          {loadingUI && (
            <div className="flex items-center justify-center py-3">
              <Spin
                indicator={<LoadingOutlined className="text-sm" spin/>}
                size="small"
              />
              <span className="ml-2 text-xs opacity-40">{i18nText("app.common.myrightsiderpanel.myrightsiderpanel.b5b1bf98")}</span>
            </div>
          )}

          {/* 已加载全部 */}
          {noMore && itemList.length > 0 && (
            <div className="flex items-center justify-center py-3">
              <span className="text-xs opacity-30">{i18nText("app.common.myrightsiderpanel.myrightsiderpanel.47d5d305")}</span>
            </div>
          )}
        </div>

        {/* 回到顶部 */}
        {showBackTop && (
          <Tooltip title={i18nText("app.common.myrightsiderpanel.myrightsiderpanel.8df12d57")} placement="left">
            <Button
              onClick={scrollToTop}
              className={styles.backTopBtn}
              aria-label={i18nText("app.common.myrightsiderpanel.myrightsiderpanel.8df12d57")}
            >
              <ArrowUpOutlined className="text-xs"/>
            </Button>
          </Tooltip>
        )}
      </div>
    );
  }
);

export default MyRightSiderPanel;
