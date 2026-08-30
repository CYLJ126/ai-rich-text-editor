import {Button} from "@/components/ui/button";
import {Alert} from "@/components/ui/alert";
import {cn} from "@/lib/utils";
import {NodeViewContent, NodeViewWrapper, type ReactNodeViewProps} from "@tiptap/react";
import {EditIcon, TrashIcon} from "lucide-react";
import mermaid from "mermaid";
import {useCallback, useEffect, useRef, useState} from "react";
import {MermaidInputDialog} from "./mermaid-input-dialog";
import {useEditorStore} from "@/components";

/**
 * 从 TipTap Node 中正确提取带换行的文本
 * hardBreak 节点需要转换为 \n
 */
function extractCodeFromNode(node: any): string {
  const lines: string[] = [];
  let currentLine = "";

  node.forEach((child: any) => {
    if (child.type.name === "hardBreak") {
      lines.push(currentLine);
      currentLine = "";
    } else {
      currentLine += child.text ?? "";
    }
  });

  // 推入最后一行
  lines.push(currentLine);

  return lines.join("\n");
}

// ─── Mermaid 视图 ───
export function MermaidView({
                              editor,
                              getPos,
                              node,
                              HTMLAttributes,
                              extension,
                            }: ReactNodeViewProps) {
  const operationMode = useEditorStore(state => state.operationMode);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [openMermaidInputDialog, setOpenMermaidInputDialog] = useState(false);
  const [error, setError] = useState<string>();
  const {options} = extension;

  // 正确提取代码（包含换行符）
  const code = extractCodeFromNode(node);

  const deleteNode = useCallback(() => {
    const pos = getPos();
    if (pos === undefined) {
      return;
    }

    editor.chain().focus().command(({tr}) => {
        tr.delete(pos, pos + node.nodeSize);
        return true;
      })
      .run();
  }, [editor, getPos, node.nodeSize]);

  const renderDiagram = useCallback(async () => {
    // 重置错误状态
    setError(undefined);

    if (!code.trim()) {
      return;
    }

    try {
      // 使用提取出的、含换行的 code
      const id = "m-" + crypto.randomUUID();
      const result = await mermaid.render(id, code);
      if (containerRef.current) {
        containerRef.current.innerHTML = result.svg;
      }
    } catch (error: any) {
      console.error("Mermaid render error:", error);
      // 提取更友好的错误信息
      const message =
        error?.message ?? error?.str ?? "Unknown mermaid render error";
      setError(message);
    }
  }, [code]);

  useEffect(() => {
    renderDiagram().then();
  }, [renderDiagram]);

  return (
    <NodeViewWrapper>
      <NodeViewContent hidden />

      {error ? (
        <div className="w-full p-2">
          <Alert variant="destructive" className="mt-2">
            <p className="text-sm font-medium">Mermaid 渲染错误</p>
            <p className="text-xs mt-1 whitespace-pre-wrap break-all">
              {error}
            </p>
          </Alert>
        </div>
      ) : (
        <div
          ref={containerRef}
          className={cn(options.HTMLAttributes.class, HTMLAttributes.class)}
        />
      )}

      <div className={`absolute flex space-x-1 top-2 right-2 ${operationMode === "edit" ? "" : "hidden"}`}>
        <Button
          variant="secondary"
          size="icon"
          className="opacity-40 hover:opacity-100 size-7 cursor-pointer"
          onClick={() => setOpenMermaidInputDialog(true)}
        >
          <EditIcon className="text-[var(--ant-color-text-tertiary)] dark:text-[var(--ant-color-bg-spotlight)]" strokeWidth={3} />
        </Button>
        <Button
          variant="destructive"
          size="icon"
          className="opacity-40 hover:opacity-100 size-7 cursor-pointer"
          onClick={deleteNode}
        >
          <TrashIcon className="text-[var(--ant-color-text-tertiary)] dark:text-[var(--ant-color-bg-spotlight)]" strokeWidth={3} />
        </Button>
      </div>

      <MermaidInputDialog
        value={code}
        isOpen={openMermaidInputDialog}
        onOpenChange={setOpenMermaidInputDialog}
        onInsert={(newCode) => {
          const pos = getPos();
          if (pos === undefined) {
            return;
          }
          editor
            .chain()
            .focus()
            .setNodeSelection(pos)
            .updateMermaid({code: newCode})
            .run();
          setOpenMermaidInputDialog(false);
        }}
      />
    </NodeViewWrapper>
  );
}
