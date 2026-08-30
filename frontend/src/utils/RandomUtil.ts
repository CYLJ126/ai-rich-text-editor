/**
 * 生成指定长度的随机UUID字符串
 * @param length 要生成的UUID长度，默认为16
 * @returns 随机UUID字符串
 */
export function generateRandomUUID(length: number = 16): string {
    if (length <= 0) {
        throw new Error('Length must be a positive number');
    }

    // 首先生成一个完整的标准UUIDv4
    const uuidTemplate = 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx';
    const fullUUID = uuidTemplate.replace(/[xy]/g, function (c) {
        const r = (Math.random() * 16) | 0;
        const v = c === 'x' ? r : (r & 0x3) | 0x8;
        return v.toString(16);
    });

    // 移除所有连字符，然后截取指定长度
    const uuidWithoutHyphens = fullUUID.replace(/-/g, '');

    // 确保不会超出最大长度
    const effectiveLength = Math.min(length, uuidWithoutHyphens.length);
    return uuidWithoutHyphens.substring(0, effectiveLength);
}
