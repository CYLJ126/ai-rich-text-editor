export interface AiContextSettings {
  /** 最多携带的上下文字数 */
  contextCharacterCount: number;
  /** 上下文预算中的前文占比 */
  beforeContextRatio: number;
  /** 上下文预算中的后文占比 */
  afterContextRatio: number;
}

export interface SlicedAiContext {
  before: string;
  after: string;
}

export const AI_CURSOR_PLACEHOLDER = '<|cursor|>';

export const DEFAULT_AI_CONTEXT_SETTINGS: AiContextSettings = {
  contextCharacterCount: 500,
  beforeContextRatio: 70,
  afterContextRatio: 30,
};

export function clampPercentage(value: number): number {
  if (!Number.isFinite(value)) return 0;
  return Math.min(100, Math.max(0, value));
}

/**
 * 按上下文字数预算截取目标位置前后的文字。
 *
 * rangeStart/rangeEnd 之间是操作目标（翻译选区等），不会进入上下文。
 * 当前文或后文不足以用完其预算时，剩余预算会自动分配给另一侧。
 */
export function sliceAiContext(
  fullText: string,
  rangeStart: number,
  rangeEnd: number = rangeStart,
  settings: Partial<AiContextSettings> = {},
): SlicedAiContext {
  const start = Math.min(fullText.length, Math.max(0, rangeStart));
  const end = Math.min(fullText.length, Math.max(start, rangeEnd));
  const beforeText = fullText.slice(0, start);
  const afterText = fullText.slice(end);
  const availableLength = beforeText.length + afterText.length;

  const contextCharacterCount = Math.max(
    0,
    Math.floor(
      settings.contextCharacterCount ??
        DEFAULT_AI_CONTEXT_SETTINGS.contextCharacterCount,
    ),
  );
  const beforeRatio = clampPercentage(
    settings.beforeContextRatio ??
      DEFAULT_AI_CONTEXT_SETTINGS.beforeContextRatio,
  );
  const afterRatio = clampPercentage(
    settings.afterContextRatio ?? DEFAULT_AI_CONTEXT_SETTINGS.afterContextRatio,
  );
  const ratioTotal = beforeRatio + afterRatio;
  const contextBudget = Math.min(availableLength, contextCharacterCount);

  if (contextBudget <= 0 || ratioTotal <= 0) {
    return {before: '', after: ''};
  }

  const beforeBudget = Math.round((contextBudget * beforeRatio) / ratioTotal);
  const afterBudget = contextBudget - beforeBudget;

  let beforeLength = Math.min(beforeText.length, beforeBudget);
  let afterLength = Math.min(afterText.length, afterBudget);
  let remaining = contextBudget - beforeLength - afterLength;

  if (remaining > 0) {
    const beforeCapacity = beforeText.length - beforeLength;
    const extraBefore = Math.min(beforeCapacity, remaining);
    beforeLength += extraBefore;
    remaining -= extraBefore;
  }
  if (remaining > 0) {
    const afterCapacity = afterText.length - afterLength;
    const extraAfter = Math.min(afterCapacity, remaining);
    afterLength += extraAfter;
  }

  return {
    before: beforeLength > 0 ? beforeText.slice(-beforeLength) : '',
    after: afterLength > 0 ? afterText.slice(0, afterLength) : '',
  };
}

export function buildContinuationContext(
  fullText: string,
  cursorOffset: number,
  settings: Partial<AiContextSettings> = {},
): string {
  const {before, after} = sliceAiContext(
    fullText,
    cursorOffset,
    cursorOffset,
    settings,
  );
  return `${before}${AI_CURSOR_PLACEHOLDER}${after}`;
}

export function formatTranslationContext({
  before,
  after,
}: SlicedAiContext): string {
  const sections: string[] = [];
  if (before) sections.push(`【待翻译内容前文】\n${before}`);
  if (after) sections.push(`【待翻译内容后文】\n${after}`);
  return sections.join('\n');
}
