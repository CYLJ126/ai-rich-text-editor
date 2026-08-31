import { VerticalLeftOutlined, VerticalRightOutlined } from '@ant-design/icons';
import { DatePicker, Row, Select, Tooltip } from 'antd';
import { createStyles } from 'antd-style';
import dayjs, { type Dayjs } from 'dayjs';
import localeData from 'dayjs/plugin/localeData';
import quarterOfYear from 'dayjs/plugin/quarterOfYear';
import weekday from 'dayjs/plugin/weekday';
import weekOfYear from 'dayjs/plugin/weekOfYear';
import React, { type ReactNode, useEffect, useState } from 'react';
import { getI18nLocale, i18nText } from '@/utils/i18n';
import 'dayjs/locale/zh-cn';
import 'dayjs/locale/zh-tw';
import { getWeekInfoList } from '@/services/ant-design-pro/dailyWork';
import styles from './index.less';

dayjs.extend(weekOfYear);
dayjs.extend(quarterOfYear);
dayjs.extend(weekday);
dayjs.extend(localeData);
const currentLocale = getI18nLocale();
dayjs.locale(currentLocale === 'en-US' ? 'en' : currentLocale.toLowerCase());

// 时间单位类型
export type TimeUnit =
  | 'minute'
  | 'hour'
  | 'day'
  | 'week'
  | 'month'
  | 'quarter'
  | 'half-year'
  | 'year';

/**
 * 时间表示类型
 */
export interface MyTime {
  type: TimeUnit; // 时间单位
  value: any; // 对应时间单位的时间值
  time: Dayjs; // 真实的时间值
  label: string; // 时间标签（用于显示）
}

// 主题配置接口
export interface ThemeConfig {
  primaryColor?: string;
  backgroundColor?: string;
  textColor?: string;
  borderColor?: string;
}

// Header组件Props
export interface HeaderProps {
  myTime?: MyTime;
  onTimeChange?: (time: MyTime, type: 'set' | 'prev' | 'next') => void;
  children?: ReactNode;
  theme?: ThemeConfig;
  className?: string;
  style?: React.CSSProperties;
  /** 仅 type=day 时生效：是否显示切换显示周几的按钮 */
  showWeekdayToggleIcon?: boolean;
  /** 仅 type=day 时生效：是否在日期后显示星期，默认 false */
  showWeekdayProp?: boolean;
  /** 仅 type=day 时生效：showWeekday 状态变化回调 */
  onShowWeekdayChange?: (showWeekday: boolean) => void;
}

// 星期映射（中文）
export const WEEKDAY_MAP = [
  i18nText('app.common.timeheader.4611918c'),
  i18nText('app.common.timeheader.58ac84b3'),
  i18nText('app.common.timeheader.7206be4b'),
  i18nText('app.common.timeheader.b3893a65'),
  i18nText('app.common.timeheader.4f23b946'),
  i18nText('app.common.timeheader.90f6c680'),
  i18nText('app.common.timeheader.675b1554'),
];

/**
 * 根据 Dayjs 对象和是否显示星期，生成 label
 */
const buildDayLabel = (date: Dayjs, withWeekday: boolean): string => {
  const base = date.format('YYYY-MM-DD');
  if (!withWeekday) return base;
  return `${base} ${WEEKDAY_MAP[date.day()]}`;
};

// 时间单位配置
const TIME_UNIT_CONFIG = {
  minute: {
    format: 'YYYY-MM-DD HH:mm',
    label: i18nText('app.common.timeheader.854ce9d9'),
  },
  hour: {
    format: 'YYYY-MM-DD HH:00',
    label: i18nText('app.common.timeheader.fe0d88ca'),
  },
  day: {
    format: 'YYYY-MM-DD',
    label: i18nText('app.common.timeheader.c3fa2749'),
  },
  week: {
    format: i18nText('app.common.timeheader.f94c518c'),
    label: i18nText('app.common.timeheader.d109a055'),
  },
  month: {
    format: 'YYYY-MM',
    label: i18nText('app.common.timeheader.ebcd1799'),
  },
  quarter: {
    format: 'YYYY-[Q]Q',
    label: i18nText('app.common.timeheader.a378ca10'),
  },
  'half-year': {
    format: 'YYYY-[H]',
    label: i18nText('app.common.timeheader.d5ad045e'),
  },
  year: { format: 'YYYY', label: i18nText('app.common.timeheader.d3362408') },
};

// 样式定义 TODO 将主题色抽成全局变量
const useStyles = createStyles(
  (theme: ThemeConfig) =>
    ({
      switchButton: {
        svg: {
          color: theme.primaryColor || '#81d3f8',
        },
      },

      timePicker: {
        backgroundColor: theme.backgroundColor || '#81d3f8',
        '&:hover, &:focus-within': {
          backgroundColor: theme.backgroundColor || '#81d3f8',
        },
        '.ant-picker-input input': {
          color: theme.textColor || 'white',
          '&:hover, &:focus': {
            color: theme.textColor || 'white',
            backgroundColor: 'transparent',
          },
        },
      },

      timeSelect: {
        backgroundColor: `${theme.backgroundColor || '#81d3f8'} !important`,
        color: theme.textColor || '#ffffff',
      },

      // 格式切换按钮
      weekdayToggle: {
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        marginLeft: '4px',
        width: '22px',
        height: '22px',
        borderRadius: '4px',
        cursor: 'pointer',
        fontSize: '11px',
        fontWeight: 600,
        border: `1px solid ${theme.primaryColor || '#81d3f8'}`,
        color: theme.primaryColor || '#81d3f8',
        userSelect: 'none' as const,
        transition: 'all 0.2s',
        flexShrink: 0,
        '&:hover': {
          opacity: 0.8,
        },
      },

      // 激活状态
      weekdayToggleActive: {
        backgroundColor: theme.primaryColor || '#81d3f8',
        color: theme.textColor || '#ffffff',
      },
    }) as any,
);

const TimeHeader: React.FC<HeaderProps> = ({
  myTime,
  onTimeChange,
  children,
  theme = {},
  className,
  style,
  showWeekdayToggleIcon = true,
  showWeekdayProp = false,
  onShowWeekdayChange,
}) => {
  const { styles: colorStyle } = useStyles(theme);

  // ── showWeekday 状态（受控/非受控均可）──
  const isShowWeekdayControlled =
    showWeekdayProp !== undefined && onShowWeekdayChange !== undefined;
  const [showWeekdayInner, setShowWeekdayInner] =
    useState<boolean>(showWeekdayProp);

  // 实际使用的值
  const showWeekday = isShowWeekdayControlled
    ? showWeekdayProp
    : showWeekdayInner;

  // 外部 showWeekday 变化时同步内部状态
  useEffect(() => {
    setShowWeekdayInner(showWeekdayProp);
  }, [showWeekdayProp]);

  // ── currentTime 状态 ──
  const buildInitialTime = (): MyTime => {
    const now = dayjs();
    return {
      type: 'day',
      value: now,
      time: now,
      label: now.format(TIME_UNIT_CONFIG['day']['format']),
    };
  };

  const [currentTime, setCurrentTime] = useState<MyTime>(
    myTime || buildInitialTime(),
  );

  const format = TIME_UNIT_CONFIG[currentTime.type].format;

  const [timeOptions, setTimeOptions] = useState<MyTime[]>([]);

  // ── 切换星期显示 ──
  const handleToggleWeekday = () => {
    const next = !showWeekday;
    if (isShowWeekdayControlled) {
      onShowWeekdayChange?.(next);
    } else {
      setShowWeekdayInner(next);
    }

    // 同步更新 currentTime 的 label
    if (currentTime.type === 'day') {
      const updated: MyTime = {
        ...currentTime,
        label: currentTime.time.format(format),
      };
      setCurrentTime(updated);
      onTimeChange?.(updated, 'set');
    }
  };

  // ── 时间偏移函数 ──
  const offsetTime = (current: MyTime, direction: 'prev' | 'next'): MyTime => {
    const amount = direction === 'prev' ? -1 : 1;
    const fmt = TIME_UNIT_CONFIG[current.type].format;

    switch (current.type) {
      case 'day': {
        const tempDate = current.time.add(amount, 'day');
        return {
          type: 'day',
          time: tempDate,
          value: tempDate,
          label: tempDate.format(fmt),
        };
      }
      case 'week': {
        let currentIndex = -1;
        for (let i = 0; i < timeOptions.length; i++) {
          if (timeOptions[i].value === current.value) {
            currentIndex = i;
          }
        }
        const nextIndex = currentIndex + (direction === 'prev' ? -1 : 1);
        // ✅ 边界保护 防止越界返回 undefined
        if (nextIndex < 0 || nextIndex >= timeOptions.length) return current;
        return timeOptions[nextIndex];
      }
      case 'quarter':
        return { ...current, time: current.time.add(amount * 3, 'month') };
      case 'half-year':
        return { ...current, time: current.time.add(amount * 6, 'month') };
      default: {
        const tempTime = current.time.add(amount, current.type);
        return {
          type: current.type,
          time: tempTime,
          value: tempTime,
          label: tempTime.format(fmt),
        };
      }
    }
  };

  // ── 加载时间选项 获取时间选项（用于周、月等选择器）──
  useEffect(() => {
    const options: Array<MyTime> = [];
    switch (currentTime.type) {
      case 'week': {
        getWeekInfoList(currentTime.time || dayjs(), 7).then((weekList) => {
          weekList?.forEach((weekInfo: any) => {
            options.push({
              value: weekInfo.value,
              label: weekInfo.label,
              time: weekInfo.time,
              type: 'week',
            });
          });
          setTimeOptions(options);
        });
        break;
      }
      case 'month': {
        break;
      }
      case 'quarter': {
        break;
      }
      case 'half-year': {
        break;
      }
      default:
        break;
    }
  }, [currentTime]);

  // 当外部 value 变化时更新内部状态
  useEffect(() => {
    if (myTime) {
      setCurrentTime(myTime);
    }
  }, [myTime]);

  // ── 时间变化处理 ──
  const handleTimeChange = (newTime: MyTime, type: 'set' | 'prev' | 'next') => {
    setCurrentTime(newTime);
    onTimeChange?.(newTime, type);
  };

  // 处理前进/后退按钮点击
  const handleNavigation = (direction: 'prev' | 'next') => {
    const newTime = offsetTime(currentTime, direction);
    handleTimeChange(newTime, direction);
  };

  // ── 渲染时间选择器 ──
  const renderTimeSelector = () => {
    // 下拉选择（周/月/季/半年）
    if (
      ['week', 'month', 'quarter', 'half-year'].includes(
        currentTime?.type || 'week',
      )
    ) {
      // 当 options 加载完后，确保 currentTime.value 能在其中找到
      const matchedOption = timeOptions.find(
        (opt) => opt.value === currentTime.value,
      );
      return (
        <Select
          className={`${styles.timeSelect} ${colorStyle.timeSelect}`}
          options={timeOptions}
          value={matchedOption ? currentTime.value : undefined} // ✅ 找不到时显示 placeholder
          placeholder={i18nText('app.common.timeheader.85d18353')}
          loading={timeOptions.length === 0}
          onSelect={(_, option) => handleTimeChange(option as MyTime, 'set')}
        />
      );
    }

    // showTime 配置
    let showTime: string | boolean = false;
    switch (currentTime.type) {
      case 'minute':
        showTime = 'HH:mm';
        break;
      case 'hour':
        showTime = 'HH';
        break;
      default:
        showTime = false;
    }

    // day 类型：计算 picker 宽度（显示星期时加宽）
    const isDay = currentTime.type === 'day';
    const pickerWidth = isDay
      ? showWeekday
        ? '175px' // "2026-05-22 周五" 需要更宽
        : '142px'
      : '193px';

    return (
      <>
        <DatePicker
          className={`${styles.timePicker} ${colorStyle.timePicker}`}
          style={{ width: pickerWidth }}
          value={currentTime.time}
          // day 类型：format 根据 showWeekday 动态切换
          format={isDay ? (date) => buildDayLabel(date, showWeekday) : format}
          onChange={(value) => {
            if (value) {
              handleTimeChange(
                {
                  value: value,
                  time: value,
                  type: currentTime.type,
                  label: value.format(format),
                },
                'set',
              );
            }
          }}
        />

        {/* 仅 day 类型显示星期切换按钮 */}
        {isDay && showWeekdayToggleIcon && (
          <Tooltip
            title={
              showWeekday
                ? i18nText('app.common.timeheader.4196a805')
                : i18nText('app.common.timeheader.2a4f53e3')
            }
          >
            <span
              className={`
                ${colorStyle.weekdayToggle}
                ${showWeekday ? colorStyle.weekdayToggleActive : ''}
              `}
              onClick={handleToggleWeekday}
            >
              {i18nText('app.common.timeheader.d109a055')}
            </span>
          </Tooltip>
        )}
      </>
    );
  };

  return (
    <div className={`${styles.headerContainer} ${className}`} style={style}>
      <Row align="middle" wrap={false} style={{ width: '100%' }}>
        {/* 向前按钮 */}
        <VerticalRightOutlined
          className={`${styles.switchButton} ${colorStyle.switchButton}`}
          onClick={() => handleNavigation('prev')}
        />

        {/* 时间选择器 */}
        {renderTimeSelector()}

        {/* 向后按钮 */}
        <VerticalLeftOutlined
          className={`${styles.switchButton} ${colorStyle.switchButton}`}
          onClick={() => handleNavigation('next')}
        />

        {/* 子组件容器 */}
        <div className={styles.childrenContainer}>{children}</div>
      </Row>
    </div>
  );
};

export default TimeHeader;
