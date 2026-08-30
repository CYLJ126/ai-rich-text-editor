import React, {useCallback, useRef} from "react";
import clsx from "clsx";
import {RightSiderItem} from "./type";
import {createStyles} from "antd-style";
import OperationsMenu from "./OperationsMenu";
import {MyDynamicIcon} from "@/components";

const useStyles = createStyles(({token, css}) => ({
  listItem: css`
    position: relative;
    cursor: pointer;
    border-radius: ${token.borderRadiusLG}px;
    transition: background 0.15s ease;
    margin: 2px 8px;

    &:hover {
      background: ${token.colorFillTertiary};

      .item-operations {
        opacity: 1;
        pointer-events: auto;
      }

      .item-extra {
        opacity: 0;
        pointer-events: none;
      }
    }
  `,
  listItemActive: css`
    background: ${token.colorFillSecondary};

    &:hover {
      background: ${token.colorFillSecondary};
    }
  `,
  itemDisabled: css`
    color: ${token.colorTextDisabled};
  `,
}));

const SiderListItem: React.FC<{
  item: RightSiderItem;
  isActive: boolean;
  onClick: (item: RightSiderItem) => void;
  onDoubleClick: (item: RightSiderItem) => void;
  className?: string;
}> = ({item, isActive, onClick, onDoubleClick, className}) => {
  const {styles} = useStyles();
  const hasOperations = !!item.operations?.length;
  const clickTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  const handleClick = useCallback(() => {
    if (clickTimerRef.current) clearTimeout(clickTimerRef.current);
    clickTimerRef.current = setTimeout(() => {
      onClick(item);
      clickTimerRef.current = null;
    }, 250);
  }, [item, onClick]);

  const handleDoubleClick = useCallback(() => {
    if (clickTimerRef.current) {
      clearTimeout(clickTimerRef.current);
      clickTimerRef.current = null;
    }
    onDoubleClick(item);
  }, [item, onDoubleClick]);

  if (item.itemRender) {
    return (
      <div
        onClick={handleClick}
        onDoubleClick={handleDoubleClick}
        className={clsx(styles.listItem, isActive && styles.listItemActive, className)}
      >
        {item.itemRender()}
      </div>
    );
  }

  return (
    <div
      onClick={handleClick}
      onDoubleClick={handleDoubleClick}
      className={clsx(
        styles.listItem,
        isActive ? styles.listItemActive : '',
        "flex items-center gap-2 px-3 py-2.5",
        className
      )}
    >
      {item.icon && (
        <div className='shrink-0 flex items-center justify-center'>
          <MyDynamicIcon
            iconName={item.icon}
            withBg={{
              color: 'var(--color-bg-container)',
              background: item.backgroundColor || 'var(--color-chinese-green)',
              borderRadius: '50%',
              padding: 0,
              width: 36,
              height: 36,
              fontSize: 18,
              opacity: item.disabled ? 0.3 : 1,
            }}
          />
        </div>
      )}
      <div className="flex-1 min-w-0 flex flex-col gap-0.5">
        <div className="flex items-center gap-1">
          <span
            className={`flex-1 min-w-0 text-sm font-medium truncate leading-tight ${item.disabled ? styles.itemDisabled : ''}`}
            title={item.title}
          >
            {item.title}
          </span>
          <div className="relative flex items-center shrink-0 h-6">
            {item.extraRender && (
              <span
                className={clsx(
                  "item-extra absolute right-0 text-xs opacity-40",
                  "transition-opacity duration-150",
                  item.disabled ? styles.itemDisabled : ''
                )}
              >
                {item.extraRender(item)}
              </span>
            )}
            {hasOperations && (
              <OperationsMenu item={item} operations={item.operations!}/>
            )}
          </div>
        </div>
        {item.abstractInfo && (
          <span className={`text-xs truncate opacity-40 leading-tight ${item.disabled ? styles.itemDisabled : ''}`}>
            {item.abstractInfo}
          </span>
        )}
      </div>
    </div>
  );
};

export default SiderListItem;
