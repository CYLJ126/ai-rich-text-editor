import {i18nText} from '@/utils/i18n';
import {
  CloseOutlined,
  DownOutlined,
  SearchOutlined,
  SwapOutlined,
  UpOutlined,
} from '@ant-design/icons';
import type { Editor } from '@tiptap/core';
import type { Transaction } from '@tiptap/pm/state';
import { Button, Input, Tooltip } from 'antd';
import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  type SearchMatch,
  searchHighlightPluginKey,
} from '@/components/Article/extension/SearchHighlight';
import styles from './SearchReplaceBar.less';

interface SearchReplaceBarProps {
  editor: Editor;
  enabled: boolean;
  canReplace: boolean;
}

interface SearchMatchDetails extends SearchMatch {
  text: string;
  captures: Array<string | undefined>;
  groups?: Record<string, string | undefined>;
  input: string;
  index: number;
}

interface SearchResult {
  matches: SearchMatchDetails[];
  error?: string;
}

const escapeRegExp = (value: string) =>
  value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

const createSearchRegExp = (
  query: string,
  regexEnabled: boolean,
  caseSensitive: boolean,
) =>
  new RegExp(
    regexEnabled ? query : escapeRegExp(query),
    ['g', 'u', caseSensitive ? '' : 'i'].join(''),
  );

const findMatches = (
  editor: Editor,
  query: string,
  regexEnabled: boolean,
  caseSensitive: boolean,
): SearchResult => {
  if (!query) return { matches: [] };

  let expression: RegExp;
  try {
    expression = createSearchRegExp(query, regexEnabled, caseSensitive);
  } catch (error) {
    return {
      matches: [],
      error: error instanceof Error ? error.message : i18nText("app.article.editor.searchreplacebar.0b12ff3e"),
    };
  }

  const matches: SearchMatchDetails[] = [];

  editor.state.doc.descendants((node, position) => {
    if (!node.isTextblock) return true;

    let blockText = '';
    const positions: number[] = [];
    node.descendants((child, relativePosition) => {
      if (!child.isText || !child.text) return;
      blockText += child.text;
      for (let index = 0; index < child.text.length; index += 1) {
        positions.push(position + 1 + relativePosition + index);
      }
    });

    expression.lastIndex = 0;
    for (const match of blockText.matchAll(expression)) {
      // 零宽匹配无法用内联 Decoration 呈现，也容易产生歧义，暂不计入结果。
      if (!match[0].length) continue;
      const index = match.index;
      const from = positions[index] ?? position + 1 + node.content.size;
      const lastPosition = positions[index + match[0].length - 1];
      const to = (lastPosition ?? from - 1) + 1;

      matches.push({
        from,
        to,
        text: match[0],
        captures: match.slice(1),
        groups: match.groups,
        input: blockText,
        index,
      });
    }

    return false;
  });

  return { matches };
};

const decodeReplacementEscapes = (value: string) =>
  value.replace(/\\([\\nrt])/g, (_, escapeCode: string) => {
    if (escapeCode === 'n') return '\n';
    if (escapeCode === 'r') return '\r';
    if (escapeCode === 't') return '\t';
    return '\\';
  });

const expandRegexReplacement = (
  replacement: string,
  match: SearchMatchDetails,
) => {
  const decoded = decodeReplacementEscapes(replacement);
  return decoded.replace(
    /\$(\$|&|`|'|<[^>]+>|\d{1,2})/g,
    (token, reference: string) => {
      if (reference === '$') return '$';
      if (reference === '&') return match.text;
      if (reference === '`') return match.input.slice(0, match.index);
      if (reference === "'") {
        return match.input.slice(match.index + match.text.length);
      }
      if (reference.startsWith('<')) {
        if (!match.groups) return token;
        return match.groups[reference.slice(1, -1)] ?? '';
      }

      const captureIndex = Number(reference);
      if (captureIndex > 0 && captureIndex <= match.captures.length) {
        return match.captures[captureIndex - 1] ?? '';
      }
      if (reference.length === 2) {
        const firstCaptureIndex = Number(reference[0]);
        if (
          firstCaptureIndex > 0 &&
          firstCaptureIndex <= match.captures.length
        ) {
          return `${match.captures[firstCaptureIndex - 1] ?? ''}${reference[1]}`;
        }
      }
      return token;
    },
  );
};

const SearchReplaceBar: React.FC<SearchReplaceBarProps> = ({
  editor,
  enabled,
  canReplace,
}) => {
  const [open, setOpen] = useState(false);
  const [replaceVisible, setReplaceVisible] = useState(false);
  const [query, setQuery] = useState('');
  const [replacement, setReplacement] = useState('');
  const [caseSensitive, setCaseSensitive] = useState(false);
  const [regexEnabled, setRegexEnabled] = useState(false);
  const [activeIndex, setActiveIndex] = useState(0);
  const [documentVersion, setDocumentVersion] = useState(0);
  const searchInputRef = useRef<React.ComponentRef<typeof Input>>(null);
  const currentArticlePositionRef = useRef(editor.state.selection.from);
  const searchStartPositionRef = useRef(editor.state.selection.from);
  const resetToSearchStartRef = useRef(false);

  const searchResult = useMemo(
    () => findMatches(editor, query, regexEnabled, caseSensitive),
    [caseSensitive, editor, query, regexEnabled, documentVersion],
  );
  const { matches } = searchResult;

  const close = useCallback(() => {
    setOpen(false);
    setReplaceVisible(false);
    editor.view.dispatch(
      editor.state.tr.setMeta(searchHighlightPluginKey, {
        matches: [],
        activeIndex: -1,
      }),
    );
  }, [editor]);

  const focusSearch = useCallback(
    (showReplace: boolean) => {
      searchStartPositionRef.current = currentArticlePositionRef.current;
      resetToSearchStartRef.current = true;
      setOpen(true);
      setReplaceVisible(showReplace);
      requestAnimationFrame(() => {
        searchInputRef.current?.focus({ cursor: 'all' });
      });
    },
    [editor],
  );

  useEffect(() => {
    if (!enabled) close();
  }, [close, enabled]);

  useEffect(() => {
    const handleUpdate = () => setDocumentVersion((value) => value + 1);
    editor.on('update', handleUpdate);
    return () => {
      editor.off('update', handleUpdate);
    };
  }, [editor]);

  useEffect(() => {
    const handlePointerDown = (event: PointerEvent) => {
      const position = editor.view.posAtCoords({
        left: event.clientX,
        top: event.clientY,
      });
      if (position) currentArticlePositionRef.current = position.pos;
    };
    const handleSelectionUpdate = () => {
      currentArticlePositionRef.current = editor.state.selection.from;
    };

    editor.view.dom.addEventListener('pointerdown', handlePointerDown);
    editor.on('selectionUpdate', handleSelectionUpdate);
    return () => {
      editor.view.dom.removeEventListener('pointerdown', handlePointerDown);
      editor.off('selectionUpdate', handleSelectionUpdate);
    };
  }, [editor]);

  useEffect(() => {
    const handleShortcut = (event: KeyboardEvent) => {
      if (
        open &&
        event.key === 'Escape' &&
        editor.view.dom.contains(event.target as Node)
      ) {
        event.preventDefault();
        event.stopPropagation();
        close();
        return;
      }

      if (
        !enabled ||
        editor.view.dom.offsetParent === null ||
        !(event.ctrlKey || event.metaKey) ||
        event.shiftKey ||
        event.altKey
      ) {
        return;
      }

      const key = event.key.toLocaleLowerCase();
      if (key === 'f' || key === 'r') {
        event.preventDefault();
        event.stopPropagation();
        focusSearch(key === 'r');
      }
    };

    window.addEventListener('keydown', handleShortcut, true);
    return () => window.removeEventListener('keydown', handleShortcut, true);
  }, [close, editor, enabled, focusSearch, open]);

  useEffect(() => {
    if (!open) {
      editor.view.dispatch(
        editor.state.tr.setMeta(searchHighlightPluginKey, {
          matches: [],
          activeIndex: -1,
        }),
      );
      return;
    }

    const nextIndex = matches.length
      ? resetToSearchStartRef.current
        ? Math.max(
            0,
            matches.findIndex(
              (match) => match.from >= searchStartPositionRef.current,
            ),
          )
        : Math.min(activeIndex, matches.length - 1)
      : 0;
    resetToSearchStartRef.current = false;
    if (nextIndex !== activeIndex) setActiveIndex(nextIndex);

    editor.view.dispatch(
      editor.state.tr.setMeta(searchHighlightPluginKey, {
        matches,
        activeIndex: nextIndex,
      }),
    );

    if (matches.length) {
      requestAnimationFrame(() => {
        editor.view.dom
          .querySelector('.search-match-current')
          ?.scrollIntoView({ behavior: 'smooth', block: 'center' });
      });
    }
  }, [activeIndex, editor, matches, open]);

  const goToNext = () => {
    if (!matches.length) return;
    setActiveIndex((activeIndex + 1) % matches.length);
  };

  const goToPrevious = () => {
    if (!matches.length) return;
    setActiveIndex((activeIndex - 1 + matches.length) % matches.length);
  };

  const replaceCurrent = () => {
    if (!canReplace || !query || !matches.length) return;

    const match = matches[activeIndex];
    if (!match) return;

    const replacementText = regexEnabled
      ? expandRegexReplacement(replacement, match)
      : replacement;
    editor.view.dispatch(replaceRange(editor.state.tr, match, replacementText));
    setActiveIndex((index) => Math.max(0, Math.min(index, matches.length - 2)));
    editor.view.focus();
  };

  const replaceAll = () => {
    if (!canReplace || !query || !matches.length) return;

    let transaction = editor.state.tr;
    [...matches].reverse().forEach((match) => {
      const replacementText = regexEnabled
        ? expandRegexReplacement(replacement, match)
        : replacement;
      transaction = replaceRange(transaction, match, replacementText);
    });
    editor.view.dispatch(transaction);
    editor.view.focus();
  };

  if (!enabled || !open) return null;

  function replaceRange(
    transaction: Transaction,
    match: SearchMatch,
    value: string,
  ) {
    const normalizedValue = value.replace(/\r\n?|\n/g, '\n');
    if (!normalizedValue.includes('\n') || !editor.schema.nodes.hardBreak) {
      return transaction.insertText(normalizedValue, match.from, match.to);
    }

    const marks = editor.state.doc.resolve(match.from).marks();
    const lines = normalizedValue.split('\n');
    const nodes = lines.flatMap((line, index) => {
      const content = line ? [editor.schema.text(line, marks)] : [];
      return index < lines.length - 1
        ? [...content, editor.schema.nodes.hardBreak.create()]
        : content;
    });
    return transaction.replaceWith(match.from, match.to, nodes);
  }

  return (
    <div className={styles.searchReplaceBar}>
      <div className={styles.searchRow}>
        <SearchOutlined className={styles.leadingIcon} />
        <Input
          ref={searchInputRef}
          variant="borderless"
          status={searchResult.error ? 'error' : undefined}
          value={query}
          placeholder={i18nText("app.article.editor.searchreplacebar.3fcdb272")}
          onChange={(event) => {
            setQuery(event.target.value);
            resetToSearchStartRef.current = true;
          }}
          onPressEnter={(event) => {
            event.preventDefault();
            if (event.shiftKey) {
              goToPrevious();
            } else {
              goToNext();
            }
          }}
          onKeyDown={(event) => {
            if (event.key === 'Escape') close();
          }}
        />
        <Tooltip title={i18nText("app.article.editor.searchreplacebar.f9152b29")}>
          <Button
            type={caseSensitive ? 'primary' : 'text'}
            size="small"
            className={styles.optionButton}
            onClick={() => {
              setCaseSensitive((enabled) => !enabled);
              resetToSearchStartRef.current = true;
            }}
            aria-pressed={caseSensitive}
          >
            Aa
          </Button>
        </Tooltip>
        <Tooltip
          title={
            searchResult.error
              ? i18nText("app.article.editor.searchreplacebar.c6d80116", {value0: searchResult.error})
              : i18nText("app.article.editor.searchreplacebar.8f15a37c")
          }
        >
          <Button
            type={regexEnabled ? 'primary' : 'text'}
            danger={Boolean(searchResult.error)}
            size="small"
            className={styles.optionButton}
            onClick={() => {
              setRegexEnabled((enabled) => !enabled);
              resetToSearchStartRef.current = true;
            }}
            aria-pressed={regexEnabled}
          >
            .*
          </Button>
        </Tooltip>
        <span className={styles.matchCount}>
          {searchResult.error
            ? i18nText("app.article.editor.searchreplacebar.aedca075")
            : query
              ? `${matches.length ? activeIndex + 1 : 0}/${matches.length}`
              : ''}
        </span>
        <Tooltip title={i18nText("app.article.editor.searchreplacebar.6168b3d3")}>
          <Button
            type="text"
            size="small"
            icon={<UpOutlined />}
            disabled={!matches.length}
            onClick={goToPrevious}
            aria-label={i18nText("app.article.editor.searchreplacebar.8e4ba8aa")}
          />
        </Tooltip>
        <Tooltip title={i18nText("app.article.editor.searchreplacebar.2d287c9e")}>
          <Button
            type="text"
            size="small"
            icon={<DownOutlined />}
            disabled={!matches.length}
            onClick={goToNext}
            aria-label={i18nText("app.article.editor.searchreplacebar.3cb938b1")}
          />
        </Tooltip>
        <Tooltip title={replaceVisible ? i18nText("app.article.editor.searchreplacebar.cda6279d") : i18nText("app.article.editor.searchreplacebar.4e0e5892")}>
          <Button
            type="text"
            size="small"
            icon={<SwapOutlined />}
            onClick={() => setReplaceVisible((visible) => !visible)}
            aria-label={i18nText("app.article.editor.searchreplacebar.09736f6a")}
          />
        </Tooltip>
        <Button
          type="text"
          size="small"
          icon={<CloseOutlined />}
          onClick={close}
          aria-label={i18nText("app.article.editor.searchreplacebar.63f331de")}
        />
      </div>
      {replaceVisible && (
        <div className={styles.replaceRow}>
          <SwapOutlined className={styles.leadingIcon} />
          <Input
            variant="borderless"
            value={replacement}
            placeholder={canReplace ? i18nText("app.article.editor.searchreplacebar.7effc9be") : i18nText("app.article.editor.searchreplacebar.6429e3da")}
            disabled={!canReplace}
            onChange={(event) => setReplacement(event.target.value)}
            onPressEnter={replaceCurrent}
            onKeyDown={(event) => {
              if (event.key === 'Escape') close();
            }}
          />
          <Button
            size="small"
            disabled={!canReplace || !query || !matches.length}
            onClick={replaceCurrent}
          >
            {i18nText("app.article.editor.searchreplacebar.53c5615e")}
          </Button>
          <Button
            type="primary"
            size="small"
            disabled={!canReplace || !query || !matches.length}
            onClick={replaceAll}
          >
            {i18nText("app.article.editor.searchreplacebar.5c99541d")}
          </Button>
        </div>
      )}
    </div>
  );
};

export default SearchReplaceBar;
