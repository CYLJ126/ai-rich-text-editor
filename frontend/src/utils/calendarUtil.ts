import {i18nText} from '@/utils/i18n';
export const weekDays = [
    {value: 1, label: i18nText("app.common.utils.calendarutil.887410d1")},
    {value: 2, label: i18nText("app.common.utils.calendarutil.e23120a1")},
    {value: 3, label: i18nText("app.common.utils.calendarutil.11e81d5c")},
    {value: 4, label: i18nText("app.common.utils.calendarutil.d3bf62d9")},
    {value: 5, label: i18nText("app.common.utils.calendarutil.1b241ce1")},
    {value: 6, label: i18nText("app.common.utils.calendarutil.ac4d1700")},
    {value: 7, label: i18nText("app.common.utils.calendarutil.5a0c1272")},
];

/**
 * 根据周标签名称获取每日对象
 * @param label 周标签名称
 */
export function getWeekDayByLabel(label: string) {
    return weekDays.findIndex((item) => item.label === label);
}

/**
 * 根据周标签值获取每日对象
 * @param value 周标签值
 */
export function getWeekDayByValue(value: number) {
    return weekDays.find((item) => item.value === value);
}
