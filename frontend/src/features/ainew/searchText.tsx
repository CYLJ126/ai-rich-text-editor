import React from 'react';
import type {ConversationSearchSnippet} from '@/services/arte-ai/conversation-search-contract';

const segmenter = new Intl.Segmenter(undefined, {granularity: 'grapheme'});
const invisible = /^[\p{White_Space}\p{Cf}\p{Cc}\p{M}\p{Cs}]*$/u;

export function searchQuery(input: string) {
  const query = input.replace(/^[\p{White_Space}]+|[\p{White_Space}]+$/gu, '');
  const length = [...segmenter.segment(query)].filter(value => !invisible.test(value.segment)).length;
  return {query, empty: length === 0, valid: length > 0 && length <= 128 && input.length <= 8192};
}

/** 在字形边界截取，并将服务端 UTF-16 区间映射到片段；React 文本节点负责转义。 */
export function searchExcerpt(snippet: ConversationSearchSnippet, max = 180): ConversationSearchSnippet {
  const parts = [...segmenter.segment(snippet.text)];
  if (parts.length <= max) return snippet;
  const first = snippet.highlights.find(value => value.start >= 0 && value.end > value.start && value.end <= snippet.text.length);
  const at = first ? Math.max(0, parts.findIndex(value => value.index + value.segment.length > first.start)) : 0;
  const from = Math.min(Math.max(0, at - 40), parts.length - max);
  const start = parts[from].index, end = parts[from + max]?.index ?? snippet.text.length;
  const prefix = start ? '…' : '';
  return {
    text: prefix + snippet.text.slice(start, end) + (end < snippet.text.length ? '…' : ''),
    highlights: snippet.highlights.filter(value => value.end > start && value.start < end).map(value => ({
      start: Math.max(start, value.start) - start + prefix.length,
      end: Math.min(end, value.end) - start + prefix.length,
    })),
  };
}

export function SearchHighlight({snippet}: { snippet: ConversationSearchSnippet }) {
  const ranges = snippet.highlights.filter(value => Number.isInteger(value.start) && Number.isInteger(value.end)
    && value.start >= 0 && value.end > value.start && value.end <= snippet.text.length).sort((a, b) => a.start - b.start);
  const merged: { start: number; end: number }[] = [];
  for (const value of ranges) {
    const last = merged.at(-1);
    if (last && value.start <= last.end) last.end = Math.max(last.end, value.end);
    else merged.push({...value});
  }
  const nodes: React.ReactNode[] = [];
  let offset = 0;
  for (const range of merged) {
    nodes.push(snippet.text.slice(offset, range.start));
    nodes.push(<mark key={range.start}>{snippet.text.slice(range.start, range.end)}</mark>);
    offset = range.end;
  }
  nodes.push(snippet.text.slice(offset));
  return <>{nodes}</>;
}
