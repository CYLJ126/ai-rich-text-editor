import React from 'react';
import {ToolbarButtonItem} from "@/types/rt.type";
import {Button, Space, Tooltip} from "antd";

/** 将一组按钮渲染为 Space.Compact，避免在 TSX 中重复逻辑 */
export default function ToolbarButtonGroup({
                                             buttons = [],
                                             justify = 'flex-start',
                                             buttonBarType = 'fix',
                                           }: {
  buttons: ToolbarButtonItem[];
  /** 按钮组的水平对齐方式，默认左对齐 */
  justify?: React.CSSProperties['justifyContent'];
  buttonBarType?: 'fix' | 'float'; // 按钮栏类型，固定栏 fix 或 浮动栏 float
}) {
  return (
    <div
      style={{
        display: 'flex',
        width: '100%',
        justifyContent: justify,
        overflow: 'hidden',
      }}
    >
      <Space.Compact>
        {buttons.map((button) =>
          button.renderCustom ? (
            <React.Fragment key={button.key}>
              {button.renderCustom(buttonBarType)}
            </React.Fragment>
          ) : (
            <Tooltip key={button.key} title={button.label} autoAdjustOverflow={false}
                     placement={buttonBarType === 'fix' ? 'bottom' : 'top'}>
              <Button
                size="small"
                icon={button.icon}
                onClick={button.onClick}
              >
                {button.children ?? null}
              </Button>
            </Tooltip>
          ),
        )}
      </Space.Compact>
    </div>
  );
}
