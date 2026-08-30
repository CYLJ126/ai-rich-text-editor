function charValue(char) {
    const code = char.charCodeAt(0);
    // Unicode编码范围
    // 汉字基本区（常用汉字）: 0x4e00 - 0x9fa5
    if ((code >= 0x4e00 && code <= 0x9fa5) || (code >= 0x3400 && code <= 0x4dbf)) {
        return 14;
    } else if (
        (code >= 0x3000 && code <= 0x303f) || // CJK符号和标点
        (code >= 0xff00 && code <= 0xffef) || // 全角ASCII
        (code >= 0x2e80 && code <= 0x2eff) || // CJK部首补充
        (code >= 0xf900 && code <= 0xfaff) || // CJK兼容象形文字
        (code >= 0x2f00 && code <= 0x2fdf) || // 康熙部首
        (code >= 0x2ff0 && code <= 0x2fff)
    ) {
        // 康熙部首扩展
        return 14;
    } else if (
        (code >= 65 && code <= 90) || // 大写英文字母
        (code >= 97 && code <= 122) || // 小写英文字母
        (code >= 32 && code <= 47) || // 标点符号
        (code >= 58 && code <= 64) || // 标点符号
        (code >= 91 && code <= 96) || // 标点符号
        (code >= 123 && code <= 126)
    ) {
        // 标点符号
        return 7;
    } else {
        return 7; // 其他字符，可以根据需要返回其他值或忽略
    }
}

/**
 * 按字符串计算输入框长度，中文、英文字符长度长度不同
 * @param str 字符串
 */
export function calculateStringValue(str) {
    let totalValue = 0;
    for (const element of str) {
        totalValue += charValue(element);
    }
    return totalValue + 30;
}
