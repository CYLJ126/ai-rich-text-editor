import {NodeViewProps} from "@tiptap/core";
import {NodeViewWrapper} from "@tiptap/react";
import {useCallback, useEffect, useRef, useState} from "react";
import {Button, Flex, Input, message as antdMessage} from "antd";
import {TextAreaRef} from "antd/lib/input/TextArea";
import {STREAM_COMPLETION_URL, streamChat} from "@/services/ant-design-pro/ai.chat";
import {extractDocText} from "@/components/Article/extension/ai-completion/context-builder";
import {buildContinuationContext} from '@/utils/ai';
import Markdown from "react-markdown";

const AiWriterView = ({editor, node, getPos}: NodeViewProps) => {
  const inputRef = useRef<TextAreaRef>(null);
  const [prompt, setPrompt] = useState("");
  const [generatedContent, setGeneratedContent] = useState("");
  const [showOutput, setShowOutput] = useState(false);
  const [generateDisabled, setGenerateDisabled] = useState(false);
  const [insertDisabled, setInsertDisabled] = useState(true);

  const insert = useCallback(() => {
      if (!generatedContent) return;
      const from = getPos();
      if (from === undefined) return;
      const to = from + node.nodeSize;
      editor.chain().focus().insertContentAt({from, to}, generatedContent, {
        contentType: "markdown",
      }).run();
    }, [generatedContent, getPos, node, editor]);

  const cancel = useCallback(() => {
    const from = getPos();
    if (from === undefined) return;
    const to = from + node.nodeSize;
    editor.chain().focus().deleteRange({from, to}).run();
  }, [editor]);

  const generate = useCallback(async () => {
    const modelId = editor.aiModel?.id;
    const articleId = editor.articleInfo?.id;
    const characterCountCeil = editor.aiModel?.continuationCharacterCountCeil;

    const {state} = editor;
    const {selection, doc} = state;
    const {anchor} = selection;
    // 按写作管理配置截取光标前后文，并标出续写位置
    const { fullText, cursorOffset }  = extractDocText(doc, anchor);
    const originalText = buildContinuationContext(
      fullText,
      cursorOffset,
      editor.aiModel ?? undefined,
    );

    setGenerateDisabled(true)
    setGeneratedContent("");
    setShowOutput(true);
    setInsertDisabled(true);

    await streamChat(
      STREAM_COMPLETION_URL,
      {
        modelId,
        originalText,
        content: prompt,
        characterCountCeil,
        generateType: 'continuation',
        scene: 'writing_prompt',
        chatRagRequest: {
          knowledgeBaseType: 'article',
          articleIds: articleId ? [articleId] : [],
        },
      },
      {
        onContent: (delta) => {
          setGeneratedContent(prev => prev + delta);
        },
        onError: (err) => {
          antdMessage.error('AI 续写失败：' + err?.message).then();
        },
        onDone: () => {
          setGenerateDisabled(false);
          setInsertDisabled(false);
        },
      }
    )
  }, [editor, prompt, setGeneratedContent, setShowOutput, setInsertDisabled])

  useEffect(() => {
    const id = window.requestAnimationFrame(() => {
      inputRef.current?.focus();
    });
    return () => {
      window.cancelAnimationFrame(id);
    };
  }, []);

  return (
    <NodeViewWrapper>
      <div className="flex flex-col gap-2 p-2 border border-[var(--ant-color-border-secondary)] rounded-md shadow-md">
        {
          showOutput && (
            <Markdown>{generatedContent}</Markdown>
          )
        }
        <Input.TextArea
          ref={inputRef}
          value={prompt}
          onChange={(e) => setPrompt(e.target.value)}
          placeholder="请输入你的提示内容"
          autoSize={{minRows: 3, maxRows: 15}}
          autoFocus
        />
        <Flex justify="flex-end" gap="small">
          <Button disabled={generateDisabled} onClick={generate}>生成</Button>
          <Button onClick={cancel}>取消</Button>
          <Button type="primary" disabled={insertDisabled} onClick={insert}>插入</Button>
        </Flex>
      </div>
    </NodeViewWrapper>
  )
}

export default AiWriterView;
