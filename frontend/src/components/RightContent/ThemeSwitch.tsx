import React from 'react';
import {Button, Flex, Popover, Switch} from 'antd';
import {BgColorsOutlined, MoonOutlined, SunOutlined} from '@ant-design/icons';
import {useThemeContext} from '@/contexts/ThemeContext';
import {useRightContentStyles} from './RightContent';
import {MyColorPicker} from "@/components";
import {colorfulColors} from "@/utils/colorUtil";

export const ThemeSwitch: React.FC = () => {
  const {styles} = useRightContentStyles();
  const {isDark, toggleAppearance, themeToken, updateThemeToken} = useThemeContext();

  const popoverContent = (
    <div className={styles.popoverContent}>
      {/* 明暗切换区域 */}
      <div className={styles.switchRow}>
        <span className={styles.switchLabel}>
          {isDark ? '暗色模式' : '亮色模式'}
        </span>
        <Switch
          checked={isDark}
          onChange={toggleAppearance}
          checkedChildren={<MoonOutlined/>}
          unCheckedChildren={<SunOutlined/>}
        />
      </div>

      <div className={styles.divider}/>

      <Flex gap="small" justify="space-between" align="center">
        {/* 自定义颜色区域 */}
        <span className='font-bold'>主题色选择</span>
        <MyColorPicker
          className=''
          initialColorOptions={colorfulColors}
          value={themeToken.colorPrimary}
          notify={(color) => updateThemeToken({colorPrimary: color})}
        />
      </Flex>
    </div>
  );

  return (
    <Popover
      content={popoverContent}
      title="外观设置"
      trigger="hover"
      placement="bottomRight"
      arrow
    >
      <Button
        type="text"
        className={styles.action}
        aria-label="外观设置"
        icon={<BgColorsOutlined/>}
      />
    </Popover>
  );
};
