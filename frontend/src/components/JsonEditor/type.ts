import Editor, {OnMount} from '@monaco-editor/react';
import React from 'react';

export type IStandaloneCodeEditor = Parameters<OnMount>[0];
export type IStandaloneEditorConstructionOptions = NonNullable<React.ComponentProps<typeof Editor>['options']>;

export interface JsonEditorRef {
  getRawValue: () => string;
  getParsedValue: () => Record<string, unknown> | unknown[] | null;
  format: () => void;
  focus: () => void;
  /** 原始 monaco editor 实例，可调用所有 monaco editor API */
  editor: IStandaloneCodeEditor | null;
}

export interface JsonEditorProps {
  value?: unknown;
  defaultValue?: unknown;
  onChange?: (
    parsed: Record<string, unknown> | unknown[] | null,
    raw: string,
  ) => void;
  onBlur?: (
    parsed: Record<string, unknown> | unknown[] | null,
    raw: string,
  ) => void;
  readOnly?: boolean;
  placeholder?: unknown;
  height?: number | string;
  className?: string;
  style?: React.CSSProperties;
  showToolbar?: boolean;
  showError?: boolean;
  editorOptions?: IStandaloneEditorConstructionOptions;
  onMount?: OnMount;
  onValidate?: (valid: boolean, error: string | null) => void;
}
