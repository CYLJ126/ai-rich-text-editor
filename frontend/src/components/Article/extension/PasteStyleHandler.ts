import {Extension} from '@tiptap/core';
import {Plugin, PluginKey} from '@tiptap/pm/state';
import {PasteStyleOptions, PasteStyleStorage} from "@/types/rt.type";

// ----------------------------------------------------------------
// 默认配置（false = 粘贴时删除该属性，true = 粘贴时保留该属性）
// ----------------------------------------------------------------
const DEFAULT_OPTIONS: PasteStyleOptions = {
  fontFamily: false,
  fontSize: false,
  color: true,
  backgroundColor: false,
  fontWeight: true,
  fontStyle: false,
  textDecoration: false,
  customProperties: [],
}

// ----------------------------------------------------------------
// CSS 属性映射表：配置 key → 实际 CSS 属性名
// ----------------------------------------------------------------
const CSS_PROPERTY_MAP: Record<
  keyof Omit<PasteStyleOptions, 'customProperties'>,
  string
> = {
  fontFamily: 'font-family',
  fontSize: 'font-size',
  color: 'color',
  backgroundColor: 'background-color',
  fontWeight: 'font-weight',
  fontStyle: 'font-style',
  textDecoration: 'text-decoration',
}

// ----------------------------------------------------------------
// 工具函数：根据配置获取需要【保留】的 CSS 属性列表
// ----------------------------------------------------------------
const getPropertiesToKeep = (options: PasteStyleOptions): string[] => {
  const properties: string[] = [];

  // 遍历映射表，收集 true（需要保留）的属性
  (
    Object.keys(CSS_PROPERTY_MAP) as Array<keyof Omit<PasteStyleOptions, 'customProperties'>>
  ).forEach((key) => {
    if (options[key]) {
      properties.push(CSS_PROPERTY_MAP[key])
    }
  })

  // 合并自定义保留属性（有值才合并）
  if (options.customProperties?.length) {
    properties.push(...options.customProperties)
  }

  return properties
}

// ----------------------------------------------------------------
// 核心处理函数：保留声明的属性，其余全部删除
// ----------------------------------------------------------------
const processHTML = (
  html: string,
  options: PasteStyleOptions
): string => {
  const parser = new DOMParser()
  const doc = parser.parseFromString(html, 'text/html')

  // 获取需要保留的属性集合（用 Set 提升查找性能）
  const propertiesToKeep = new Set(getPropertiesToKeep(options))

  // 处理所有带 style 属性的元素
  doc.querySelectorAll('[style]').forEach((el) => {
    const element = el as HTMLElement
    const style = element.style

    // 获取当前元素所有的 CSS 属性名（转为数组避免遍历时修改影响迭代）
    const existingProperties = Array.from(style)

    // 删除所有不在保留列表中的属性
    existingProperties.forEach((prop) => {
      if (!propertiesToKeep.has(prop)) {
        style.removeProperty(prop)
      }
    })

    // style 属性清空后移除，避免留下空的 style=""
    if (!element.getAttribute('style')?.trim()) {
      element.removeAttribute('style')
    }
  })

  // ----------------------------------------------------------------
  // 处理 <font> 标签的特殊 HTML 属性（非 style，需单独处理）
  // ----------------------------------------------------------------

  // face 属性对应 font-family
  if (!options.fontFamily) {
    doc.querySelectorAll('font[face]').forEach((el) => {
      el.removeAttribute('face')
    })
  }

  // color 属性对应文字颜色
  if (!options.color) {
    doc.querySelectorAll('font[color]').forEach((el) => {
      el.removeAttribute('color')
    })
  }

  // size 属性对应 font-size
  if (!options.fontSize) {
    doc.querySelectorAll('font[size]').forEach((el) => {
      el.removeAttribute('size')
    })
  }

  return doc.body.innerHTML
}

// ----------------------------------------------------------------
// 扩展定义
// ----------------------------------------------------------------
export const PasteStyleHandler = Extension.create<PasteStyleOptions, PasteStyleStorage>({
  name: 'pasteStyleHandler',

  // 默认配置（初始化时传入）
  addOptions() {
    return {...DEFAULT_OPTIONS}
  },

  // Storage：存储运行时可变状态
  addStorage() {
    // 初始 activeOptions 从 options 同步
    const activeOptions: PasteStyleOptions = {...this.options}

    return {
      activeOptions,

      /** 动态更新部分配置 */
      setOptions(patch: Partial<PasteStyleOptions>) {
        Object.assign(this.activeOptions, patch)
      },

      /** 保留所有样式（全部 true） */
      enableAll() {
        const allEnabled = Object.fromEntries(
          Object.keys(CSS_PROPERTY_MAP).map((key) => [key, true])
        ) as Omit<PasteStyleOptions, 'customProperties'>
        Object.assign(this.activeOptions, allEnabled)
      },

      /** 清除所有样式（全部 false） */
      disableAll() {
        const allDisabled = Object.fromEntries(
          Object.keys(CSS_PROPERTY_MAP).map((key) => [key, false])
        ) as Omit<PasteStyleOptions, 'customProperties'>
        Object.assign(this.activeOptions, allDisabled)
      },
    }
  },

  addProseMirrorPlugins() {
    // 通过闭包引用 storage，保证每次粘贴都读取最新配置
    const storage = this.storage

    return [
      new Plugin({
        key: new PluginKey('pasteStyleHandler'),
        props: {
          transformPastedHTML(html: string): string {
            return processHTML(html, storage.activeOptions)
          },
        },
      }),
    ]
  },
})
