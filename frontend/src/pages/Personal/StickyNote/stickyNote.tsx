import {i18nText} from '@/utils/i18n';
import React, {PointerEvent as ReactPointerEvent, useCallback, useEffect, useMemo, useRef, useState,} from 'react';
import {DatePicker, Input, message, Popover, Spin} from 'antd';
import {CloseOutlined, FileWordOutlined, PlusSquareOutlined, TagsOutlined,} from '@ant-design/icons';
import {debounce} from 'lodash';
import dayjs, {Dayjs} from 'dayjs';

import {getNextZIndex} from './zIndexManager';
import styles from './stickyNote.less';
import {resizeSticky, switchThemeColor, updateSticky,} from '@/services/ant-design-pro/dailyWork';
import {useStickyNoteData} from './stickyNoteContext';
import MyColorPicker from '@/components/MyColorPicker';
import TagsSelector, {MyTag, TagItem,} from '@/components/TagsSelector';
import {addTagRelation, deleteTagRelation, listTagRelations,} from '@/services/ant-design-pro/base';
import {SimpleEditor} from '@/components/Article';
import {StickyNoteInfo} from '@/pages/Personal/StickyNote/type';

const tagType = 'sticky';

export interface StickyNotePosition {
  x: number;
  y: number;
}

export interface StickyNoteProps {
  stickyNoteInfo: StickyNoteInfo;

  /**
   * 便笺相对于父级定位容器的横坐标。
   */
  px: number;

  /**
   * 便笺相对于父级定位容器的纵坐标。
   */
  py: number;

  /**
   * 便笺初始层级
   */
  initialZIndex?: number;

  /**
   * 拖动结束后的回调。
   *
   * 父组件可以通过该回调更新便笺列表中的坐标，并将坐标保存至后端。
   */
  onPositionChange?: (
    id: number,
    position: StickyNotePosition,
  ) => void | Promise<void>;
}

interface DragState {
  pointerId: number;
  startClientX: number;
  startClientY: number;
  startPositionX: number;
  startPositionY: number;
  hasMoved: boolean;
}

export default function StickyNote({
                                     stickyNoteInfo,
                                     px,
                                     py,
                                     initialZIndex = 1,
                                     onPositionChange,
                                   }: StickyNoteProps) {
  const {deleteLogical, stickyTags} = useStickyNoteData();

  /**
   * 使用 useMemo 计算初始状态，避免初始化渲染时出现样式闪烁。
   */
  const initialState = useMemo(() => {
    const currentThemeColor =
      '#' + (stickyNoteInfo.themeColor || '81d3f8');

    const currentWidth = stickyNoteInfo.width || 300;
    const currentHeight = stickyNoteInfo.height || 220;

    return {
      position: {
        x: px,
        y: py,
      },
      size: {
        width: currentWidth,
        height: currentHeight,
      },
      sticky: {
        id: stickyNoteInfo.id,
        title: stickyNoteInfo.title,
        content: stickyNoteInfo.content,
        themeColor: currentThemeColor,
      },
      endDate: stickyNoteInfo.endDate
        ? dayjs(stickyNoteInfo.endDate)
        : dayjs(),
      themeColor: currentThemeColor,
      showType: stickyNoteInfo.showType || 'text',
      zIndex: initialZIndex,
    };
  }, [stickyNoteInfo, px, py, initialZIndex]);

  /**
   * 便笺宽高
   */
  const [width, setWidth] = useState(initialState.size.width);
  const [height, setHeight] = useState(initialState.size.height);

  /**
   * 初始化状态
   */
  const [isInitialized, setIsInitialized] = useState(false);
  const [tagsLoaded, setTagsLoaded] = useState(false);

  /**
   * 便笺层级
   */
  const [zIndex, setZIndex] = useState(initialState.zIndex);

  /**
   * 便笺当前位置
   */
  const [position, setPosition] = useState<StickyNotePosition>(
    initialState.position,
  );

  /**
   * 通过 ref 保存最新位置。
   *
   * PointerMove 触发频率较高，PointerUp 时直接读取 ref，
   * 可以避免读取到 React 尚未提交的旧 state。
   */
  const positionRef = useRef<StickyNotePosition>(
    initialState.position,
  );

  /**
   * 当前拖动信息。
   */
  const dragStateRef = useRef<DragState | null>(null);

  /**
   * 是否正在拖动整个便笺。
   */
  const [isDragging, setIsDragging] = useState(false);

  /**
   * 便笺详情。
   */
  const [sticky, setSticky] = useState(initialState.sticky);

  /**
   * 结束日期，超过此日期后不再显示。
   */
  const [endDate, setEndDate] = useState<Dayjs>(
    initialState.endDate,
  );

  /**
   * 主题色。
   */
  const [themeColor, setThemeColor] = useState(
    initialState.themeColor,
  );

  /**
   * text：文本；list：列表；markdown：富文本。
   */
  const [showType, setShowType] = useState<string>(
    initialState.showType,
  );

  /**
   * 标签列表单独管理，因为标签需要异步获取。
   */
  const [tags, setTags] = useState<any[]>([]);

  /**
   * 将当前便笺提升至最上层。
   */
  const bringToFront = useCallback(() => {
    setZIndex(getNextZIndex());
  }, []);

  /**
   * 当父组件传入的位置发生变化时，同步内部位置。
   *
   * 拖动过程中不进行同步，防止父组件的旧位置覆盖当前拖动位置。
   */
  useEffect(() => {
    if (dragStateRef.current) {
      return;
    }

    const nextPosition: StickyNotePosition = {
      x: px,
      y: py,
    };

    positionRef.current = nextPosition;
    setPosition(nextPosition);
  }, [px, py]);

  /**
   * 开始拖动便笺。
   *
   * 该事件只绑定在 TagsOutlined 上，因此不会和：
   * 1. Tiptap 节点拖动手柄；
   * 2. 编辑器内容选择；
   * 3. DraggableLine 尺寸调整手柄
   * 发生冲突。
   */
  const handleDragStart = useCallback(
    (event: ReactPointerEvent<HTMLSpanElement>) => {
      /**
       * 鼠标只响应左键。
       * 触摸和触控笔的 button 通常也是 0。
       */
      if (event.button !== 0) {
        return;
      }

      event.preventDefault();
      event.stopPropagation();

      bringToFront();
      setIsDragging(true);

      dragStateRef.current = {
        pointerId: event.pointerId,
        startClientX: event.clientX,
        startClientY: event.clientY,
        startPositionX: positionRef.current.x,
        startPositionY: positionRef.current.y,
        hasMoved: false,
      };

      /**
       * 捕获当前 Pointer。
       *
       * 即使鼠标移动到 TagsOutlined 外部，
       * PointerMove 和 PointerUp 仍会发送给当前图标。
       */
      event.currentTarget.setPointerCapture(event.pointerId);
    },
    [bringToFront],
  );

  /**
   * 拖动便笺。
   */
  const handleDragMove = useCallback(
    (event: ReactPointerEvent<HTMLSpanElement>) => {
      const dragState = dragStateRef.current;

      if (
        !dragState ||
        dragState.pointerId !== event.pointerId
      ) {
        return;
      }

      event.preventDefault();
      event.stopPropagation();

      const offsetX =
        event.clientX - dragState.startClientX;
      const offsetY =
        event.clientY - dragState.startClientY;

      /**
       * 避免非常轻微的指针抖动也被标记为拖动。
       */
      if (
        !dragState.hasMoved &&
        (Math.abs(offsetX) > 1 || Math.abs(offsetY) > 1)
      ) {
        dragState.hasMoved = true;
      }

      const nextPosition: StickyNotePosition = {
        x: dragState.startPositionX + offsetX,
        y: dragState.startPositionY + offsetY,
      };

      positionRef.current = nextPosition;
      setPosition(nextPosition);
    },
    [],
  );

  /**
   * 结束拖动，并通知父组件保存位置。
   */
  const handleDragEnd = useCallback(
    (event: ReactPointerEvent<HTMLSpanElement>) => {
      const dragState = dragStateRef.current;

      if (
        !dragState ||
        dragState.pointerId !== event.pointerId
      ) {
        return;
      }

      event.preventDefault();
      event.stopPropagation();

      const hasMoved = dragState.hasMoved;

      dragStateRef.current = null;
      setIsDragging(false);

      if (
        event.currentTarget.hasPointerCapture(event.pointerId)
      ) {
        event.currentTarget.releasePointerCapture(
          event.pointerId,
        );
      }

      /**
       * 只有位置真正发生变化时才通知父组件，
       * 避免单击拖动图标也触发后端保存。
       */
      if (!hasMoved) {
        return;
      }

      const finalPosition = {
        x: Math.round(positionRef.current.x),
        y: Math.round(positionRef.current.y),
      };

      positionRef.current = finalPosition;
      setPosition(finalPosition);

      Promise.resolve(
        onPositionChange?.(
          stickyNoteInfo.id,
          finalPosition,
        ),
      ).catch((error) => {
        console.error('便笺位置保存失败:', error);
        message.error(i18nText("app.common.stickynote.stickynote.9e111f6e")).then();
      });
    },
    [onPositionChange, stickyNoteInfo.id],
  );

  /**
   * 防抖更新主题色。
   */
  const debouncedSwitchThemeColor = useMemo(
    () =>
      debounce(async (id: number, color: string) => {
        try {
          await switchThemeColor({
            id,
            themeColor: color,
          });
        } catch (error) {
          console.error('颜色更新失败:', error);
          message.error(i18nText("app.common.stickynote.stickynote.eee92d96")).then();
        }
      }, 1000),
    [],
  );

  /**
   * 防抖尺寸
   */
  const debouncedResize = useMemo(
    () =>
      debounce(async (id: number, tempWidth: number, tempHeight: number) => {
        try {
          await resizeSticky(id, tempWidth, tempHeight);
        } catch (error) {
          console.error('便笺尺寸更新失败:', error);
          message.error(i18nText("app.common.stickynote.stickynote.ac13b0e7")).then();
        }
      }, 1000),
    [],
  );

  /**
   * 组件卸载时取消尚未执行的颜色更新。
   */
  useEffect(() => {
    return () => {
      debouncedSwitchThemeColor.cancel();
    };
  }, [debouncedSwitchThemeColor]);

  /**
   * 保存便笺内容。
   */
  function saveSticky(param: any) {
    if (!param.content) {
      return;
    }

    updateSticky(param).then((res) => {
      if (!res) {
        message
          .error(i18nText("app.common.stickynote.stickynote.6d1ebd27", {value0: param.id}))
          .then();
      }
    });
  }

  /**
   * 处理颜色变化。
   */
  const handleColorChange = (newColor: string) => {
    // 立即更新 UI 显示
    setThemeColor(newColor);

    setSticky((previous) => ({
      ...previous,
      themeColor: newColor,
    }));

    // 使用防抖函数延迟保存到后端
    debouncedSwitchThemeColor(sticky.id, newColor);
  };

  /**
   * 查询便笺标签。
   */
  const queryTags = async (stickyId: number) => {
    try {
      const res = await listTagRelations(
        stickyId,
        tagType,
      );

      if (res) {
        setTags(res);
      }
    } catch (error) {
      console.error('查询标签失败:', error);
    } finally {
      setTagsLoaded(true);
    }
  };

  /**
   * 添加标签
   */
  const addTag = (tag: TagItem) => {
    addTagRelation(sticky.id, tag.id, tagType).then(
      (res) => {
        if (!res) {
          return;
        }

        setTags((previousTags) => {
          const exists = previousTags.some(
            (item) => item.id === tag.id,
          );

          if (exists) {
            return previousTags;
          }

          return [
            ...previousTags,
            {
              ...tag,
              index: previousTags.length,
            },
          ];
        });
      },
    );
  };

  /**
   * 删除标签
   */
  const removeTag = (tag: TagItem) => {
    deleteTagRelation(
      sticky.id,
      tag.id,
      tagType,
    ).then((res) => {
      if (!res) {
        return;
      }

      setTags((previousTags) =>
        previousTags.filter(
          (item) => item.id !== tag.id,
        ),
      );
    });
  };

  /**
   * 初始化异步数据
   */
  useEffect(() => {
    let disposed = false;

    const initializeComponent = async () => {
      if (!stickyNoteInfo?.id) {
        if (!disposed) {
          setIsInitialized(true);
          setTagsLoaded(true);
        }
        return;
      }

      try {
        // 只查询标签列表，其他状态已经在初始化时设置好了
        await queryTags(stickyNoteInfo.id);
      } catch (error) {
        console.error('获取标签失败:', error);
      } finally {
        if (!disposed) {
          setTagsLoaded(true);
          setIsInitialized(true);
        }
      }
    };

    initializeComponent().then();

    return () => {
      disposed = true;
    };
  }, [stickyNoteInfo?.id]);

  /**
   * 动态 CSS 变量
   */
  const dynamicStyles = {
    '--theme-color': themeColor,
  } as React.CSSProperties;

  /**
   * 初始化未完成时，在便笺当前位置显示加载状态
   */
  if (!isInitialized || !tagsLoaded) {
    return (
      <div
        style={{
          position: 'absolute',
          left: position.x,
          top: position.y,
          zIndex,
          width,
          height,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          boxSizing: 'border-box',
          backgroundColor:
            'rgba(255, 255, 255, 0.8)',
          borderRadius: 8,
          border: '1px solid #d9d9d9',
        }}
      >
        <Spin size="small"/>
      </div>
    );
  }

  return (
    <div
      style={{
        position: 'absolute',
        left: position.x,
        top: position.y,
        zIndex,
        width,
        height,
        ...dynamicStyles,
      }}
      onClick={bringToFront}
      className={[
        styles.stickyNote,
        isDragging ? styles.dragging : '',
      ]
        .filter(Boolean)
        .join(' ')}
    >
      {/* 标题栏 */}
      <div className={styles.header}>
        <Input
          value={sticky.title}
          className={styles.title}
          onChange={(event) => {
            const title = event.target.value;

            setSticky((previous) => ({
              ...previous,
              title,
            }));
          }}
          onBlur={() => {
            saveSticky({
              ...sticky,
              endDate: endDate.format('YYYY-MM-DD'),
            });
          }}
          onMouseDown={(event) => {
            /* 标题输入框只负责编辑文本，不参与便笺拖动 */
            event.stopPropagation();
          }}
          style={{
            backgroundColor: themeColor,
          }}
        />

        {/* 整个便笺的拖动手柄 */}
        <TagsOutlined
          className={styles.dragIcon}
          draggable={false}
          title={i18nText("app.common.stickynote.stickynote.087e8e9a")}
          onPointerDown={handleDragStart}
          onPointerMove={handleDragMove}
          onPointerUp={handleDragEnd}
          onPointerCancel={handleDragEnd}
          onClick={(event) => {
            /* 防止拖动结束后生成的 click 事件继续冒泡 */
            event.preventDefault();
            event.stopPropagation();
          }}
        />
      </div>

      {/* 操作按钮栏 */}
      <div className={styles.buttonBar}>
        {/* 主题色选择 */}
        <MyColorPicker
          notify={handleColorChange}
          initialColor={themeColor}
        />

        {/* 纯文本和 Markdown 切换 */}
        <FileWordOutlined
          className={styles.textIcon}
          onClick={(event) => {
            event.stopPropagation();

            setShowType((previous) =>
              previous === 'text'
                ? 'markdown'
                : 'text',
            );
          }}
        />

        {/* 逻辑删除 */}
        <CloseOutlined
          className={styles.deleteIcon}
          onClick={(event) => {
            event.stopPropagation();
            deleteLogical(sticky.id).then();
          }}
        />

        {/* 截止日期 */}
        <DatePicker
          className={styles.endDate}
          size="small"
          value={endDate}
          format="YYYY-MM-DD"
          onClick={(event) => {
            event.stopPropagation();
          }}
          onChange={(date) => {
            const nextEndDate = date || dayjs();

            setEndDate(nextEndDate);

            saveSticky({
              ...sticky,
              endDate: nextEndDate.format('YYYY-MM-DD'),
            });
          }}
        />
      </div>

      {/* 标签栏 */}
      <div
        className={styles.tagBar}
        style={{
          width,
        }}
      >
        {/* 添加标签 */}
        <Popover
          autoAdjustOverflow
          placement="bottomRight"
          destroyOnHidden
          fresh
          content={
            <TagsSelector
              selectTag={addTag}
              options={stickyTags}
            />
          }
        >
          <PlusSquareOutlined
            className={styles.tagAdd}
            onClick={(event) => {
              event.stopPropagation();
            }}
          />
        </Popover>

        <div className={styles.tagContainer}>
          {tags.map((tag) => (
            <MyTag
              key={tag.id}
              tag={tag}
              removeTag={removeTag}
              color={themeColor}
              buttonStyle={{
                height: 17,
                borderRadius: 4,
                marginLeft: 5,
              }}
            />
          ))}
        </div>
      </div>

      {/* 便笺内容 */}
      <SimpleEditor
        className={`${styles.content} text-white`}
        /**
         * 该 draggable 只控制 Tiptap 的文章节点拖动手柄。
         * 整个便笺的拖动只绑定在 TagsOutlined 上，因此不会冲突。
         */
        draggable
        defaultContent={sticky.content}
        onUpdate={(content: string) => {
          setSticky((previous) => ({
            ...previous,
            content,
          }));
        }}
        onBlur={(content: string) => {
          /**
           * 使用 SimpleEditor 回传的最新 content，
           * 避免 React state 尚未更新导致保存旧内容。
           */
          const nextSticky = {
            ...sticky,
            content,
          };

          setSticky(nextSticky);

          saveSticky({
            ...nextSticky,
            endDate: endDate.format('YYYY-MM-DD'),
          });
        }}
        width={width}
        height={height - 75}
        horizontalResizable
        verticalResizable
        minWidth={300}
        maxWidth={1200}
        minHeight={200}
        maxHeight={800}
        onWidthChange={(nextWidth) => {
          setWidth(nextWidth);
          debouncedResize(sticky.id, nextWidth, height);
        }}
        onHeightChange={(nextHeight) => {
          setHeight(nextHeight);
          debouncedResize(sticky.id, width, nextHeight);
        }}
        showScrollbar={false}
        showResizeIcon={false}
      />
    </div>
  );
}
