import ClipboardUtil from '@/utils/ClipboardUtil';
import {serialMarkMap} from './constants';
import type {ListProperty, TextPair} from './types';
import {handleText} from "./index";
import {CustomProperty} from "@/utils/textProcessor";

/**
 * 列表处理器类
 */
export class ListProcessor {
  /**
   * 自动去除列表前缀
   */
  static removeListPrefix(line: string): string {
    // 匹配各种列表前缀格式
    // 1. 数字形式: 1. 2. 3. 或 1、2、3、
    // 2. 括号形式: (1) [1] 【1】
    // 3. 字母形式: a. b. c. 或 A. B. C.
    // 4. 星号形式: *
    // 5. 中文数字: 一、二、三、
    const prefixRegex =
      /^(?:\d+[.、。]|\(\d+\)|\[\d+\]|【\d+】|[a-zA-Z][.、。]|\*\s|(?:一|二|三|四|五|六|七|八|九|十|十一|十二|十三|十四|十五|十六|十七|十八|十九|二十|二十一|二十二|二十三|二十四|二十五|二十六|二十七|二十八|二十九|三十|三十一|三十二|三十三|三十四|三十五|三十六|三十七|三十八|三十九|四十|四十一|四十二|四十三|四十四|四十五|四十六|四十七|四十八|四十九|五十|五十一|五十二|五十三|五十四|五十五|五十六|五十七|五十八|五十九|六十|六十一|六十二|六十三|六十四|六十五|六十六|六十七|六十八|六十九|七十|七十一|七十二|七十三|七十四|七十五|七十六|七十七|七十八|七十九|八十|八十一|八十二|八十三|八十四|八十五|八十六|八十七|八十八|八十九|九十|九十一|九十二|九十三|九十四|九十五|九十六|九十七|九十八|九十九|一百)、)\s*/;
    return line.replace(prefixRegex, '').trim();
  }

  /**
   * 处理列表文本
   */
  static handleListText(listProp: ListProperty, text: string): string {
    const lines = text
      .split(/[\r\n]+/g)
      .map((line) => line.trim())
      .filter((line) => line.length > 0);

    if (lines.length === 0) {
      return '';
    }

    const maxCount = Math.min(lines.length, 100);
    const serialMarks =
      serialMarkMap[listProp.serialMark] || serialMarkMap['1'];
    const processedLines: string[] = [];

    for (let i = 0; i < maxCount; i++) {
      let line = lines[i];

      // 自动去除列表前缀
      if (listProp.autoRemovePrefix) {
        line = ListProcessor.removeListPrefix(line);
      }

      // 删除指定长度的前缀
      if (listProp.removePrefixLength > 0) {
        line = line.substring(listProp.removePrefixLength).trim();
      }

      // 添加序号
      line = serialMarks[i] + line;

      // 移除末尾标点
      line = line.replace(/[,.，。；、]\s*$/, '').trim();

      // 添加结束符
      if (i === lines.length - 1) {
        // 最后一行
        line += listProp.lastWithPeriod ? '。' : listProp.endWith;
      } else {
        if (!line.endsWith('：')) {
          // 如果是以冒号结尾，则不添加分号
          line += listProp.endWith;
        }
        if (listProp.withBlankLine) {
          line += '\r\n';
        }
      }

      processedLines.push(line);
    }

    return processedLines.join('');
  }

  /**
   * 处理列表文本（包含基础文本处理）
   */
  static handleCompleteListText(
    text: string,
    customProp: CustomProperty,
    listProp: ListProperty,
    setTextPair: (textPair: TextPair) => void,
  ): void {
    // 先进行基础文本处理（不清除换行和复制到剪贴板）
    const tempProp = {
      ...customProp,
      rewriteClipboard: false,
      clearBreakLine: false,
    };

    // 按换行符分成多行，对每行调用 handleText()
    const lines = text.split(/[\r\n]+/g);
    const processedLines: string[] = [];

    for (const line of lines) {
      if (line.trim()) {
        // 只处理非空行
        const tempPair = handleText(
          line.trim(),
          tempProp,
          () => {},
        );
        processedLines.push(tempPair.formatted);
      } else {
        processedLines.push(''); // 保留空行
      }
    }

    // 过滤空行
    const nonEmptyLines = processedLines.filter((line) => line.length > 0);
    // 检查是否为单行标序模式且只有一行文本
    if (!listProp.singleLineMode && nonEmptyLines.length === 1) {
      // 只做基础文本处理，不做列表处理
      const formatted = nonEmptyLines[0];
      const finalPair: TextPair = { raw: text, formatted };
      setTextPair(finalPair);
      if (customProp.rewriteClipboard) {
        ClipboardUtil.writeText(formatted).catch(console.error);
      }
      return;
    }

    // 重新组合处理后的文本，过滤空行用于后续列表处理
    const processedText = nonEmptyLines.join('\r\n');

    // 再进行列表处理
    const formatted = ListProcessor.handleListText(listProp, processedText);
    const finalPair: TextPair = { raw: text, formatted };

    setTextPair(finalPair);

    if (customProp.rewriteClipboard) {
      ClipboardUtil.writeText(formatted).catch(console.error);
    }
  }
}
