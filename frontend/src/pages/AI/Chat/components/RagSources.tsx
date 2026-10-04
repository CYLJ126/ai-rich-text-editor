import {Typography} from 'antd';
import type {RagFragment} from '@/types/ai-new/chat';
import {i18nText as t} from '@/utils/i18n';

/** Plain text only: stored article content cannot inject markup into the consent dialog. */
export default function RagSources({fragments}: {fragments: RagFragment[]}) {
  if (!fragments.length) return null;
  return <details style={{marginBlock: 12}}>
    <summary>{t('app.aiNew.rag.sources', {count: fragments.length})}</summary>
    {fragments.map(fragment => <section key={fragment.citationId} style={{padding: '8px 0'}}>
      <Typography.Text strong>[{fragment.citationId}] #{fragment.source.resource.resourceId} · v{fragment.source.resource.version}</Typography.Text>
      <div>{fragment.coverageDescription}</div>
      <div style={{whiteSpace: 'pre-wrap', overflowWrap: 'anywhere', maxHeight: 240, overflow: 'auto'}}>{fragment.content}</div>
    </section>)}
  </details>;
}
