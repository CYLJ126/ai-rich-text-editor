import {describe, expect, it} from 'vitest';
import {buildCanvasPrompt, CANVAS_CONTEXT_MARKER, parseCanvasResponse} from './canvas-ai';
import enUS from '@/locales/en-US/app';
import zhCN from '@/locales/zh-CN/app';
import zhTW from '@/locales/zh-TW/app';

const xml = '<mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/><mxCell id="2" parent="1" vertex="1"><mxGeometry x="0" y="0" width="120" height="60" as="geometry"/></mxCell></root></mxGraphModel>';
const json = JSON.stringify({nodeData: {id: 'root', topic: 'Launch', children: [{id: 'test', topic: 'Testing'}]}});

describe('canvas AI results and context', () => {
  it('extracts editable Draw.io XML while retaining the explanation', () => {
    expect(parseCanvasResponse('drawio', `Added a branch.\n\n\`\`\`xml\n${xml}\n\`\`\``))
      .toEqual({source: xml, summary: 'Added a branch.'});
  });
  it('accepts Mind Elixir data without a fence', () => {
    expect(parseCanvasResponse('mindmap', json).source).toBe(json);
  });
  it('rejects duplicate IDs throughout a mind map', () => {
    const invalid = JSON.stringify({nodeData: {id: 'root', topic: 'Plan', children: [{id: 'root', topic: 'Test'}]}});
    expect(() => parseCanvasResponse('mindmap', invalid)).toThrow();
  });
  it.each([
    xml.replace('parent="1"', 'parent="missing"'),
    xml.replace('id="2"', 'id="1"'),
    '<svg><root/></svg>',
  ])('rejects unusable Draw.io structures', value => {
    expect(() => parseCanvasResponse('drawio', value)).toThrow();
  });
  it('supplies the selected source for modification and hides context behind a marker', () => {
    const prompt = buildCanvasPrompt('drawio', 'Add a step', xml, 'modify', 'en-US');
    expect(prompt.split(CANVAS_CONTEXT_MARKER)[0].trim()).toBe('Add a step');
    expect(prompt).toContain(xml);
    expect(prompt).toContain('source is authoritative');
  });
  it('omits prior source when creating from scratch', () => {
    const prompt = buildCanvasPrompt('mindmap', 'New launch', json, 'create', 'zh-CN');
    expect(prompt).not.toContain(json);
    expect(prompt).toContain('Create a NEW diagram');
  });
  it('provides the same workspace messages in every supported locale', () => {
    const keys = (messages: object) => Object.keys(messages).filter(key => key.startsWith('app.article.canvas.ai.')).sort();
    expect(keys(enUS)).toEqual(keys(zhCN));
    expect(keys(zhTW)).toEqual(keys(zhCN));
  });
});
