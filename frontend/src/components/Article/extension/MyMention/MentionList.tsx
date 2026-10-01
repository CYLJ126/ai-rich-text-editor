import { Button } from 'antd';
import React, {
  forwardRef,
  useEffect,
  useImperativeHandle,
  useRef,
  useState,
} from 'react';
import { i18nText } from '@/utils/i18n';

export interface MentionListHandle {
  onKeyDown: (props: { event: KeyboardEvent }) => boolean;
}

export interface MentionListProps {
  items: string[];
  command: (item: { id: string }) => void;
}

const MentionList = forwardRef<MentionListHandle, MentionListProps>(
  ({items, command}, ref) => {
    const [selectedIndex, setSelectedIndex] = useState(0);
    const itemRefs = useRef<(HTMLDivElement | null)[]>([]);

    const selectItem = (index: number) => {
      const item = items[index];
      if (item) command({id: item});
    };

    useEffect(() => setSelectedIndex(0), [items]);

    useEffect(() => {
      itemRefs.current[selectedIndex]?.scrollIntoView({block: 'nearest'});
    }, [selectedIndex]);

    useImperativeHandle(
      ref,
      () => ({
        onKeyDown: ({event}) => {
          if (items.length === 0) return false;
          if (event.key === 'ArrowUp') {
            setSelectedIndex(
              (current) => (current + items.length - 1) % items.length,
            );
            return true;
          }
          if (event.key === 'ArrowDown') {
            setSelectedIndex((current) => (current + 1) % items.length);
            return true;
          }
          if (event.key === 'Enter') {
            selectItem(selectedIndex);
            return true;
          }
          return false;
        },
      }),
      [items, selectedIndex],
    );

    return (
      <div
        className="bg-white p-[2px] border-solid border-2 border-purple-400 rounded-[5px] flex flex-col gap-[2px] max-h-52 overflow-y-auto"
        style={{scrollbarWidth: 'none', msOverflowStyle: 'none'}}
      >
        {items.length ? (
          items.map((item, index) => (
            <div
              key={item}
              ref={(element) => {
                itemRefs.current[index] = element;
              }}
            >
              <Button
                onClick={() => selectItem(index)}
                className={`!px-[2px] !py-[2px] !text-purple-600 !border-none !shadow-none !rounded-[3px] ${
                  index === selectedIndex ? '!bg-purple-200' : '!bg-purple-100'
                } hover:!bg-purple-200`}
              >
                {item}
              </Button>
            </div>
          ))
        ) : (
          <div className="px-[2px] py-[2px] text-slate-400 text-sm">
            {i18nText('app.common.noResults')}
          </div>
        )}
      </div>
    );
  },
);

MentionList.displayName = 'MentionList';

export default MentionList;
