/**
 * 重新添加序号，将各行的行首序号从 1 开始，并返回处理后的内容。
 * @param content 内容
 */
export function reOrder(content) {
    // 将输入内容按行分割成数组
    const lines = content.split('\n');
    // 处理每一行：移除前导空格、>开头部分、原有序号
    const processedLines = lines.map((line) => {
        // 去除前导空格
        let processed = line.trimStart();
        // 去除以">"开头及后续空格
        processed = processed.replace(/^>\s*/, '');
        // 去除行首的数字序号（如"1. "）
        processed = processed.replace(/^\d+\.\s*/, '');
        return processed;
    });
    // 重新添加序号
    const numberedLines = processedLines.map((line, index) => `${index + 1}. ${line}`);
    // 合并为字符串并返回
    return numberedLines.join('\n');
}

/**
 * 添加序号，{@link undoSerialNo} 的逆向操作
 * 支持多层级添加序号，一个空格 = 1 层
 *
 * @param content 待格式化内容
 */
export function formatSerialNo(content) {
    const lines = content.split('\n');
    if (lines.length === 0) return content;
    // 初始化层级计数器数组（第 0 层）
    let counters = [0];
    let result = [];
    for (const line of lines) {
        const trimmed = line.trimStart();
        // 计算前导空格 /Tab 数量
        const indent = line.length - trimmed.length;
        // 每缩进 1 字符算作一层
        const currentLevel = indent;
        // 扩展计数器数组至当前层级所需长度，不足部分填充0
        while (counters.length <= currentLevel) {
            counters.push(0);
        }
        // 截断计数器数组至当前层级深度，并更新当前层级的计数器
        counters = counters.slice(0, currentLevel + 1);
        counters[currentLevel]++; // 当前层级计数器自增
        // 重置更深层级的计数器（若存在）
        for (let i = currentLevel + 1; i < counters.length; i++) {
            counters[i] = 0;
        }
        // 生成序号：当前层级对应数量的 Tab + "x. "
        const serial = '\t'.repeat(currentLevel) + `${counters[currentLevel]}. `;
        result.push(serial + trimmed);
    }
    return result.join('\n');
}

/**
 * 去除序号，{@link formatSerialNo} 的逆向操作
 * @param content 待格式化内容
 */
export function undoSerialNo(content) {
    const lines = content.split('\n');
    return lines
        .map((line) => {
            // 正则匹配：行首的 Tab 缩进 + 序号（如 "\t\t1. "）
            // 将 Tab 转换为指定数量的空格（例如 1 个 Tab = 1 个空格）
            return line.replace(/^(\t*)(\d+\.\s+)/, (_, tabs) => {
                return ' '.repeat(tabs.length * 1);
            });
        })
        .join('\n');
}
