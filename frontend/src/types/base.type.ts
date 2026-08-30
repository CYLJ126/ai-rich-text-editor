/**
 * 基础参数接口
 * 对应 Java 类 BaseParam
 *
 * 注意：Java 中的 LocalDateTime 和 LocalDate 在 TS 中对应 Date 或 string
 * 实际使用时，根据后端接口约定选择合适的类型
 */
export interface BaseParam {
  /** ID */
  id?: number;

  /** 标签 ID 集合 */
  tags?: number[];

  /** 开始时间范围上限 */
  startDateTimeCeil?: string; // 格式: "yyyy-MM-dd HH:mm:ss"

  /** 开始时间范围下限 */
  startDateTimeFloor?: string; // 格式: "yyyy-MM-dd HH:mm:ss"

  /** 开始日期 */
  startDate?: string; // 格式: "yyyy-MM-dd"

  /** 开始日期范围上限 */
  startDateCeil?: string; // 格式: "yyyy-MM-dd"

  /** 开始日期范围下限 */
  startDateFloor?: string; // 格式: "yyyy-MM-dd"

  /** 结束时间范围上限 */
  endDateTimeCeil?: string; // 格式: "yyyy-MM-dd HH:mm:ss"

  /** 结束时间范围下限 */
  endDateTimeFloor?: string; // 格式: "yyyy-MM-dd HH:mm:ss"

  /** 结束日期 */
  endDate?: string; // 格式: "yyyy-MM-dd"

  /** 结束日期范围上限 */
  endDateCeil?: string; // 格式: "yyyy-MM-dd"

  /** 结束日期范围下限 */
  endDateFloor?: string; // 格式: "yyyy-MM-dd"

  /** 创建时间范围上限 */
  createTimeCeil?: string; // 格式: "yyyy-MM-dd HH:mm:ss"

  /** 创建时间范围下限 */
  createTimeFloor?: string; // 格式: "yyyy-MM-dd HH:mm:ss"

  /** 更新时间范围上限 */
  updateTimeCeil?: string; // 格式: "yyyy-MM-dd HH:mm:ss"

  /** 更新时间范围下限 */
  updateTimeFloor?: string; // 格式: "yyyy-MM-dd HH:mm:ss"

  /** 创建人 id */
  createBy?: string;

  /** 更新人 id */
  updateBy?: string;

  /** 总数 */
  total?: number;

  /** 每页显示条数，默认 10 */
  size?: number;

  /** 当前页，默认 1 */
  current?: number;

  /** 排序字段信息 */
  orders?: OrderItem[];
}

/**
 * 排序项接口
 */
export interface OrderItem {
  /** 排序字段 */
  column?: string;

  /** 是否升序 */
  asc?: boolean;
}
