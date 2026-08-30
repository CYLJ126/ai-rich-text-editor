// src/utils/ai/tokenizer.ts

/**
 * 轻量级 token 估算（不依赖 tiktoken）
 * 中文字符按 1 token/字，英文按 4 字符/token 估算
 * 实际生产环境可替换为 tiktoken 或后端接口
 */
export function estimateTokenCount(text: string): number {
  if (!text) return 0;

  let count = 0;
  for (const char of text) {
    const code = char.charCodeAt(0);
    // CJK 统一表意文字区间
    if (
      (code >= 0x4e00 && code <= 0x9fff) ||
      (code >= 0x3400 && code <= 0x4dbf) ||
      (code >= 0x20000 && code <= 0x2a6df)
    ) {
      count += 1;
    } else {
      // 英文、数字、标点等按 0.25 token/字符估算（即 4 字符约 1 token）
      count += 0.25;
    }
  }
  return Math.ceil(count);
}

export interface TokenBudget {
  /** 光标前最多取的 token 数 */
  beforeTokens: number;
  /** 光标后最多取的 token 数 */
  afterTokens: number;
  /** 总 token 上限（beforeTokens + afterTokens） */
  totalTokens: number;
}

export const DEFAULT_TOKEN_BUDGET: TokenBudget = {
  beforeTokens: 1500,
  afterTokens: 500,
  totalTokens: 2000,
};

/**
 * 从文本中按 token 预算截取前缀和后缀
 */
export function sliceByTokenBudget(
  beforeText: string,
  afterText: string,
  budget: TokenBudget,
): { before: string; after: string } {
  // 截取前缀：从末尾往前取，保留最接近光标的内容
  const before = sliceFromEnd(beforeText, budget.beforeTokens);
  // 截取后缀：从开头往后取
  const after = sliceFromStart(afterText, budget.afterTokens);
  return {before, after};
}

function sliceFromEnd(text: string, maxTokens: number): string {
  if (estimateTokenCount(text) <= maxTokens) return text;
  // 二分查找合适的截断点
  let lo = 0;
  let hi = text.length;
  while (lo < hi) {
    const mid = Math.floor((lo + hi) / 2);
    const slice = text.slice(text.length - mid);
    if (estimateTokenCount(slice) <= maxTokens) {
      lo = mid + 1;
    } else {
      hi = mid;
    }
  }
  return text.slice(text.length - (lo - 1));
}

function sliceFromStart(text: string, maxTokens: number): string {
  if (estimateTokenCount(text) <= maxTokens) return text;
  let lo = 0;
  let hi = text.length;
  while (lo < hi) {
    const mid = Math.floor((lo + hi) / 2);
    const slice = text.slice(0, mid);
    if (estimateTokenCount(slice) <= maxTokens) {
      lo = mid + 1;
    } else {
      hi = mid;
    }
  }
  return text.slice(0, lo - 1);
}
