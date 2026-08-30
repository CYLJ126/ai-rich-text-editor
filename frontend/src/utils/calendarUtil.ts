export const weekDays = [
    {value: 1, label: '周一'},
    {value: 2, label: '周二'},
    {value: 3, label: '周三'},
    {value: 4, label: '周四'},
    {value: 5, label: '周五'},
    {value: 6, label: '周六'},
    {value: 7, label: '周日'},
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
