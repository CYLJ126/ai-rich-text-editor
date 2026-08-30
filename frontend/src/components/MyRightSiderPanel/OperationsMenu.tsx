import {i18nText} from '@/utils/i18n';
import React from "react";
import {App, Dropdown} from "antd";
import clsx from "clsx";
import {EllipsisOutlined} from "@ant-design/icons";
import {RightSiderItem, RightSiderItemOption} from "./type";

const OperationsMenu: React.FC<{
  item: RightSiderItem;
  operations: RightSiderItemOption[];
}> = ({item, operations}) => {
  const {modal} = App.useApp();

  const menuItems: any[] = [...operations]
    .sort((a, b) => (a.order ?? 999) - (b.order ?? 999))
    .map((opt) => {
      if (opt.type === 'divider') {
        return opt;
      }
      return {
        key: opt.key,
        danger: opt.isDanger,
        icon: opt.icon,
        label: opt.optionRender ? opt.optionRender() : opt.label,
        onClick: () => {
          if (opt.isDanger) {
            modal.confirm({
              title: i18nText("app.common.myrightsiderpanel.operationsmenu.cb7b3d8f"),
              content: i18nText("app.common.myrightsiderpanel.operationsmenu.86989ab5", {value0: opt.label}),
              okText: i18nText("app.common.myrightsiderpanel.operationsmenu.017e90af"),
              cancelText: i18nText("app.common.myrightsiderpanel.operationsmenu.19891573"),
              okButtonProps: {danger: true},
              onOk: () => opt.onClick?.(item),
            });
          } else {
            opt.onClick?.(item);
          }
        },
      }
    });

  return (
    <Dropdown
      menu={{items: menuItems}}
      trigger={["click"]}
      placement="bottomRight"
    >
      <button
        className={clsx(
          "item-operations",
          "flex items-center justify-center",
          "w-6 h-6 rounded-md border-0 bg-transparent cursor-pointer",
          "hover:bg-black/10 dark:hover:bg-white/10",
          "transition-colors",
          "opacity-0 pointer-events-none"
        )}
        onClick={(e) => e.stopPropagation()}
      >
        <EllipsisOutlined className="text-sm"/>
      </button>
    </Dropdown>
  );
};

export default OperationsMenu;
