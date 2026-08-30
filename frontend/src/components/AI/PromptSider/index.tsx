import React from 'react';
import {PromptConfig} from "@/types/ai.type";

export type PromptSiderProps = {
  height?: number;
  onSelect?: (prompt: PromptConfig) => void;
}

const PromptSider: React.FC<PromptSiderProps> = ({ height, onSelect }) => {
  return (
    <div style={{ height }}>
      <div>提示词侧边栏</div>
    </div>
  );
}

export default PromptSider;
