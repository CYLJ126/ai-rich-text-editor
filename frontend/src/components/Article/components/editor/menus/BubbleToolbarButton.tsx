import {type Editor, isTextSelection} from '@tiptap/core';
import {PluginKey} from '@tiptap/pm/state';
import {BubbleMenu} from '@tiptap/react/menus';
import React from 'react';
import {ToolbarButtonGroup} from '@/components/Article/components';
import {ToolbarButtonItem} from "@/types/rt.editor.type";

export const BubbleToolbarButton = ({
  editButtons,
  editor,
  allowReadOnly = false,
}: {
  editButtons: ToolbarButtonItem[];
  editor: Editor;
  allowReadOnly?: boolean;
}) => {
  const pluginKey = new PluginKey('bubble-toolbar-button');

  if (!editButtons || !editor) {
    return null;
  }

  return (
    <BubbleMenu
      editor={editor || (null as unknown as Editor)}
      className='z-999'
      appendTo={() => document.body}
      options={{
        placement: 'top',
        offset: 8,
      }}
      shouldShow={({ editor, state }) => {
        const { selection } = state;
        const { empty } = selection;

        if (!editor.isEditable && !allowReadOnly) {
          return false;
        }

        if (empty) {
          return false;
        }

        if (!isTextSelection(selection)) {
          return false;
        }

        return !editor.isActive('codeBlock');
      }}
      pluginKey={pluginKey}
    >
      <ToolbarButtonGroup buttons={editButtons} justify="flex-start" buttonBarType="float"/>
    </BubbleMenu>
  );
};
