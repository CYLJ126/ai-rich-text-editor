import type {Node as ProseMirrorNode} from '@tiptap/pm/model';
import type {ArticleHeading} from "@/types/rt.type";

// ─── 文档文本提取 ───

interface DocTextResult {
  fullText: string;
  /** 光标在 fullText 中的偏移 */
  cursorOffset: number;
  /** 标题信息（带 fullText 偏移） */
  headings: ArticleHeading[];
}

/**
 * docPos → textOffset 映射条目
 * 表示：文档位置 docPos 对应 fullText 中 textOffset 处
 */
interface PosMapEntry {
  docPos: number;
  textOffset: number;
}

/**
 * 将 ProseMirror 文档转换为纯文本，同时建立文档位置到文本偏移的映射
 *
 * ProseMirror 位置规则：
 *  - doc 本身不占位置
 *  - 每个块节点开标签占 1 个位置（offset + 1 进入内容）
 *  - 文本节点：每个字符占 1 个位置
 *  - 叶子节点（image 等）：整个节点占 nodeSize 个位置
 *  - 块节点关闭标签占 1 个位置
 */
export function extractDocText(
  doc: ProseMirrorNode,
  cursorPos: number,
): DocTextResult {
  const parts: string[] = [];
  const headings: ArticleHeading[] = [];
  // 映射表：记录关键的 docPos → textOffset 锚点
  const posMap: PosMapEntry[] = [];

  /**
   * 递归处理节点
   * @param node  当前节点
   * @param pos   当前节点在文档中的绝对开始位置（对块节点而言是开标签位置）
   */
  function walk(node: ProseMirrorNode, pos: number): void {
    // ── 文本节点 ───
    if (node.isText) {
      const t = node.text!;
      const textStart = currentTextLength();
      // 为文本节点的每个字符建立映射
      posMap.push({docPos: pos, textOffset: textStart});
      parts.push(t);
      // 文本节点末尾锚点
      posMap.push({docPos: pos + t.length, textOffset: textStart + t.length});
      return;
    }

    // ── 硬换行 ───
    if (node.type.name === 'hardBreak') {
      posMap.push({docPos: pos, textOffset: currentTextLength()});
      parts.push('\n');
      posMap.push({docPos: pos + node.nodeSize, textOffset: currentTextLength()});
      return;
    }

    // ── 图片（叶子块节点） ───
    if (node.type.name === 'image') {
      const alt = (node.attrs?.alt as string) ?? '';
      const repr = alt ? `[图片: ${alt}]` : '';
      posMap.push({docPos: pos, textOffset: currentTextLength()});
      if (repr) parts.push(repr);
      posMap.push({docPos: pos + node.nodeSize, textOffset: currentTextLength()});
      return;
    }

    // ── 标题块 ───
    if (node.type.name === 'heading') {
      const level = (node.attrs?.level as number) ?? 1;
      const id = (node.attrs?.id as string) ?? '';
      const headingTextOffset = currentTextLength();
      // 进入块内容（跳过开标签，+1）
      posMap.push({docPos: pos, textOffset: headingTextOffset});
      posMap.push({docPos: pos + 1, textOffset: headingTextOffset});
      node.forEach((child, childOffset) => {
        walk(child, pos + 1 + childOffset);
      });
      const headingText = node.textContent;
      headings.push({id, level, text: headingText, offset: headingTextOffset});
      parts.push('\n');
      // 块结束位置
      posMap.push({docPos: pos + node.nodeSize, textOffset: currentTextLength()});
      return;
    }

    // ── 普通块节点 ───
    if (node.isBlock) {
      posMap.push({docPos: pos, textOffset: currentTextLength()});
      posMap.push({docPos: pos + 1, textOffset: currentTextLength()});
      node.forEach((child, childOffset) => {
        walk(child, pos + 1 + childOffset);
      });
      parts.push('\n');
      posMap.push({docPos: pos + node.nodeSize, textOffset: currentTextLength()});
      return;
    }

    // ── 内联容器节点（mark 等） ───
    posMap.push({docPos: pos, textOffset: currentTextLength()});
    node.forEach((child, childOffset) => {
      walk(child, pos + 1 + childOffset);
    });
    posMap.push({docPos: pos + node.nodeSize, textOffset: currentTextLength()});
  }

  /** 当前已拼接文本的长度 */
  function currentTextLength(): number {
    return parts.reduce((acc, p) => acc + p.length, 0);
  }

  // doc 根节点：其子节点从位置 1 开始（doc 开标签占位 0，内容从 1 起）
  // 实际上 doc.forEach 给出的 offset 已经是相对于 doc 内容起点的偏移
  // 绝对位置 = 1 + childOffset（因为 doc 本身开标签在位置 0，内容从 1 开始）
  doc.forEach((child, childOffset) => {
    walk(child, 1 + childOffset);
  });

  const fullText = parts.join('');

  // ── 通过映射表定位 cursorOffset ───
  const cursorOffset = resolveTextOffset(posMap, cursorPos, fullText.length);

  return {fullText, cursorOffset, headings};
}

/**
 * 通过映射表将文档位置转换为文本偏移
 * 使用插值：在相邻两个锚点之间线性映射（对文本节点完全精确）
 */
function resolveTextOffset(
  posMap: PosMapEntry[],
  docPos: number,
  textLength: number,
): number {
  if (posMap.length === 0) return 0;

  // 按 docPos 排序（构建时基本有序，但保险起见）
  const sorted = [...posMap].sort((a, b) => a.docPos - b.docPos);

  // 精确命中
  const exact = sorted.find((e) => e.docPos === docPos);
  if (exact) return exact.textOffset;

  // 超出范围
  if (docPos <= sorted[0].docPos) return sorted[0].textOffset;
  if (docPos >= sorted[sorted.length - 1].docPos) return textLength;

  // 在两个锚点之间插值
  let lo = 0;
  let hi = sorted.length - 1;
  while (lo < hi - 1) {
    const mid = (lo + hi) >> 1;
    if (sorted[mid].docPos <= docPos) lo = mid;
    else hi = mid;
  }

  const left = sorted[lo];
  const right = sorted[hi];
  const docRange = right.docPos - left.docPos;
  const textRange = right.textOffset - left.textOffset;

  if (docRange === 0) return left.textOffset;

  // 线性插值（对纯文本节点是精确的）
  const ratio = (docPos - left.docPos) / docRange;
  return left.textOffset + Math.round(ratio * textRange);
}
