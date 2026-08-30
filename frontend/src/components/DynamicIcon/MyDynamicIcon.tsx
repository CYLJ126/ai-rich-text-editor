import type {CSSProperties} from 'react';
import React, {useMemo} from 'react';
import {QuestionCircleOutlined} from '@ant-design/icons';
import {ICON_MAP} from './iconMap';
import type {AntdIconProps} from '@ant-design/icons/lib/components/AntdIcon';

export interface DynamicIconProps extends Omit<AntdIconProps, 'className'> {
  /** 后端返回的图标名称字符串，如 "HomeOutlined" */
  iconName: string;
  /** 附加的 CSS 类名 */
  className?: string;
  /** 内联样式 */
  style?: CSSProperties;
  /** 图标颜色（语法糖，等同于 style.color） */
  color?: string;
  /** 图标尺寸（语法糖，等同于 style.fontSize） */
  size?: number | undefined;
  /** 当图标名称未找到时的回退图标名称，默认显示问号图标 */
  fallback?: string | null;
  /**
   * 背景容器样式
   * 传入此属性后，图标将被一个容器 span 包裹，容器应用该样式
   * 容器默认已内置 inline-flex 居中布局，无需手动添加
   *
   * @example
   * // 圆形红色背景
   * <MyDynamicIcon iconName="HomeOutlined" withBg={{ background: 'red', borderRadius: '50%', padding: 8 }} />
   *
   * // 圆角矩形蓝色背景
   * <MyDynamicIcon iconName="UserOutlined" withBg={{ background: '#1677ff', borderRadius: 8, padding: '4px 8px' }} />
   */
  withBg?: CSSProperties;
  /** 背景容器的附加 CSS 类名（withBg 存在时生效） */
  bgClassName?: string;
}

/**
 * 背景容器默认基础样式
 * 提供居中布局，用户传入的 withBg 会与此合并（用户样式优先）
 */
const DEFAULT_BG_STYLE: CSSProperties = {
  display: 'inline-flex',
  alignItems: 'center',
  justifyContent: 'center',
};

/**
 * 动态图标组件
 * 根据后端返回的图标名称字符串，渲染对应的 Ant Design 图标
 *
 * @example
 * // 基础用法
 * <MyDynamicIcon iconName="HomeOutlined" />
 *
 * // 带样式
 * <MyDynamicIcon iconName="UserOutlined" className="text-blue-500" size={24} color="#1677ff" />
 *
 * // 无回退图标
 * <MyDynamicIcon iconName="UnknownIcon" fallback={null} />
 *
 * // 带背景（圆形）
 * <MyDynamicIcon iconName="HomeOutlined" withBg={{ background: 'red', borderRadius: '50%', padding: 8 }} />
 */
const MyDynamicIcon: React.FC<DynamicIconProps> = ({
                                                     iconName,
                                                     className,
                                                     style,
                                                     color,
                                                     size,
                                                     fallback = 'QuestionCircleOutlined',
                                                     withBg,
                                                     bgClassName,
                                                     ...restProps
                                                   }) => {
  // 合并样式
  const mergedStyle = useMemo<CSSProperties>(() => {
    const result: CSSProperties = {...style};
    if (color) result.color = color;
    if (size !== undefined) result.fontSize = size;
    return result;
  }, [style, color, size]);

  // 背景容器样式合并：默认布局 → 用户传入的 withBg
  const mergedBgStyle = useMemo<CSSProperties>(() => {
    if (!withBg) return {};
    return {
      ...DEFAULT_BG_STYLE,
      // 用户样式优先，可覆盖默认值
      ...withBg,
    };
  }, [withBg]);

  // 从映射表中查找图标组件
  const IconComponent = useMemo(() => {
    if (!iconName) return null;

    // 直接匹配
    if (ICON_MAP[iconName]) return ICON_MAP[iconName];

    // 大小写不敏感匹配（兼容后端返回格式不规范的情况）
    const lowerKey = iconName.toLowerCase();
    const matchedKey = Object.keys(ICON_MAP).find(
      (key) => key.toLowerCase() === lowerKey,
    );
    if (matchedKey) return ICON_MAP[matchedKey];

    return null;
  }, [iconName]);

  // 确定最终渲染的图标组件（含 fallback 逻辑）
  const ResolvedIcon = useMemo(() => {
    if (IconComponent) return IconComponent;

    // 未找到图标
    if (fallback === null) return null;

    const resolved =
      fallback && ICON_MAP[fallback]
        ? ICON_MAP[fallback]
        : QuestionCircleOutlined;

    console.warn(
      `[DynamicIcon] 未找到图标: "${iconName}"，已使用回退图标: "${fallback ?? 'QuestionCircleOutlined'}"`,
    );

    return resolved;
  }, [IconComponent, fallback, iconName]);

  // 图标未找到且 fallback 为 null，直接返回 null
  if (!ResolvedIcon) return null;

  // 渲染纯图标（无背景）
  const iconNode = (
    <ResolvedIcon
      className={className}
      style={mergedStyle}
      {...restProps}
    />
  );

  // 渲染带背景容器的图标
  if (withBg) {
    return (
      <span
        className={bgClassName}
        style={mergedBgStyle}
      >
        {iconNode}
      </span>
    );
  }

  return iconNode;
};

export default MyDynamicIcon;
