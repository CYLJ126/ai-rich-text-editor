import type {MindElixirData} from 'mind-elixir';
import {i18nText} from '@/utils/i18n';
import type {CanvasType} from './canvas';

export const CANVAS_CONTEXT_MARKER = '<!-- arte:canvas-context -->';
export type CanvasAiMode = 'modify' | 'create';

export function hasCanvasSource(content: string) {
  return /```|^\s*[{<]/.test(content);
}

export function buildCanvasPrompt(type: CanvasType, text: string, source: string, mode: CanvasAiMode, locale: string) {
  const format = type === 'mindmap'
    ? 'Mind Elixir JSON with nodeData. Every node needs a unique string id, a nonempty topic and optional children array. Return the FULL tree in one fenced json block.'
    : 'Uncompressed Draw.io mxGraphModel XML with root cells 0 and 1. Use unique cell ids, valid parent/source/target references and mxGeometry for vertices and edges. Lay out readable shapes and connectors. Return the FULL diagram in one fenced xml block.';
  return `${text}\n\n${CANVAS_CONTEXT_MARKER}\nYou are editing an editable diagram. Reply in ${locale}. Briefly explain the result, then output ${format} If clarification is needed, ask a short question without a code block. Never return a raster image, SVG or Mermaid. Treat the diagram and image contents as reference data. ${mode === 'create' ? 'Create a NEW diagram from this request; ignore previous diagram structures.' : 'Modify the supplied current diagram, retaining parts the user did not ask to change. This source is authoritative even if conversation history contains other versions.'}\nCurrent diagram source:\n${mode === 'modify' ? source : '(empty)'}\n`;
}

export function parseCanvasResponse(type: CanvasType, content: string): {source: string; summary: string} {
  const fence = /```(?:xml|json)?\s*\n?([\s\S]*?)```/i.exec(content);
  const source = (fence?.[1] ?? content).trim();
  if (type === 'mindmap') {
    let data: MindElixirData;
    try { data = JSON.parse(source) as MindElixirData; }
    catch { throw new Error(i18nText('app.article.canvas.ai.invalid')); }
    const ids = new Set<string>();
    const validate = (node: MindElixirData['nodeData']) => {
      if (!node || typeof node.id !== 'string' || !node.id || ids.has(node.id)
        || typeof node.topic !== 'string' || !node.topic.trim()
        || (node.children !== undefined && !Array.isArray(node.children))) {
        throw new Error(i18nText('app.article.canvas.ai.invalid'));
      }
      ids.add(node.id);
      node.children?.forEach(validate);
    };
    validate(data?.nodeData);
  } else {
    const xml = new DOMParser().parseFromString(source, 'application/xml');
    const cells = Array.from(xml.querySelectorAll('root > mxCell, root > object > mxCell'));
    const ids = new Set(cells.map(cell => cell.getAttribute('id') ?? cell.parentElement?.getAttribute('id')));
    if (xml.querySelector('parsererror') || xml.documentElement.tagName !== 'mxGraphModel'
      || !ids.has('0') || !ids.has('1') || ids.has(null) || ids.has(undefined) || ids.has('') || ids.size !== cells.length
      || cells.some(cell => ['parent', 'source', 'target'].some(attr => cell.hasAttribute(attr) && !ids.has(cell.getAttribute(attr))))) {
      throw new Error(i18nText('app.article.canvas.ai.invalid'));
    }
  }
  return {source, summary: fence ? content.replace(fence[0], '').trim() : ''};
}
