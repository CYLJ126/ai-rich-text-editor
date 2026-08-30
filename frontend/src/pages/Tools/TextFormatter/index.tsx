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
  {label: '左对齐', value: 'left'},
  {label: '右对齐', value: 'right'},
  {label: '居中对齐', value: 'center'},
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
            label: '基础配置',
            children: (
              <div className={styles.configRow}>
                <Checkbox
                  checked={customProperty.zhOrEn}
                  onChange={(e) =>
                    updateCustomProperty({zhOrEn: e.target.checked})
                  }
                >
                  中文模式
                </Checkbox>
                <Checkbox
                  checked={!customProperty.zhOrEn}
                  onChange={(e) =>
                    updateCustomProperty({zhOrEn: !e.target.checked})
                  }
                >
                  英文模式
                </Checkbox>
                <Checkbox
                  checked={customProperty.punctuationMark}
                  onChange={(e) =>
                    updateCustomProperty({punctuationMark: e.target.checked})
                  }
                >
                  替换标点符号
                </Checkbox>
                <Checkbox
                  checked={customProperty.clearBreakLine}
                  onChange={(e) =>
                    updateCustomProperty({clearBreakLine: e.target.checked})
                  }
                >
                  消除换行
                </Checkbox>
                <Checkbox
                  checked={customProperty.compressSpace}
                  onChange={(e) =>
                    updateCustomProperty({compressSpace: e.target.checked})
                  }
                >
                  压缩空格
                </Checkbox>
                <Checkbox
                  checked={customProperty.withSpace}
                  onChange={(e) =>
                    updateCustomProperty({withSpace: e.target.checked})
                  }
                >
                  英文数字前后空格
                </Checkbox>
                <Checkbox
                  checked={customProperty.pasteFromClipboard}
                  onChange={(e) =>
                    updateCustomProperty({
                      pasteFromClipboard: e.target.checked,
                    })
                  }
                >
                  从剪切板填充
                </Checkbox>
                <Checkbox
                  checked={customProperty.rewriteClipboard}
                  onChange={(e) =>
                    updateCustomProperty({rewriteClipboard: e.target.checked})
                  }
                >
                  复制到剪切板
                </Checkbox>
              </div>
            ),
          },
          {
            key: 'list',
            label: '列表处理配置',
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
                    启用列表处理
                  </Checkbox>
                </div>

                {customProperty.handleList && (
                  <div className={styles.configRow}>
                    <span className={styles.label}>项目编号：</span>
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
                      单行标序
                    </Checkbox>
                    <Checkbox
                      checked={listProperty.withBlankLine}
                      onChange={(e) =>
                        updateListProperty({withBlankLine: e.target.checked})
                      }
                    >
                      添加空行
                    </Checkbox>
                    <Checkbox
                      checked={listProperty.autoRemovePrefix}
                      onChange={(e) =>
                        updateListProperty({
                          autoRemovePrefix: e.target.checked,
                        })
                      }
                    >
                      自动去除列表前缀
                    </Checkbox>
                    <span className={styles.label}>结束符：</span>
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
                      句点结尾
                    </Checkbox>
                    <InputNumber
                      className={styles.prefixRemove}
                      value={listProperty.removePrefixLength}
                      min={0}
                      addonBefore="删除前缀"
                      addonAfter="个字符"
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
            label: 'Markdown 表格配置',
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
                    启用 Markdown 表格处理
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
                      去除表格中的加粗
                    </Checkbox>
                    <Checkbox
                      checked={markdownTableProperty.compressSpaces}
                      onChange={(e) =>
                        updateMarkdownTableProperty({
                          compressSpaces: e.target.checked,
                        })
                      }
                    >
                      压缩表格中的空格
                    </Checkbox>
                    <span className={styles.label}>边框对齐：</span>
                    <Select
                      className={styles.serialMark}
                      value={markdownTableProperty.borderAlignment}
                      options={borderAlignmentOptions}
                      onChange={(value) =>
                        updateMarkdownTableProperty({borderAlignment: value})
                      }
                    />
                    <span className={styles.label}>单元格分隔符：</span>
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
          <div className={styles.textLabel}>原始文本：</div>
          <Input.TextArea
            showCount
            className={styles.textArea}
            value={textPair.raw}
            onChange={(e) => handleTextChange(e.target.value)}
            placeholder="请输入要格式化的文本..."
            onFocus={handleWindowFocus}
          />
        </div>
        <div>
          <div className={styles.textLabel}>格式化结果：</div>
          <Input.TextArea
            showCount
            className={`${styles.textArea} ${styles.readOnly}`}
            value={textPair.formatted}
            readOnly
            placeholder="格式化后的文本将显示在这里..."
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
