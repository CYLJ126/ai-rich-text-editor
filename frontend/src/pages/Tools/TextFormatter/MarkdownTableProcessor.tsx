import ClipboardUtil from '@/utils/ClipboardUtil';
import type {MarkdownTableProperty, TextPair} from './types';
import {handleText} from "./index";
import {CustomProperty} from "@/utils/textProcessor";

export class MarkdownTableProcessor {
  static handleMarkdownTable(
    text: string,
    customProp: CustomProperty,
    markdownTableProp: MarkdownTableProperty,
    setTextPair: (textPair: TextPair) => void,
  ) {
    const lines = text.split('\n').filter((line) => line.trim());
    if (lines.length < 2) {
      handleText(text, customProp, setTextPair);
      return;
    }
    if (text.startsWith('|')) {
      MarkdownTableProcessor.formatMarkdownTable(
        text,
        lines,
        customProp,
        markdownTableProp,
        setTextPair,
      );
    } else {
      MarkdownTableProcessor.rawToMarkdownTable(
        text,
        lines,
        customProp,
        markdownTableProp,
        setTextPair,
      );
    }
  }

  /**
   * 文本转 Markdown 表格
   * 1. 先按 cellSeparator 分割每行文本为单元格内容，形成一个二维数组，并确认出整个表格的最大列数；如果 cellSeparator 为空格，则需要处理一个或多个连续空格的情况；
   * 2. 每个单元格内容进行基础文本处理；
   * 3. 将格式化后的二维数组以左对齐方式拼接为 markdown 表格，按最大列数进行生成，如存在某列单元格不足，则在后面补空白单元格以对齐；
   */
  static rawToMarkdownTable(
    text: string,
    lines: string[],
    customProp: CustomProperty,
    markdownTableProp: MarkdownTableProperty,
    setTextPair: (textPair: TextPair) => void,
  ) {
    try {
      const cellSeparator = markdownTableProp.cellSeparator || ' ';
      // 步骤1: 按 cellSeparator 分割每行文本为单元格内容，形成二维数组
      const tableData: string[][] = [];
      let maxColumnCount = 0;
      for (const line of lines) {
        let cells: string[] = [];
        if (cellSeparator === ' ') {
          // 如果分隔符是空格，需要处理一个或多个连续空格的情况
          cells = line.trim().split(/\s+/);
        } else if (cellSeparator === '') {
          // 如果分隔符为空，则整行作为一个单元格
          cells = [line.trim()];
        } else {
          // 其他分隔符按常规方式分割
          cells = line.split(cellSeparator).map((cell) => cell.trim());
        }
        // 过滤掉空的单元格（除非所有单元格都为空）
        const filteredCells = cells.filter((cell) => cell.length > 0);
        if (filteredCells.length > 0 || cells.length === 1) {
          tableData.push(filteredCells.length > 0 ? filteredCells : cells);
          maxColumnCount = Math.max(
            maxColumnCount,
            filteredCells.length > 0 ? filteredCells.length : cells.length,
          );
        }
      }
      if (tableData.length === 0) {
        setTextPair({ raw: text, formatted: text });
        return;
      }
      // 步骤2: 每个单元格内容进行基础文本处理
      const processedTableData: string[][] = [];
      for (const row of tableData) {
        const processedRow: string[] = [];
        for (const cell of row) {
          // 对单元格内容进行基础文本处理（不清除换行和复制到剪贴板）
          const tempProp = {
            ...customProp,
            rewriteClipboard: false,
            clearBreakLine: true,
            compressSpace: true,
          };
          const tempPair = handleText(cell, tempProp, () => {
          });
          processedRow.push(tempPair.formatted.trim());
        }
        processedTableData.push(processedRow);
      }
      // 步骤3: 将格式化后的二维数组以左对齐方式拼接为 markdown 表格
      // 补充不足的单元格以对齐到最大列数
      const alignedTableData: string[][] = processedTableData.map((row) => {
        const alignedRow = [...row];
        // 如果当前行列数不足，则补空白单元格
        while (alignedRow.length < maxColumnCount) {
          alignedRow.push('');
        }
        return alignedRow;
      });
      // 使用左对齐格式化表格
      const formattedTable = MarkdownTableProcessor.formatTableWithAlignment(
        alignedTableData,
        markdownTableProp.borderAlignment, // 固定使用左对齐
        true, // 添加分隔行
      );
      // 组合最终结果
      const formatted = formattedTable.join('\n');
      const finalPair: TextPair = { raw: text, formatted };
      setTextPair(finalPair);
      // 如果需要复制到剪贴板
      if (customProp.rewriteClipboard) {
        ClipboardUtil.writeText(formatted).catch(console.error);
      }
    } catch (error) {
      console.error('处理文本转 Markdown 表格时出错:', error);
      setTextPair({ raw: text, formatted: text });
    }
  }

  /**
   * 处理 Markdown 表格
   */
  static formatMarkdownTable(
    text: string,
    lines: string[],
    customProp: CustomProperty,
    markdownTableProperty: MarkdownTableProperty,
    setTextPair: (textPair: TextPair) => void,
  ) {
    try {
      const tableData: string[][] = [];
      let separatorLineIndex = -1;
      // 解析表格数据
      for (let i = 0; i < lines.length; i++) {
        const line = lines[i].trim();
        // 检查是否为分隔行
        if (/^\|\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$/.test(line)) {
          separatorLineIndex = i;
          continue;
        }
        // 解析数据行
        const cells = MarkdownTableProcessor.parseCells(line);
        const processedCells: string[] = [];
        for (let cell of cells) {
          // 去除加粗格式
          if (markdownTableProperty.removeBold) {
            cell = cell.replace(/\*\*(.*?)\*\*/g, '$1');
          }

          // 压缩空格
          if (markdownTableProperty.compressSpaces) {
            cell = cell.replace(/\s+/g, ' ');
          }

          // 对单元格内容进行基础文本处理（不清除换行和复制到剪贴板）
          const tempProp = {
            ...customProp,
            rewriteClipboard: false,
            clearBreakLine: true,
            compressSpace: markdownTableProperty.compressSpaces,
          };

          const tempPair = handleText(cell, tempProp, () => {
          });
          processedCells.push(tempPair.formatted.trim());
        }
        tableData.push(processedCells);
      }

      if (tableData.length === 0) {
        setTextPair({ raw: text, formatted: text });
        return;
      }
      // 对齐处理
      const formattedTable: string[] =
        MarkdownTableProcessor.formatTableWithAlignment(
          tableData,
          markdownTableProperty.borderAlignment,
          separatorLineIndex >= 0,
        );
      // 组合最终结果
      const formatted: string = formattedTable.join('\n');
      const finalPair: TextPair = { raw: text, formatted };
      setTextPair(finalPair);
      if (customProp.rewriteClipboard) {
        ClipboardUtil.writeText(formatted).catch(console.error);
      }
    } catch (error) {
      console.error('处理 Markdown 表格时出错:', error);
      setTextPair({ raw: text, formatted: text });
    }
  }

  /**
   * 解析表格行中的单元格
   */
  private static parseCells(line: string): string[] {
    // 移除首尾的空白和可能的竖线
    let tempLine = line.trim();
    if (tempLine.startsWith('|')) {
      tempLine = tempLine.substring(1);
    }
    if (tempLine.endsWith('|')) {
      tempLine = tempLine.substring(0, tempLine.length - 1);
    }
    // 按 | 分割单元格
    return tempLine.split('|').map((cell) => cell.trim());
  }

  /**
   * 格式化表格（带对齐）
   */
  private static formatTableWithAlignment(
    tableData: string[][],
    alignment: string,
    hasSeparator: boolean,
  ): string[] {
    if (tableData.length === 0) return [];

    // 计算每列的最大宽度
    const colCount = Math.max(...tableData.map((row) => row.length));
    const colWidths: number[] = new Array(colCount).fill(0);

    for (const row of tableData) {
      for (let i = 0; i < row.length; i++) {
        if (row[i]) {
          colWidths[i] = Math.max(
            colWidths[i],
            MarkdownTableProcessor.getDisplayWidth(row[i]),
          );
        }
      }
    }

    const result: string[] = [];

    for (let i = 0; i < tableData.length; i++) {
      const cells = tableData[i];
      const formattedCells: string[] = [];
      for (let j = 0; j < colCount; j++) {
        const cell = cells[j] || '';
        const width = colWidths[j];
        let formattedCell: string;
        switch (alignment) {
          case 'left':
            formattedCell = MarkdownTableProcessor.padRight(cell, width);
            break;
          case 'right':
            formattedCell = MarkdownTableProcessor.padLeft(cell, width);
            break;
          case 'center':
            formattedCell = MarkdownTableProcessor.padCenter(cell, width);
            break;
          default:
            formattedCell = cell;
        }
        formattedCells.push(formattedCell);
      }

      result.push('| ' + formattedCells.join(' | ') + ' |');

      // 在第一行后添加分隔行（如果原来有的话）
      if (i === 0 && hasSeparator) {
        const separatorCells: string[] = [];
        for (let j = 0; j < colCount; j++) {
          const width = colWidths[j];
          let separator: string;

          switch (alignment) {
            case 'left':
              separator = ':' + '-'.repeat(Math.max(1, width - 1));
              break;
            case 'right':
              separator = '-'.repeat(Math.max(1, width - 1)) + ':';
              break;
            case 'center':
              separator = ':' + '-'.repeat(Math.max(1, width - 2)) + ':';
              break;
            default:
              separator = '-'.repeat(Math.max(1, width));
          }

          separatorCells.push(separator);
        }

        result.push('| ' + separatorCells.join(' | ') + ' |');
      }
    }

    return result;
  }

  /**
   * 计算字符串显示宽度（考虑中文字符）
   */
  private static getDisplayWidth(str: string): number {
    let width = 0;
    for (const char of str) {
      // 中文字符、全角字符等占2个位置
      if (char.match(/[\u4e00-\u9fa5\uff00-\uffef]/)) {
        width += 2;
      } else {
        width += 1;
      }
    }
    return width;
  }

  /**
   * 右填充到指定宽度
   */
  private static padRight(str: string, width: number): string {
    const currentWidth = MarkdownTableProcessor.getDisplayWidth(str);
    const paddingNeeded = Math.max(0, width - currentWidth);
    return str + ' '.repeat(paddingNeeded);
  }

  /**
   * 左填充到指定宽度
   */
  private static padLeft(str: string, width: number): string {
    const currentWidth = MarkdownTableProcessor.getDisplayWidth(str);
    const paddingNeeded = Math.max(0, width - currentWidth);
    return ' '.repeat(paddingNeeded) + str;
  }

  /**
   * 居中填充到指定宽度
   */
  private static padCenter(str: string, width: number): string {
    const currentWidth = MarkdownTableProcessor.getDisplayWidth(str);
    const paddingNeeded = Math.max(0, width - currentWidth);
    const leftPadding = Math.floor(paddingNeeded / 2);
    const rightPadding = paddingNeeded - leftPadding;
    return ' '.repeat(leftPadding) + str + ' '.repeat(rightPadding);
  }
}
