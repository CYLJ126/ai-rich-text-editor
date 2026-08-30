import React, {useEffect, useMemo, useState} from 'react';
import type {ColorPickerProps, GetProp} from 'antd';
import {ColorPicker, message, Popover} from 'antd';
import styles from './index.less';
import {debounce} from 'lodash';

type Color = GetProp<ColorPickerProps, 'value'>;

// ✅ 补充 Props 类型定义
interface InnerPickerProps {
  initColor?: string;
  notify: (color: string) => void;
  initialColorOptions?: string[];
}

interface MyColorPickerProps {
  initialColor?: string;
  value?: string;
  notify: (color: string) => void;
  initialStyle?: React.CSSProperties;
  initialColorOptions?: string[];
}

const InnerPicker: React.FC<InnerPickerProps> = ({
                                                   initColor,
                                                   notify,
                                                   initialColorOptions = [],
                                                 }) => {
  const [themeColor, setThemeColor] = useState<Color>(initColor || '#81d3f8');

  const colorOptions =
    initialColorOptions.length !== 0
      ? initialColorOptions
      : ['#ce2416', '#f78922', '#f6c114', '#64bd89', '#59aec6', '#2484b6', '#7f3b83'];

  // 防抖颜色更新
  const debouncedSwitchThemeColor = useMemo(
    () =>
      debounce((color: string) => {
        try {
          notify(color);
        } catch (error) {
          message.error('颜色改变通知失败').then();
        }
      }, 300),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [notify],
  );

  // 处理颜色变化
  const handleColorChange = (newColor: string) => {
    // 立即更新 UI 显示
    setThemeColor(newColor);
    // 使用防抖函数通知外部函数
    debouncedSwitchThemeColor(newColor);
  };

  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: '5px',
        outline: 'none',
        userSelect: 'none',
      }}
      // ✅ 整个 InnerPicker 容器层拦截冒泡（兜底处理）
      onClick={(e) => e.stopPropagation()}
    >
      {/* ✅ ColorPicker 外层包裹 div，专门拦截其点击冒泡 */}
      <div
        onClick={(e) => e.stopPropagation()}
        onMouseDown={(e) => e.stopPropagation()}
      >
        <ColorPicker
          className={styles.colorPicker}
          value={themeColor}
          onChange={(color) => handleColorChange(color.toHexString())}
          showText={false}
          size="small"
          style={{outline: 'none'}}
        />
      </div>

      {/* 预设色块列表 */}
      {colorOptions.map((color) => (
        <div
          key={color}
          style={{
            height: 15,
            width: 15,
            backgroundColor: color,
            cursor: 'pointer',
            outline: 'none',
            userSelect: 'none',
            borderRadius: '2px',
          }}
          // ✅ 阻止色块点击事件冒泡
          onClick={(e) => {
            e.stopPropagation();
            handleColorChange(color);
          }}
          // ✅ 同时阻止 mouseDown 冒泡，防止 onMouseDown 触发父级行为
          onMouseDown={(e) => e.stopPropagation()}
          tabIndex={-1}
        />
      ))}
    </div>
  );
};

const MyColorPicker: React.FC<MyColorPickerProps> = ({
                                                       initialColor = '#81d3f8',
                                                       value,
                                                       notify,
                                                       initialStyle = {},
                                                       initialColorOptions = [],
                                                     }) => {
  const [themeColor, setThemeColor] = useState<string>(initialColor);

  useEffect(() => {
    if (value !== undefined) {
      setThemeColor(value);
    }
  }, [value]);

  return (
    <Popover
      autoAdjustOverflow
      placement="bottomRight"
      // ✅ Popover content 容器层也拦截冒泡
      content={
        <div onClick={(e) => e.stopPropagation()}>
          <InnerPicker
            initColor={themeColor}
            initialColorOptions={initialColorOptions}
            notify={(color) => {
              setThemeColor(color);
              notify(color);
            }}
          />
        </div>
      }
    >
      <div
        style={{
          height: 18,
          width: 18,
          backgroundColor: '#ffffff',
          borderRadius: '20%',
          border: `1px solid ${themeColor}`,
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          cursor: 'pointer',
          outline: 'none',
          userSelect: 'none',
          flexShrink: 0, // 防止收缩
          whiteSpace: 'nowrap', // 防止内容换行
          ...initialStyle,
        }}
        tabIndex={-1}
      >
        <div
          id="innerColorBlock"
          style={{
            height: '75%',
            width: '75%',
            backgroundColor: themeColor,
            borderRadius: '20%',
            outline: 'none',
            userSelect: 'none',
          }}
        />
      </div>
    </Popover>
  );
};

export default MyColorPicker;
