import {clsx, type ClassValue} from "clsx"
import {twMerge} from "tailwind-merge"

/**
 * 合并 CSS 类名
 * 示例：
 * cn('px-2 py-1', 'px-4')  // 返回 'py-1 px-4'（px-4 覆盖 px-2）
 * cn('bg-red-500', isActive && 'bg-blue-500')  // 条件类名
 * @param inputs 类名列表
 */
export function cn(...inputs: ClassValue[]) {
  // clsx：条件性拼接类名（支持对象、数组等写法）
  // twMerge：解决 Tailwind 类名冲突（后面的类覆盖前面的）
  return twMerge(clsx(inputs))
}

/**
 * 将字符串安全转换为数字，无效值返回 undefined
 * 示例：
 * safeParseNum('123')    // 返回 123
 * safeParseNum('abc')    // 返回 undefined
 * @param value 待转换值
 */
export function safeParseNum(value?: string | null) {
  if (!value) {
    return undefined;
  }

  const num = +value;

  return isNaN(num) ? undefined : num;
}

/**
 * 将字符串首字母转为大写
 * 示例:
 * uppercaseFirstChar('hello')   // 'Hello'
 * uppercaseFirstChar('H')       // 'H'
 * @param v
 */
export function uppercaseFirstChar(v?: string) {
  if (!v?.[0] || v?.length === 1) {
    return v;
  }

  return `${v?.[0].toUpperCase()}${v?.substring(1)}`;
}
