import {i18nText} from '@/utils/i18n';
import {Checkbox, Collapse, Divider, Input, InputNumber, Select} from 'antd';
import React, {useState} from 'react';
import PageWrapper from '@/components/PageWrapper';
import {serialMarkOptions} from './constants';
import styles from './index.less';
import {useTextFormatter} from './useTextFormatter';
import type {TextPair} from "./types";
import ClipboardUtil from "@/utils/ClipboardUtil";
import {CustomProperty, TextProcessor} from "@/utils/textProcessor";

const borderAlignmentOptions = [
  {label: i18nText("app.tools.textformatter.2eea7883"), value: 'left'},
  {label: i18nText("app.tools.textformatter.c8558274"), value: 'right'},
  {label: i18nText("app.tools.textformatter.9a334944"), value: 'center'},
];

/**
 * 主文本处理函数
 */
export const handleText = (
  text: string,
  prop: CustomProperty,
  setTextPair: (textPair: TextPair) => void,
): TextPair => {
  const formatted = prop.zhOrEn
    ? TextProcessor.handleChinese(prop, text)
    : TextProcessor.handleEnglish(prop, text);

  const textPair: TextPair = {raw: text, formatted};
  setTextPair(textPair);

  if (prop.rewriteClipboard) {
    ClipboardUtil.writeText(formatted).catch(console.error);
  }

  return textPair;
}

const TextFormatter: React.FC = () => {
  const {
    textPair,
    customProperty,
    listProperty,
    markdownTableProperty,
    updateCustomProperty,
    updateListProperty,
    updateMarkdownTableProperty,
    handleTextChange,
    handleWindowFocus,
  } = useTextFormatter();

  // 展开/收起状态
  const [expanded, setExpanded] = useState<string[]>(['basic']);

  const handleExpandChange = (keys: string[]) => {
    let newExpanded = keys as string[];
    // 列表处理和表格处理面板互斥
    if (newExpanded.includes('list') && newExpanded.includes('markdownTable')) {
      // 如果同时展开了两个互斥面板，保留后点击的那个
      const listIndex = keys.indexOf('list');
      const tableIndex = keys.indexOf('markdownTable');
      if (listIndex > tableIndex) {
        newExpanded = newExpanded.filter((key) => key !== 'markdownTable');
      } else {
        newExpanded = newExpanded.filter((key) => key !== 'list');
      }
    }
    setExpanded(newExpanded);
    // 当展开列表处理配置时，自动启用列表处理并禁用表格处理
    const isListExpanded = newExpanded.includes('list');
    if (isListExpanded && !customProperty.handleList) {
      updateCustomProperty({handleList: true, handleMarkdownTable: false});
    }
    // 当展开 Markdown 表格配置时，启用表格处理并禁用列表处理
    const isTableExpanded = newExpanded.includes('markdownTable');
    if (isTableExpanded) {
      updateCustomProperty({handleMarkdownTable: true, handleList: false});
    }
  };

  return (
    <div className={styles.container}>
      {/* 配置区域 */}
      <Collapse
        activeKey={expanded}
        onChange={handleExpandChange}
        className={styles.configSection}
        items={[
          {
            key: 'basic',
            label: i18nText("app.tools.textformatter.bb5164a0"),
            children: (
              <div className={styles.configRow}>
                <Checkbox
                  checked={customProperty.zhOrEn}
                  onChange={(e) =>
                    updateCustomProperty({zhOrEn: e.target.checked})
                  }
                >
                  {i18nText("app.tools.textformatter.8a3da6b9")}
                </Checkbox>
                <Checkbox
                  checked={!customProperty.zhOrEn}
                  onChange={(e) =>
                    updateCustomProperty({zhOrEn: !e.target.checked})
                  }
                >
                  {i18nText("app.tools.textformatter.f79dd04c")}
                </Checkbox>
                <Checkbox
                  checked={customProperty.punctuationMark}
                  onChange={(e) =>
                    updateCustomProperty({punctuationMark: e.target.checked})
                  }
                >
                  {i18nText("app.tools.textformatter.a0aa110e")}
                </Checkbox>
                <Checkbox
                  checked={customProperty.clearBreakLine}
                  onChange={(e) =>
                    updateCustomProperty({clearBreakLine: e.target.checked})
                  }
                >
                  {i18nText("app.tools.textformatter.261ec42c")}
                </Checkbox>
                <Checkbox
                  checked={customProperty.compressSpace}
                  onChange={(e) =>
                    updateCustomProperty({compressSpace: e.target.checked})
                  }
                >
                  {i18nText("app.tools.textformatter.52172e3c")}
                </Checkbox>
                <Checkbox
                  checked={customProperty.withSpace}
                  onChange={(e) =>
                    updateCustomProperty({withSpace: e.target.checked})
                  }
                >
                  {i18nText("app.tools.textformatter.34d844c6")}
                </Checkbox>
                <Checkbox
                  checked={customProperty.pasteFromClipboard}
                  onChange={(e) =>
                    updateCustomProperty({
                      pasteFromClipboard: e.target.checked,
                    })
                  }
                >
                  {i18nText("app.tools.textformatter.da0b1ed2")}
                </Checkbox>
                <Checkbox
                  checked={customProperty.rewriteClipboard}
                  onChange={(e) =>
                    updateCustomProperty({rewriteClipboard: e.target.checked})
                  }
                >
                  {i18nText("app.tools.textformatter.77c86872")}
                </Checkbox>
              </div>
            ),
          },
          {
            key: 'list',
            label: i18nText("app.tools.textformatter.37d44d7e"),
            children: (
              <>
                <div className={styles.configRow}>
                  <Checkbox
                    checked={customProperty.handleList}
                    onChange={(e) =>
                      updateCustomProperty({
                        handleList: e.target.checked,
                        handleMarkdownTable: e.target.checked
                          ? false
                          : customProperty.handleMarkdownTable,
                      })
                    }
                  >
                    {i18nText("app.tools.textformatter.b73857da")}
                  </Checkbox>
                </div>

                {customProperty.handleList && (
                  <div className={styles.configRow}>
                    <span className={styles.label}>{i18nText("app.tools.textformatter.65c685a2")}</span>
                    <Select
                      className={styles.serialMark}
                      value={listProperty.serialMark}
                      options={serialMarkOptions}
                      onChange={(value) =>
                        updateListProperty({serialMark: value})
                      }
                    />
                    <Checkbox
                      checked={listProperty.singleLineMode}
                      onChange={(e) =>
                        updateListProperty({singleLineMode: e.target.checked})
                      }
                    >
                      {i18nText("app.tools.textformatter.a791e7e6")}
                    </Checkbox>
                    <Checkbox
                      checked={listProperty.withBlankLine}
                      onChange={(e) =>
                        updateListProperty({withBlankLine: e.target.checked})
                      }
                    >
                      {i18nText("app.tools.textformatter.23fa410c")}
                    </Checkbox>
                    <Checkbox
                      checked={listProperty.autoRemovePrefix}
                      onChange={(e) =>
                        updateListProperty({
                          autoRemovePrefix: e.target.checked,
                        })
                      }
                    >
                      {i18nText("app.tools.textformatter.402035e1")}
                    </Checkbox>
                    <span className={styles.label}>{i18nText("app.tools.textformatter.99a2f03f")}</span>
                    <Input
                      className={styles.endWithInput}
                      value={listProperty.endWith}
                      onChange={(e) =>
                        updateListProperty({endWith: e.target.value})
                      }
                    />
                    <Checkbox
                      checked={listProperty.lastWithPeriod}
                      onChange={(e) =>
                        updateListProperty({lastWithPeriod: e.target.checked})
                      }
                    >
                      {i18nText("app.tools.textformatter.e96afb0e")}
                    </Checkbox>
                    <InputNumber
                      className={styles.prefixRemove}
                      value={listProperty.removePrefixLength}
                      min={0}
                      addonBefore={i18nText("app.tools.textformatter.6a3ad96f")}
                      addonAfter={i18nText("app.tools.textformatter.670ce0bb")}
                      onChange={(value) =>
                        updateListProperty({
                          removePrefixLength: Number(value) || 0,
                        })
                      }
                    />
                  </div>
                )}
              </>
            ),
          },
          {
            key: 'markdownTable',
            label: i18nText("app.tools.textformatter.4fc3ef35"),
            children: (
              <>
                <div className={styles.configRow}>
                  <Checkbox
                    checked={customProperty.handleMarkdownTable}
                    onChange={(e) =>
                      updateCustomProperty({
                        handleMarkdownTable: e.target.checked,
                        handleList: e.target.checked
                          ? false
                          : customProperty.handleList,
                      })
                    }
                  >
                    {i18nText("app.tools.textformatter.b9f976f4")}
                  </Checkbox>
                </div>

                {customProperty.handleMarkdownTable && (
                  <div className={styles.configRow}>
                    <Checkbox
                      checked={markdownTableProperty.removeBold}
                      onChange={(e) =>
                        updateMarkdownTableProperty({
                          removeBold: e.target.checked,
                        })
                      }
                    >
                      {i18nText("app.tools.textformatter.ca2f1061")}
                    </Checkbox>
                    <Checkbox
                      checked={markdownTableProperty.compressSpaces}
                      onChange={(e) =>
                        updateMarkdownTableProperty({
                          compressSpaces: e.target.checked,
                        })
                      }
                    >
                      {i18nText("app.tools.textformatter.0af2d944")}
                    </Checkbox>
                    <span className={styles.label}>{i18nText("app.tools.textformatter.170c4b73")}</span>
                    <Select
                      className={styles.serialMark}
                      value={markdownTableProperty.borderAlignment}
                      options={borderAlignmentOptions}
                      onChange={(value) =>
                        updateMarkdownTableProperty({borderAlignment: value})
                      }
                    />
                    <span className={styles.label}>{i18nText("app.tools.textformatter.45a09311")}</span>
                    <Input
                      style={{width: 40}}
                      defaultValue=" "
                      value={markdownTableProperty.cellSeparator}
                      onChange={(e) =>
                        updateMarkdownTableProperty({
                          cellSeparator: e.target.value,
                        })
                      }
                    />
                  </div>
                )}
              </>
            ),
          },
        ]}
      />

      <Divider className={styles.divider}/>

      {/* 文本区域 */}
      <div className={styles.textSection}>
        <div>
          <div className={styles.textLabel}>{i18nText("app.tools.textformatter.89d380c2")}</div>
          <Input.TextArea
            showCount
            className={styles.textArea}
            value={textPair.raw}
            onChange={(e) => handleTextChange(e.target.value)}
            placeholder={i18nText("app.tools.textformatter.d334f423")}
            onFocus={handleWindowFocus}
          />
        </div>
        <div>
          <div className={styles.textLabel}>{i18nText("app.tools.textformatter.0df2dc8a")}</div>
          <Input.TextArea
            showCount
            className={`${styles.textArea} ${styles.readOnly}`}
            value={textPair.formatted}
            readOnly
            placeholder={i18nText("app.tools.textformatter.d569aca5")}
          />
        </div>
      </div>
    </div>
  );
};

export default () => {
  return (
    <PageWrapper>
      <TextFormatter/>
    </PageWrapper>
  );
};
