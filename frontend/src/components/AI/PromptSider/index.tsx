import {i18nText} from '@/utils/i18n';
import React from 'react';
import {PromptConfig} from "@/types/ai.type";

export type PromptSiderProps = {
  height?: number;
  onSelect?: (prompt: PromptConfig) => void;
}

const PromptSider: React.FC<PromptSiderProps> = ({ height, onSelect }) => {
  return (
    <div style={{ height }}>
      <div>{i18nText("app.ai.promptsider.d9ac182e")}</div>
    </div>
  );
}

export default PromptSider;
