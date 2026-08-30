import {jsonPost, jsonPostList} from './api';
// 格式化时间为本地时间
import 'dayjs/locale/zh-cn';

/**  ----------------- WeeklyDaysController start ----------------- */
/**
 * 返回周下拉列表
 *
 * @param time 时间
 * @param offset 偏移量
 */
export async function getWeekInfoList(time: any, offset: any) {
  return jsonPost('/dw/weeklyDays/getWeekInfoList', {time: time, offset: offset});
}

/**
 * #返回当前周对应的表头，即周一到周天
 *
 * @param weekId 第几周
 */
export async function getWeekDaysHeader(weekId?: number) {
  return jsonPost('/dw/weeklyDays/listWeekDaysHeader', {weekId: weekId});
}

/**  ----------------- WeeklyDaysController end ----------------- */

/**  ----------------- StickyController start ----------------- */

/**
 * 根据条件查询便笺 ID 列表
 * @param param 请求参数
 */
export async function listStickies(param: any) {
  return jsonPostList('/dw/sticky/listStickies', param);
}

/**
 * 获取便笺详情
 * @param id 请求参数
 */
export async function getStickyById(id?: number) {
  return jsonPost('/dw/sticky/getStickyById', {id: id});
}

/**
 * 添加便笺
 * @param param 请求参数
 */
export async function addSticky(param: any) {
  return jsonPost('/dw/sticky/addSticky', param);
}

/**
 * 更新便笺标题、 内容
 * @param param 请求参数
 */
export async function updateSticky(param: any) {
  return jsonPost('/dw/sticky/updateSticky', param);
}

/**
 * 更新便笺宽度、高度
 * @param id ID
 * @param width 宽度
 * @param height 高度
 */
export async function resizeSticky(id?: number, width?: number, height?: number) {
  return jsonPost('/dw/sticky/resizeSticky', {id, width, height});
}

/**
 * 排序便笺
 * @param param 请求参数
 */
export async function orderSticky(param: any) {
  return jsonPost('/dw/sticky/orderSticky', param);
}

/**
 * 折叠或展开便笺
 * deprecated：页面自动控制高度，无需后端控制
 * @param param 请求参数
 */
export async function foldSticky(param: any) {
  return jsonPost('/dw/sticky/foldSticky', param);
}

/**
 * 更新主题色
 * @param param 请求参数
 */
export async function switchThemeColor(param: any) {
  return jsonPost('/dw/sticky/switchThemeColor', param);
}

/**
 * 逻辑删除
 * @param param 请求参数
 */
export async function deleteSticky(param: any) {
  return jsonPost('/dw/sticky/deleteSticky', param);
}

/**  ----------------- StickyController end ----------------- */

