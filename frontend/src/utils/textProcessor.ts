/**
 * 格式化配置选项
 */
export interface CustomProperty {
  zhOrEn: boolean; // true-中文；false-英文；
  punctuationMark: boolean; // true-替换标点符号；false-不替换标点符号；
  clearBreakLine: boolean; // true-消除换行；false-不消除换行；
  compressSpace: boolean; // true-压缩空格；false-不压缩空格；
  withSpace: boolean; // true-英文、数字前后带空格；false-英文、数字前后不带空格；
  pasteFromClipboard: boolean; // true-从粘贴板复制内容到文本框中；false-不从粘贴板复制内容到文本框中；
  rewriteClipboard: boolean; // true-处理后复制到粘贴板；false-处理后不复制到粘贴板；
  isHandleClipboard: boolean; // true-从粘贴板复制内容到文本框中；false-不从粘贴板复制内容到文本框中；
  handleList: boolean; // true-处理成列表内容；false-默认，正常文本处理；
  handleMarkdownTable: boolean; // true-处理Markdown表格；false-默认，正常文本处理；
}

/**
 * 文本处理器类
 */
export class TextProcessor {
  /**
   * 消除换行
   */
  static handleBreakLine(text: string): string {
    return text.replace(/[\r\n]/g, '');
  }

  /**
   * 处理标点符号替换
   */
  static handlePunctuation(text: string): string {
    return text
      .replace(/\?/g, '？')
      .replace(/:/g, '：')
      .replace(/\./g, '。')
      .replace(/,/g, '，')
      .replace(/\(/g, '（')
      .replace(/\)/g, '）')
      .replace(/!/g, '！')
      .replace(/;/g, '；')
      .replace(/％/g, '%');
  }

  /**
   * 处理 URL 地址，恢复其中的英文标点符号
   */
  static handleUrls(text: string): string {
    let result = text;

    // 1. 先匹配并保护完整的 URL 模式
    const urlPatterns = [
      // 完整协议 URL：http://xxx 或 https://xxx，排除末尾的中文标点
      /(https?)[：:]\/\/([^\s。，！？；）]+)/gi,
      // www 开头的域名，排除末尾的中文标点
      /(^|[\s\u4e00-\u9fa5])(www)[。．.]([^\s。，！？；）]*)/gi,
      // ftp 等其他协议，排除末尾的中文标点
      /(ftp|file|mailto|dubbo)[：:]\/\/([^\s。，！？；）]+)/gi,
    ];

    urlPatterns.forEach((pattern) => {
      // eslint-disable-next-line @typescript-eslint/no-unused-vars
      result = result.replace(pattern, (match, ...groups) => {
        // 恢复整个匹配内容中的英文标点符号
        return match
          .replace(/：/g, ':')
          .replace(/[。．]/g, '.')
          .replace(/，/g, ',')
          .replace(/？/g, '?')
          .replace(/；/g, ';')
          .replace(/（/g, '(')
          .replace(/）/g, ')')
          .replace(/！/g, '!');
      });
    });

    // 2. 处理邮箱地址中的标点符号
    result = result.replace(
      /([a-zA-Z0-9._%+-]+)@([a-zA-Z0-9.-]+)[。．]([a-zA-Z]{2,})/g,
      '$1@$2.$3',
    );

    return result;
  }

  /**
   * 恢复英文单词中的英文标点符号
   */
  static restoreEnglishPunctuation(text: string): string {
    let result = text;

    // 1. 恢复多字母英文单词中的点号（如 Node.js, React.js）
    // 这里增加更严格的条件：前面必须是至少2个字母的单词
    result = result.replace(/([a-zA-Z]{2,})。([a-zA-Z0-9]+)/g, '$1.$2');

    // 2. 恢复版本号中的点号（如 v1.0.0, 2.3.4）
    result = result.replace(/([a-zA-Z0-9])。([0-9])/g, '$1.$2');
    result = result.replace(/([0-9])。([0-9])/g, '$1.$2');

    // 3. 恢复文件扩展名中的点号，但只针对明确的英文单词（至少2个字母）
    result = result.replace(/([a-zA-Z]{2,})。([a-zA-Z]{2,5})\b/g, '$1.$2');

    // 4. 恢复英文缩写中的点号（如 Mr., Dr., U.S.A.）
    result = result.replace(/\b([A-Z]+)。([A-Z])/g, '$1.$2');

    // 5. 恢复英文单词内部的冒号（如 http:, namespace:）
    result = result.replace(/([a-zA-Z]{2,})：([a-zA-Z])/g, '$1:$2');

    return result;
  }

  /**
   * 处理英文词组间的标点符号转换
   * 识别真正的英文词组，并恢复其内部的英文标点符号
   */
  static handleEnglishWordsInChinese(text: string): string {
    let result = text;

    // 匹配真正的英文词组：至少包含一个2字母以上的英文单词
    // 模式：中文字符 + 英文词组（包含可能的中文标点） + 中文字符
    result = result.replace(
      /([\u4e00-\u9fa5])\s*([a-zA-Z]{2,}[a-zA-Z0-9\s。，：；！？（）._-]*[a-zA-Z0-9])\s*([\u4e00-\u9fa5])/g,
      (match, before, englishGroup, after) => {
        // 对英文词组内部的标点符号进行处理
        const processedGroup = englishGroup
          // 恢复英文单词间的标点符号（只处理真正的英文单词）
          .replace(/([a-zA-Z]{2,})。([a-zA-Z0-9])/g, '$1.$2')
          .replace(/([a-zA-Z]{2,})：([a-zA-Z])/g, '$1:$2')
          .replace(/([a-zA-Z0-9])，([a-zA-Z0-9])/g, '$1,$2')
          .replace(/\s+/g, ' ') // 规范化内部空格
          .trim();

        return `${before} ${processedGroup} ${after}`;
      },
    );

    // 处理特殊情况：识别真正的英文复合词（如 Node.js）
    result = result.replace(
      /([\u4e00-\u9fa5])([a-zA-Z]{2,}[。，：；])([a-zA-Z][a-zA-Z0-9]*)([\u4e00-\u9fa5])/g,
      (match, before, firstPart, secondPart, after) => {
        // 只有当第一部分是多字母单词时，才恢复为英文点号
        if (firstPart.match(/^[a-zA-Z]{2,}[。]/)) {
          const combined = firstPart.replace(/[。]$/, '.') + secondPart;
          return `${before} ${combined} ${after}`;
        }
        // 否则保持中文标点符号
        return match;
      },
    );

    return result;
  }

  /**
   * 智能处理混合标点符号
   * 根据上下文环境决定是否转换标点符号
   */
  static smartPunctuationHandling(text: string): string {
    let result = text;

    // 保护 URL 不被标点符号转换影响
    const urls: string[] = [];
    let urlIndex = 0;

    // 临时替换 URL
    result = result.replace(
      /(https?[：:]\/\/[^\s]+|www[。．.][^\s]+|[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}(?:\/[^\s]*)?)/g,
      (match) => {
        urls.push(match);
        return `__URL_${urlIndex++}__`;
      },
    );

    // 保护邮箱地址
    const emails: string[] = [];
    let emailIndex = 0;

    result = result.replace(
      /[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}/g,
      (match) => {
        emails.push(match);
        return `__EMAIL_${emailIndex++}__`;
      },
    );

    // 处理英文词组
    result = TextProcessor.handleEnglishWordsInChinese(result);

    // 恢复邮箱
    emails.forEach((email, index) => {
      result = result.replace(`__EMAIL_${index}__`, email);
    });

    // 恢复 URL
    urls.forEach((url, index) => {
      result = result.replace(`__URL_${index}__`, url);
    });

    return result;
  }

  /**
   * 处理数字中的逗号（千分位分隔符）
   */
  static handleNumberCommas(text: string): string {
    let result = text;

    // 处理数字中的逗号，支持中英文逗号
    // 匹配模式：数字 + 逗号 + 3位数字（千分位格式）
    // 处理中文逗号
    result = result.replace(/(\d)，(\d{3})\b/g, '$1,$2');
    result = result.replace(/(\d)，(\d{1,3})，(\d{3})\b/g, '$1,$2,$3');
    result = result.replace(
      /(\d)，(\d{1,3})，(\d{1,3})，(\d{3})\b/g,
      '$1,$2,$3,$4',
    );

    // 处理更复杂的千分位情况（支持多级千分位）
    // 使用循环处理任意长度的千分位数字
    let changed = true;
    let iterations = 0;
    const maxIterations = 10; // 防止无限循环

    while (changed && iterations < maxIterations) {
      changed = false;
      iterations++;

      // 匹配：数字 + 中文逗号 + 1-3位数字 + 英文逗号 + 3位数字
      const newResult = result.replace(/(\d)，(\d{1,3}),(\d{3})/g, '$1,$2,$3');
      if (newResult !== result) {
        result = newResult;
        changed = true;
        continue;
      }

      // 匹配：数字 + 中文逗号 + 1-3位数字，后面没有更多数字
      const newResult2 = result.replace(/(\d)，(\d{1,3})(?!\d)/g, '$1,$2');
      if (newResult2 !== result) {
        result = newResult2;
        changed = true;
        continue;
      }

      // 匹配：数字 + 中文逗号 + 1-3位数字 + 中文逗号
      const newResult3 = result.replace(/(\d)，(\d{1,3})，/g, '$1,$2,');
      if (newResult3 !== result) {
        result = newResult3;
        changed = true;
      }
    }

    // 处理小数点后的情况，确保逗号在小数点前
    result = result.replace(/(\d)，(\d{3})\.(\d+)/g, '$1,$2.$3');

    return result;
  }

  /**
   * 处理代码块（反引号包裹的内容）
   */
  static handleCodeBlocks(text: string): string {
    let result = text;

    // 匹配所有反引号包裹的内容，包括空格
    result = result.replace(/`([^`]*?)`/g, (match, content) => {
      // 去除内容首尾的空格，但保留内部空格
      const trimmedContent = content.trim();
      return `\`${trimmedContent}\``;
    });

    // 在反引号前后与中文字符间添加空格
    result = result.replace(/([\u4e00-\u9fa5])`/g, '$1 `');
    result = result.replace(/`([\u4e00-\u9fa5])/g, '` $1');

    return result;
  }

  /**
   * 处理斜杠前后的空格
   */
  static handleSlash(text: string): string {
    // 去除斜杠前后的所有空格，然后重新处理
    let result = text.replace(/\s*\/\s*/g, '/');

    // 在斜杠前后的中英文字符间添加适当空格
    // 中文字符 + / + 英文字符 -> 中文字符 / 英文字符
    result = result.replace(/([\u4e00-\u9fa5])\/([a-zA-Z0-9])/g, '$1/$2');

    // 英文字符 + / + 中文字符 -> 英文字符 / 中文字符
    result = result.replace(/([a-zA-Z0-9])\/([\u4e00-\u9fa5])/g, '$1/$2');

    return result;
  }

  /**
   * 处理空格和中英文混排
   */
  static handleSpacing(text: string): string {
    let result = text;

    // 1. 先处理代码块，避免被后续处理影响
    result = TextProcessor.handleCodeBlocks(result);

    // 2. 处理斜杠（在其他空格处理之前）
    result = TextProcessor.handleSlash(result);

    // 3. 处理数字中的逗号（千分位）
    result = TextProcessor.handleNumberCommas(result);

    // 4. 在中文字符和英文字符之间添加空格
    // 但要避免影响已经处理过的代码块
    const codeBlocks: string[] = [];
    let codeIndex = 0;

    // 临时替换代码块，避免被空格处理影响
    result = result.replace(/`[^`]*`/g, (match) => {
      codeBlocks.push(match);
      return `__CODE_BLOCK_${codeIndex++}__`;
    });

    // 中文 + 英文字母/数字
    result = result.replace(/([\u4e00-\u9fa5])([a-zA-Z0-9])/g, '$1 $2');
    // 英文字母/数字 + 中文
    result = result.replace(/([a-zA-Z0-9])([\u4e00-\u9fa5])/g, '$1 $2');

    // 5. 处理英文单词间的标点符号（确保英文单词内部的点号不被破坏）
    const englishWordPattern = /\b[a-zA-Z][a-zA-Z0-9._-]*\.[a-zA-Z0-9._-]*\b/g;
    const englishWords: string[] = [];
    let wordIndex = 0;

    // 临时替换英文单词，避免被后续处理破坏
    result = result.replace(englishWordPattern, (match) => {
      englishWords.push(match);
      return `__ENGLISH_WORD_${wordIndex++}__`;
    });

    // 6. 格式化数学算式
    result = result.replace(/(\d)\s*([+\-*=])\s*(\d)/g, '$1 $2 $3');
    result = result.replace(/([\u4e00-\u9fa5])\s*([+\-*=])\s*(\d)/g, '$1 $2$3');

    // 7. 处理百分号
    result = result.replace(/(\d)\s*%/g, '$1%');
    result = result.replace(/%\s*([\u4e00-\u9fa5])/g, '% $1');

    // 8. 处理货币符号
    result = result.replace(/([$￥])\s*(\d)/g, '$1$2');

    // 9. 处理其他符号
    result = result.replace(
      /(\d|[\u4e00-\u9fa5])\s*(&)\s*([a-zA-Z])/g,
      '$1 $2 $3',
    );

    // 10. 恢复英文单词
    englishWords.forEach((word, index) => {
      result = result.replace(`__ENGLISH_WORD_${index}__`, word);
    });

    // 11. 恢复代码块
    codeBlocks.forEach((block, index) => {
      result = result.replace(`__CODE_BLOCK_${index}__`, block);
    });

    return result;
  }

  /**
   * 压缩空格
   */
  static compressSpaces(text: string): string {
    let result = text;

    // 先保护代码块不被压缩
    const codeBlocks: string[] = [];
    let codeIndex = 0;

    result = result.replace(/`[^`]*`/g, (match) => {
      codeBlocks.push(match);
      return `__CODE_BLOCK_${codeIndex++}__`;
    });

    // 1. 压缩多个连续空格为一个
    result = result.replace(/[ \t]+/g, ' ');

    // 2. 去除中文字符间的空格（需要多次处理以清除交叉空格）
    // 但要保留中英文之间的空格
    for (let i = 0; i < 3; i++) {
      // 只去除纯中文字符之间的空格
      result = result.replace(/([\u4e00-\u9fa5])\s+([\u4e00-\u9fa5])/g, '$1$2');
    }

    // 3. 去除标点符号前后的多余空格
    const chinesePunctuations = `！，。？；（）''""…、：【】`;

    // 去除标点符号前的空格
    for (let i = 0; i < 2; i++) {
      result = result.replace(
        new RegExp(`\\s+([${chinesePunctuations}])`, 'g'),
        '$1',
      );
      result = result.replace(
        new RegExp(`([${chinesePunctuations}])\\s+`, 'g'),
        '$1',
      );
    }

    // 4. 处理斜杠周围的空格（确保斜杠前后没有空格）
    result = result.replace(/\s*\/\s*/g, '/');

    // 恢复代码块
    codeBlocks.forEach((block, index) => {
      result = result.replace(`__CODE_BLOCK_${index}__`, block);
    });

    return result.trim();
  }

  /**
   * 格式化时间
   */
  static formatTime(text: string): string {
    // 将中文冒号替换为英文冒号
    return text.replace(/(\d{1,2})：(\d{1,2})/g, '$1:$2');
  }

  /**
   * 格式化代码块
   */
  static formatCode(text: string): string {
    // 检测可能的代码片段并用反引号包裹
    // 匹配常见的代码模式
    let result = text;

    // 匹配变量声明
    result = result.replace(
      /\b(var|let|const)\s+[a-zA-Z_$][a-zA-Z0-9_$]*\s*=\s*[^;]*;?/g,
      '`$&`',
    );

    // 匹配函数声明/调用
    result = result.replace(
      /\b(function\s+[a-zA-Z_$][a-zA-Z0-9_$]*\s*\([^)]*\)|[a-zA-Z_$][a-zA-Z0-9_$]*\s*\([^)]*\))/g,
      '`$&`',
    );

    // 匹配控制结构
    result = result.replace(
      /\b(if|else|for|while|return)\s*\([^)]*\)\s*{?[^}]*}?/g,
      '`$&`',
    );

    return result;
  }

  /**
   * 格式化数字
   */
  static formatNumbers(text: string): string {
    let result = text;

    // 处理小数点（但要避免破坏英文单词中的点号）
    // 只处理明确的数字小数点
    result = result.replace(/(\d)\s*[.。]\s*(\d)/g, '$1.$2');

    return result;
  }

  /**
   * 处理中文文本
   */
  static handleChinese(customProp: CustomProperty, text: string): string {
    let result = text;

    // 1. 消除换行
    if (customProp.clearBreakLine) {
      result = TextProcessor.handleBreakLine(result);
    }

    // 2. 替换标点符号
    if (customProp.punctuationMark) {
      result = TextProcessor.handlePunctuation(result);
      // 替换标点符号后，进行智能处理
      result = TextProcessor.smartPunctuationHandling(result);
      // 恢复英文单词中的英文标点
      result = TextProcessor.restoreEnglishPunctuation(result);
    }

    // 3. 最后处理 URL 地址（在所有标点符号处理完成后）
    result = TextProcessor.handleUrls(result);

    // 4. 格式化数字
    result = TextProcessor.formatNumbers(result);

    // 5. 格式化时间
    result = TextProcessor.formatTime(result);

    // 6. 添加空格（包含斜杠处理、代码块处理、数字逗号处理）
    if (customProp.withSpace) {
      result = TextProcessor.handleSpacing(result);
    }

    // 7. 格式化代码（自动检测代码）
    result = TextProcessor.formatCode(result);

    // 8. 压缩空格（放在最后处理）
    if (customProp.compressSpace) {
      result = TextProcessor.compressSpaces(result);
    }

    return result.trim();
  }

  /**
   * 处理英文文本
   */
  static handleEnglish(customProp: CustomProperty, text: string): string {
    let result = text;

    if (customProp.clearBreakLine) {
      result = TextProcessor.handleBreakLine(result);
    }

    // 处理 URL 地址
    result = TextProcessor.handleUrls(result);

    // 处理代码块
    result = TextProcessor.handleCodeBlocks(result);

    // 处理数字逗号
    result = TextProcessor.handleNumberCommas(result);

    if (customProp.compressSpace) {
      // 英文模式下只压缩多个空格为一个
      result = result.replace(/\s+/g, ' ').trim();
    }

    // 处理斜杠（英文模式下也应该去除斜杠前后的空格）
    result = TextProcessor.handleSlash(result);

    return result;
  }

}
