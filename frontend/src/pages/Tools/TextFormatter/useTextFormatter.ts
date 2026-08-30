import {useCallback, useEffect, useRef, useState} from 'react';
import {ClipboardHandler} from './clipboardHandler';
import {initialCustomProperty, initialListProperty, initialMarkdownTableProperty,} from './constants';
import {ListProcessor} from './listProcessor';
import {MarkdownTableProcessor} from './MarkdownTableProcessor';
import type {ListProperty, MarkdownTableProperty, TextPair,} from './types';
import {handleText} from "./index";
import {CustomProperty} from "@/utils/textProcessor";

/**
 * 文本格式化Hook
 */
export const useTextFormatter = () => {
  const [textPair, setTextPair] = useState<TextPair>({
    raw: '',
    formatted: '',
  });
  const [customProperty, setCustomProperty] = useState<CustomProperty>(
    initialCustomProperty,
  );
  const [listProperty, setListProperty] =
    useState<ListProperty>(initialListProperty);
  const [markdownTableProperty, setMarkdownTableProperty] =
    useState<MarkdownTableProperty>(initialMarkdownTableProperty);

  // 使用ref保存最新的配置，用于事件监听器
  const configRef = useRef({
    customProperty,
    listProperty,
    markdownTableProperty,
  });

  useEffect(() => {
    configRef.current = { customProperty, listProperty, markdownTableProperty };
  }, [customProperty, listProperty, markdownTableProperty]);

  /**
   * 更新自定义属性
   */
  const updateCustomProperty = useCallback(
    (updates: Partial<CustomProperty>) => {
      const newProp = { ...customProperty, ...updates };
      setCustomProperty(newProp);

      if (updates.handleList) {
        ListProcessor.handleCompleteListText(
          textPair.raw,
          newProp,
          listProperty,
          setTextPair,
        );
      } else if (updates.handleMarkdownTable) {
        MarkdownTableProcessor.handleMarkdownTable(
          textPair.raw,
          newProp,
          markdownTableProperty,
          setTextPair,
        );
      } else {
        handleText(textPair.raw, newProp, setTextPair);
      }
    },
    [customProperty, listProperty, markdownTableProperty, textPair.raw],
  );

  /**
   * 更新列表属性
   */
  const updateListProperty = useCallback(
    (updates: Partial<ListProperty>) => {
      const newListProp = { ...listProperty, ...updates };
      setListProperty(newListProp);
      setCustomProperty((prev) => ({
        ...prev,
        handleList: true,
        handleMarkdownTable: false,
      }));
      ListProcessor.handleCompleteListText(
        textPair.raw,
        customProperty,
        newListProp,
        setTextPair,
      );
    },
    [listProperty, customProperty, textPair.raw],
  );

  /**
   * 更新 Markdown 表格属性
   */
  const updateMarkdownTableProperty = useCallback(
    (updates: Partial<MarkdownTableProperty>) => {
      const newTableProp = { ...markdownTableProperty, ...updates };
      setMarkdownTableProperty(newTableProp);
      setCustomProperty((prev) => ({
        ...prev,
        handleMarkdownTable: true,
        handleList: false,
      }));
      MarkdownTableProcessor.handleMarkdownTable(
        textPair.raw,
        customProperty,
        newTableProp,
        setTextPair,
      );
    },
    [markdownTableProperty, customProperty, textPair.raw],
  );

  /**
   * 处理文本输入变化
   */
  const handleTextChange = useCallback((value: string) => {
    const {
      customProperty: prop,
      listProperty: listProp,
      markdownTableProperty: tableProp,
    } = configRef.current;

    if (prop.handleList) {
      ListProcessor.handleCompleteListText(value, prop, listProp, setTextPair);
    } else if (prop.handleMarkdownTable) {
      MarkdownTableProcessor.handleMarkdownTable(
        value,
        prop,
        tableProp,
        setTextPair,
      );
    } else {
      handleText(value, prop, setTextPair);
    }
  }, []);

  /**
   * 窗口焦点处理
   */
  const handleWindowFocus = useCallback(async () => {
    const {
      customProperty: prop,
      listProperty: listProp,
      markdownTableProperty: tableProp,
    } = configRef.current;

    if (prop.pasteFromClipboard) {
      console.log('开始处理剪贴板...')
      await ClipboardHandler.handleClipboard(
        prop,
        listProp,
        tableProp,
        setTextPair,
      );
    }
  }, []);

  return {
    textPair,
    customProperty,
    listProperty,
    markdownTableProperty,
    updateCustomProperty,
    updateListProperty,
    updateMarkdownTableProperty,
    handleTextChange,
    handleWindowFocus,
  };
};
