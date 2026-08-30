import {Button, Popover, Tooltip} from 'antd';
import React, {useCallback, useEffect, useRef, useState} from 'react';

export interface DropdownOption {
  label: string;
  value: string;
  icon?: React.ReactNode;
  disabled?: boolean;
}

interface DropdownToolbarButtonProps {
  /** 按钮图标 */
  icon: React.ReactNode;
  /** 按钮显示文本 */
  label?: React.ReactNode;
  /** Tooltip 提示文字 */
  tooltip?: string;
  /** 下拉选项列表 */
  options: DropdownOption[];
  /** 当前激活值（用于高亮） */
  activeValue?: string | null;
  /** 选中某项后的回调 */
  onSelect: (value: string) => void;
}

export function DropdownToolbarButton({
  icon,
  label,
  tooltip,
  options,
  activeValue,
  onSelect,
}: DropdownToolbarButtonProps) {
  const [open, setOpen] = useState(false);
  // 键盘导航高亮索引（-1 表示无）
  const [focusedIndex, setFocusedIndex] = useState<number>(-1);
  const listRef = useRef<HTMLDivElement>(null);
  const itemRefs = useRef<(HTMLDivElement | null)[]>([]);

  // 打开时，将焦点索引定位到当前激活项
  useEffect(() => {
    if (open) {
      const idx = options.findIndex((o) => o.value === activeValue);
      setFocusedIndex(idx >= 0 ? idx : 0);
    } else {
      setFocusedIndex(-1);
    }
  }, [open, activeValue, options]);

  // 焦点索引变化时，自动滚动到该项
  useEffect(() => {
    if (focusedIndex >= 0 && itemRefs.current[focusedIndex]) {
      itemRefs.current[focusedIndex]?.scrollIntoView({
        block: 'nearest',
      });
    }
  }, [focusedIndex]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent) => {
      if (!open) return;
      if (e.key === 'ArrowDown') {
        e.preventDefault();
        setFocusedIndex((prev) =>
          prev < options.length - 1 ? prev + 1 : prev,
        );
      } else if (e.key === 'ArrowUp') {
        e.preventDefault();
        setFocusedIndex((prev) => (prev > 0 ? prev - 1 : 0));
      } else if (e.key === 'Enter') {
        e.preventDefault();
        if (focusedIndex >= 0 && !options[focusedIndex]?.disabled) {
          onSelect(options[focusedIndex].value);
          setOpen(false);
        }
      } else if (e.key === 'Escape') {
        setOpen(false);
      }
    },
    [open, focusedIndex, options, onSelect],
  );

  // 下拉列表内容
  const dropdownContent = (
    <div
      ref={listRef}
      className="max-h-52 overflow-y-auto overflow-x-hidden outline-none scrollbar-none"
      tabIndex={-1}
      onKeyDown={handleKeyDown}
    >
      {options.map((option, idx) => {
        const isActive = option.value === activeValue;
        const isFocused = idx === focusedIndex;
        const disabled = option.disabled || false;
        return (
          <div
            key={option.value}
            ref={(el) => {
              itemRefs.current[idx] = el;
            }}
            className={[
              'px-3 py-1.5 cursor-pointer rounded text-sm select-none transition-colors',
              isActive ? 'bg-slate-50 dark:bg-gray-800 font-medium' : '',
              isFocused && !isActive ? 'bg-slate-50 dark:bg-gray-800' : '',
              isFocused ? 'outline-none ring-1 ring-inset ring-gray-300' : '',
              !isActive && !isFocused
                ? 'hover:bg-slate-50 dark:hover:bg-gray-800'
                : '',
              disabled ? 'pointer-events-none opacity-50' : '',
            ]
              .filter(Boolean)
              .join(' ')}
            onMouseEnter={() => setFocusedIndex(idx)}
            onMouseLeave={() => setFocusedIndex(-1)}
            onMouseDown={(e) => {
              // 阻止触发编辑器失焦
              e.preventDefault();
            }}
            onClick={() => {
              if (disabled) return;
              onSelect(option.value);
              setOpen(false);
            }}
          >
            <span className="inline-flex items-center gap-2">
              {option.icon}
              <span>{option.label}</span>
            </span>
          </div>
        );
      })}
    </div>
  );

  return (
    <Popover
      content={dropdownContent}
      trigger="hover"
      open={open}
      onOpenChange={(v) => setOpen(v)}
      placement="bottom"
      arrow={false}
    >
      <Tooltip title={tooltip}>
        <Button size="small" onKeyDown={handleKeyDown} icon={icon}>
          {label}
        </Button>
      </Tooltip>
    </Popover>
  );
}
