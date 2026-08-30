export interface StickyNoteInfo {
  /** 内容 */
  content: string;
  /** 创建人 */
  createBy: string;
  /** 创建时间 */
  createTime: string;
  /** 结束日期 */
  endDate: string;
  /** 折叠标识（1=折叠？） */
  foldFlag: number;
  /** 高度 */
  height: number;
  /** 主键ID */
  id: number;
  /** 排序ID */
  orderId: string | null;
  /** 显示类型：text 等 */
  showType: string;
  /** 开始日期 */
  startDate: string;
  /** 状态：1=启用？ */
  status: number;
  /** 标签 */
  tags: string | null;
  /** 主题色（十六进制） */
  themeColor: string;
  /** 标题 */
  title: string;
  /** 更新人 */
  updateBy: string;
  /** 更新时间 */
  updateTime: string;
  /** 宽度 */
  width: number;
}
