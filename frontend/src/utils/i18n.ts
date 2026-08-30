import enUS from '@/locales/en-US/app';
import zhCN from '@/locales/zh-CN/app';
import zhTW from '@/locales/zh-TW/app';

export type I18nValues = Record<string, unknown>;

/**
 * Format project messages without depending on Umi's plugin manager.
 *
 * This function is deliberately safe during module evaluation: many editor
 * option lists are built before Umi finishes initializing its locale plugin.
 * Language switching reloads the page, so those module-level values are rebuilt
 * from the locale persisted by Umi in localStorage.
 */
export function i18nText(id: string, values?: I18nValues): string {
  const messages = getCurrentMessages();
  const template = messages[id] ?? (zhCN as AppMessages)[id] ?? id;
  if (!values) return template;

  return template.replace(/\{([a-zA-Z0-9_]+)\}/g, (placeholder, key) => {
    const value = values[key];
    return value === undefined || value === null ? placeholder : String(value);
  });
}

type AppMessages = Record<string, string>;
export type SupportedLocale = 'en-US' | 'zh-CN' | 'zh-TW';

const messagesByLocale: Record<string, AppMessages> = {
  'en-US': enUS as AppMessages,
  'zh-CN': zhCN as AppMessages,
  'zh-TW': zhTW as AppMessages,
};

function getCurrentMessages(): AppMessages {
  return messagesByLocale[getI18nLocale()];
}

export function getI18nLocale(): SupportedLocale {
  if (typeof window === 'undefined') return 'zh-CN';

  try {
    const locale = window.localStorage.getItem('umi_locale') ?? 'zh-CN';
    return locale in messagesByLocale ? (locale as SupportedLocale) : 'zh-CN';
  } catch {
    return 'zh-CN';
  }
}
