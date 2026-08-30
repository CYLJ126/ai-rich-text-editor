import type {EditorState} from '@tiptap/pm/state';

export interface TriggerResult {
  /** "//" 在文档中的起始位置 */
  from: number;
  /** "//" 在文档中的结束位置 */
  to: number;
  /** 触发有效 */
  valid: boolean;
}

/**
 * 判断当前光标是否处于有效的 "//" 触发状态
 */
export function detectTrigger(state: EditorState): TriggerResult | null {
  const {selection, doc} = state;
  const {$from} = selection;

  // ─── 1. 必须是光标选区（collapsed） ───
  if (!selection.empty) return null;

  // ─── 2. 不在代码块中 ───
  for (let depth = $from.depth; depth >= 0; depth--) {
    const node = $from.node(depth);
    if (node.type.name === 'codeBlock' || node.type.name === 'code') {
      return null;
    }
  }

  // ─── 3. 取光标所在文本块内光标前的所有文本 ───
  const cursorPos = $from.pos;
  const parentOffset = $from.parentOffset;

  // parentOffset < 2 时肯定不够两个字符
  if (parentOffset < 2) return null;

  // 使用 textBetween 获取光标前文本，\0 作为叶子节点占位符
  const textBefore = $from.parent.textBetween(0, parentOffset, '\n', '\0');

  if (textBefore.length < 2) return null;

  // ─── 4. 光标前两个字符必须是 "//" ───
  const lastTwo = textBefore.slice(-2);
  if (lastTwo !== '//') return null;

  // ─── 5. "//" 前一个字符不能是 "/" 或 ":" (避免 http:// 等) ───
  if (textBefore.length >= 3) {
    const charBefore = textBefore[textBefore.length - 3];
    if (charBefore === '/' || charBefore === ':') return null;
  }

  // ─── 6. "//" 后（光标后）第一个字符不能是 "/" 或 " " ───
  // 使用 $from.parent.content.size 替代 nodeSize - 2，语义更明确
  const parentContentSize = $from.parent.content.size;
  if (parentOffset < parentContentSize) {
    const textAfter = $from.parent.textBetween(
      parentOffset,
      parentContentSize,
      '\n',
      '\0',
    );
    if (textAfter.length > 0) {
      const firstAfter = textAfter[0];
      if (firstAfter === '/' || firstAfter === ' ') return null;
    }
  }

  // ─── 7. 计算文档级别的 from/to 位置 ───
  const from = cursorPos - 2;
  const to = cursorPos;

  return {from, to, valid: true};
}

/**
 * 从文本中提取可能的实体（简单启发式）
 */
export function extractEntities(text: string): string[] {
  const patterns = [
    /\b[A-Z][a-z]+(?:[A-Z][a-z]+)+\b/g,
    /\b[A-Z]{2,}\b/g,
    /\b[a-z]+(?:_[a-z]+)+\b/g,
    /[\u4e00-\u9fff]{2,4}/g,
  ];

  const entities = new Set<string>();
  for (const pattern of patterns) {
    const matches = text.match(pattern) ?? [];
    for (const m of matches) {
      if (m.length >= 2) entities.add(m);
    }
  }
  return Array.from(entities);
}
