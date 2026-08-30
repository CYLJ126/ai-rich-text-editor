import {i18nText} from '@/utils/i18n';
import React from "react";
import {RightSiderItem} from "./type";
import SiderListItem from "./SiderListItem";

interface PinnedSectionProps {
  pinnedItems: RightSiderItem[];
  activeKey?: number | string;
  onItemClick: (item: RightSiderItem) => void;
  onItemDoubleClick: (item: RightSiderItem) => void;
}

const PinnedSection: React.FC<PinnedSectionProps> = ({
                                                       pinnedItems,
                                                       activeKey,
                                                       onItemClick,
                                                       onItemDoubleClick,
                                                     }) => {
  // 无置顶项时不渲染任何内容
  if (pinnedItems.length === 0) return null;

  return (
    <div className="shrink-0 border-b border-[var(--ant-color-border-secondary)]">
      {/* 置顶区标题 */}
      <div className="mx-2 -mb-0.5 flex items-center px-1 pt-1.5 select-none">
        <span className="text-xs font-medium tracking-wide text-[var(--ant-color-text-quaternary)]">{i18nText("app.common.myrightsiderpanel.pinnedsection.a84c5184")}</span>
      </div>

      {/* 置顶列表 */}
      {pinnedItems.map((item) => (
        <SiderListItem
          key={item.key}
          item={item}
          isActive={activeKey === item.key}
          onClick={onItemClick}
          onDoubleClick={onItemDoubleClick}
        />
      ))}
    </div>
  );
};

export default PinnedSection;
