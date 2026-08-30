import {i18nText} from '@/utils/i18n';
import {Button, Input, Modal, Typography} from "antd";
import mermaid from "mermaid";
import {useEffect, useRef, useState} from "react";

const {Text} = Typography;

// 全局只初始化一次 mermaid
let mermaidInitialized = false;

function ensureMermaidInit() {
  if (!mermaidInitialized) {
    mermaid.initialize({
      startOnLoad: false,
      // 使用 loose 安全级别，避免某些图表被过滤
      securityLevel: "loose",
      theme: "default",
    });
    mermaidInitialized = true;
  }
}

export function MermaidInputDialog({
                                     value,
                                     isOpen,
                                     onOpenChange,
                                     onInsert,
                                   }: {
  value?: string;
  isOpen: boolean;
  onOpenChange: (open: boolean) => void;
  onInsert?: (code: string) => void;
}) {
  const [code, setCode] = useState(value ?? "");
  const [error, setError] = useState<string>();
  const [loading, setLoading] = useState(false);
  // 防止 parse 竞态
  const parseAbortRef = useRef<boolean>(false);

  const handleSubmit = async () => {
    setError(undefined);
    setLoading(true);
    parseAbortRef.current = false;
    ensureMermaidInit();

    try {
      // 使用 mermaid.parse 第二个参数传 { suppressErrors: false }
      await mermaid.parse(code, {suppressErrors: false});

      if (!parseAbortRef.current) {
        onInsert?.(code);
      }
    } catch (err: any) {
      if (!parseAbortRef.current) {
        const message =
          err?.message ?? err?.str ?? i18nText("app.article.mermaid.mermaidinputdialog.6f7612ef");
        setError(message);
      }
    } finally {
      setLoading(false);
    }
  };

  const handleCancel = () => {
    onOpenChange(false);
  };

  useEffect(() => {
    ensureMermaidInit();
  }, []);

  useEffect(() => {
    if (isOpen) {
      setCode(value ?? "");
      setError(undefined);
    } else {
      // 关闭时取消进行中的 parse
      parseAbortRef.current = true;
    }
  }, [isOpen, value]);

  return (
    <Modal
      open={isOpen}
      title={i18nText("app.article.mermaid.mermaidinputdialog.f9aecd74")}
      width="55%"
      mask={{closable: false}} // 点击遮罩层不关闭，对应 disablePointerDismissal
      onCancel={handleCancel}
      closable={false}
      destroyOnHidden
      footer={
        <div style={{display: "flex", justifyContent: "flex-end", gap: 8}}>
          <Button onClick={handleCancel}>{i18nText("app.article.mermaid.mermaidinputdialog.cf744d77")}</Button>
          <Button
            type="primary"
            disabled={code.trim().length === 0}
            loading={loading}
            onClick={handleSubmit}
          >
            {value ? i18nText("app.article.mermaid.mermaidinputdialog.3948bca5") : i18nText("app.article.mermaid.mermaidinputdialog.12bb7e7c")}
          </Button>
        </div>
      }
    >
      <Input.TextArea
        id="mermaid-code"
        className="overflow-auto scrollbar-none"
        autoSize={{minRows: 15, maxRows: 25}}
        value={code}
        onChange={(e) => {
          setCode(e.target.value);
          // 输入时清除旧错误
          if (error) setError(undefined);
        }}
        placeholder={`graph TD\n    A[Start] --> B{Is it?}\n    B -- Yes --> C[OK]\n    B -- No --> D[End]`}
      />
      {error && (
        <Text
          type="danger"
          style={{
            marginTop: 6,
            fontSize: 12,
            whiteSpace: "pre-wrap",
            wordBreak: "break-all",
          }}
        >
          {error}
        </Text>
      )}
    </Modal>
  );
}
