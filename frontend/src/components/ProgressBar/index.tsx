import {Progress} from 'antd';
import {createStyles} from 'antd-style';
import React from 'react';

interface ProgressBarSize {
  width?: number;
  height?: number;
}

interface ProgressBarProps {
  /** 进度百分比 */
  progress: number;
  /** 进度条类型 */
  type?: 'line' | 'circle' | 'dashboard';
  /** 进度条总共步数，类型为 line 时可用 */
  steps?: number;
  /** 是否显示进度百分比 */
  showInfo?: boolean;
  /** 进度条宽度和高度 */
  size?: ProgressBarSize;
  /** 进度条线宽，进度条类型为 circle 或 dashboard 时可用 */
  strokeWidth?: number;
  /** 进度条颜色，默认 #81d3f8 */
  strokeColor?: string;
  /** 轨道颜色，默认 #c6c6c6 */
  railColor?: string;
  /** 子组件，会覆盖在进度条上方 */
  children?: React.ReactNode;
  /** 额外的样式类名 */
  className?: string;
  /** 额外的样式 */
  style?: React.CSSProperties;
}

// eslint-disable-next-line @typescript-eslint/no-unused-vars
const useStyles = createStyles(
  ({token}, {width, height}: ProgressBarSize) => ({
    // 最外层容器：固定宽高，相对定位，inline-flex 防止撑满
    container: {
      position: 'relative',
      display: 'inline-flex',   // 关键：收缩到内容宽度，不撑满父容器
      alignItems: 'center',
      width: `${width}px`,       // 固定宽度
      height: `${height}px`,
      flexShrink: 0,             // 防止被 flex 父容器压缩
    },

    // Progress 组件本身
    progress: {
      position: 'absolute',
      top: 0,
      left: 0,
      width: `${width}px !important`,  // 强制固定宽度
      height: `${height}px`,
      zIndex: 1,

      // 覆盖 antd 默认的 block 撑满行为
      '&.ant-progress': {
        display: 'block',
        lineHeight: 1,
      },

      // 修复 ant-progress-rail 圆角
      '.ant-progress-rail': {
        borderRadius: '5px !important',
      },

      '.ant-progress-track': {
        borderRadius: '5px !important',
      },
    },

    // children 覆盖层：绝对定位，完全居中
    childrenWrapper: {
      position: 'absolute',
      top: 0,
      left: 0,
      width: `${width}px`,      // 与进度条等宽
      height: `${height}px`,
      display: 'flex',
      justifyContent: 'center', // 水平居中
      alignItems: 'center',     // 垂直居中
      zIndex: 2,                // 覆盖在进度条上方
      borderRadius: '5px',
      pointerEvents: 'none',    // 不阻断进度条的鼠标事件（按需开启）
    },
  }) as any,
);

const ProgressBar: React.FC<ProgressBarProps> = ({
                                                   progress,
                                                   showInfo = true,
                                                   size = {width: 80, height: 30},
                                                   type = 'line',
                                                   steps,
                                                   strokeWidth = 6,
                                                   strokeColor = '#81d3f8',
                                                   railColor = '#c6c6c6',
                                                   children,
                                                   className,
                                                   style,
                                                 }) => {
  const {styles, cx} = useStyles({width: size.width, height: size.height}); // 同时传入 width

  return (
    <div className={cx(styles.container, className)} style={style}>
      <Progress
        percent={progress}
        showInfo={showInfo}
        strokeColor={strokeColor}
        railColor={railColor}
        size={size}
        steps={steps}
        type={type}
        // strokeWidth={strokeWidth} TODO 被浏览器识别为过时变量了，但官方文档不是过时的，先注释掉
        className={styles.progress}
      />
      {children && <div className={styles.childrenWrapper}>{children}</div>}
    </div>
  );
};

export default ProgressBar;
