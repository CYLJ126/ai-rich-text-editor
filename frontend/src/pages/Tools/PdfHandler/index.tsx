import {
  BookOutlined,
  ExportOutlined,
  FileImageOutlined,
  FilePdfOutlined,
  FileTextOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  PlayCircleOutlined,
  ScissorOutlined,
  UploadOutlined,
  ZoomInOutlined,
  ZoomOutOutlined,
} from '@ant-design/icons';
import type { UploadProps } from 'antd';
import {
  Alert,
  App,
  Button,
  Card,
  Checkbox,
  Empty,
  Form,
  Input,
  InputNumber,
  Select,
  Slider,
  Space,
  Spin,
  Splitter,
  Tooltip,
  Typography,
  Upload,
} from 'antd';
import React, { useEffect, useMemo, useRef, useState } from 'react';
import styles from './index.less';
import { formatPageExpression, parsePageExpression } from './pageSelection';
import {
  convertPdfToPictures,
  extractPdfPages,
  readPdfMetadata,
} from './service';
import type {
  PdfMetadata,
  PdfOperationResult,
  PdfOutlineEntry,
  PdfToolKey,
  SplitValues,
  ToPicValues,
} from './types';

const { Dragger } = Upload;
const { Paragraph, Text, Title } = Typography;

const TOOLS: Array<{
  key: PdfToolKey;
  label: string;
  icon: React.ReactNode;
  implemented: boolean;
}> = [
  {
    key: 'extract',
    label: 'Extract',
    icon: <ExportOutlined />,
    implemented: true,
  },
  {
    key: 'toPic',
    label: 'To Pic',
    icon: <FileImageOutlined />,
    implemented: true,
  },
  {
    key: 'toText',
    label: 'To Text',
    icon: <FileTextOutlined />,
    implemented: false,
  },
  {
    key: 'split',
    label: 'Split',
    icon: <ScissorOutlined />,
    implemented: true,
  },
];

const PdfHandler: React.FC = () => {
  const { message } = App.useApp();
  const [activeTool, setActiveTool] = useState<PdfToolKey>('toPic');
  const [file, setFile] = useState<File>();
  const [pdfUrl, setPdfUrl] = useState('');
  const [metadata, setMetadata] = useState<PdfMetadata>();
  const [selectedPages, setSelectedPages] = useState<number[]>([]);
  const [currentPage, setCurrentPage] = useState(1);
  const [zoom, setZoom] = useState(100);
  const [outlineVisible, setOutlineVisible] = useState(true);
  const [outlineMode, setOutlineMode] = useState<'pages' | 'bookmarks'>(
    'pages',
  );
  const [metadataLoading, setMetadataLoading] = useState(false);
  const [executing, setExecuting] = useState(false);
  const [pageInputError, setPageInputError] = useState('');
  const [operationResult, setOperationResult] = useState<PdfOperationResult>();
  const [toPicForm] = Form.useForm<ToPicValues>();
  const [splitForm] = Form.useForm<SplitValues>();
  const requestSequence = useRef(0);
  const exportAll = Form.useWatch('exportAll', toPicForm) ?? false;

  useEffect(
    () => () => {
      if (pdfUrl) URL.revokeObjectURL(pdfUrl);
    },
    [pdfUrl],
  );

  const pageExpression = useMemo(
    () => formatPageExpression(selectedPages),
    [selectedPages],
  );

  const selectFile = async (nextFile: File) => {
    const sequence = requestSequence.current + 1;
    requestSequence.current = sequence;
    setFile(nextFile);
    setPdfUrl(URL.createObjectURL(nextFile));
    setMetadata(undefined);
    setSelectedPages([]);
    setCurrentPage(1);
    setPageInputError('');
    setOperationResult(undefined);
    setMetadataLoading(true);
    try {
      const info = await readPdfMetadata(nextFile);
      if (requestSequence.current !== sequence) return;
      setMetadata(info);
      const baseName = nextFile.name.replace(/\.pdf$/i, '');
      toPicForm.setFieldsValue({ outputDir: info.defaultOutputDir });
      splitForm.setFieldsValue({
        pages: '',
        outputDir: info.defaultOutputDir,
        outputName: `${baseName}-extract`,
      });
    } catch (error) {
      if (requestSequence.current === sequence) {
        setFile(undefined);
        setPdfUrl('');
        message.error(error instanceof Error ? error.message : 'PDF 读取失败');
      }
    } finally {
      if (requestSequence.current === sequence) setMetadataLoading(false);
    }
  };

  const uploadProps: UploadProps = {
    accept: 'application/pdf,.pdf',
    maxCount: 1,
    showUploadList: false,
    beforeUpload: (nextFile) => {
      void selectFile(nextFile);
      return Upload.LIST_IGNORE;
    },
  };

  const updateSelectedPages = (pages: number[]) => {
    const normalized = [...new Set(pages)].sort((left, right) => left - right);
    setSelectedPages(normalized);
    splitForm.setFieldValue('pages', formatPageExpression(normalized));
    setPageInputError('');
  };

  const togglePage = (page: number, checked: boolean) => {
    updateSelectedPages(
      checked
        ? [...selectedPages, page]
        : selectedPages.filter((selected) => selected !== page),
    );
  };

  const handlePageInput = (value: string) => {
    splitForm.setFieldValue('pages', value);
    if (!metadata) return;
    try {
      updateSelectedPages(parsePageExpression(value, metadata.pageCount));
    } catch (error) {
      setPageInputError(
        error instanceof Error ? error.message : '页码格式错误',
      );
    }
  };

  const execute = async () => {
    if (!file || !metadata) {
      message.warning('请先选择 PDF 文件');
      return;
    }
    setExecuting(true);
    setOperationResult(undefined);
    try {
      let result: PdfOperationResult;
      if (activeTool === 'toPic') {
        const values = await toPicForm.validateFields();
        if (!values.exportAll && !selectedPages.length) {
          throw new Error('请在右侧页面大纲中选择要导出的页面');
        }
        result = await convertPdfToPictures(file, pageExpression, values);
      } else if (activeTool === 'split' || activeTool === 'extract') {
        const values = await splitForm.validateFields();
        const pages = parsePageExpression(values.pages, metadata.pageCount);
        if (!pages.length) throw new Error('请输入或选择要抽取的页面');
        result = await extractPdfPages(file, values);
      } else {
        throw new Error('To Text 将在后续版本中提供');
      }
      setOperationResult(result);
      message.success(`处理完成，已生成 ${result.files.length} 个文件`);
    } catch (error) {
      message.error(error instanceof Error ? error.message : 'PDF 处理失败');
    } finally {
      setExecuting(false);
    }
  };

  const currentTool = TOOLS.find((tool) => tool.key === activeTool) ?? TOOLS[0];
  const selectedSummary =
    exportAll && metadata
      ? `全部 ${metadata.pageCount} 页`
      : pageExpression
        ? `${pageExpression}（共 ${selectedPages.length} 页）`
        : '尚未选择页面';

  return (
    <div className={styles.page}>
      <Splitter className={styles.workspace}>
        <Splitter.Panel defaultSize="40%" min={340} max="70%">
          <section className={styles.leftPanel}>
            <div className={styles.fileArea}>
              <Spin spinning={metadataLoading}>
                <Dragger {...uploadProps}>
                  <p>
                    <UploadOutlined style={{ fontSize: 24 }} />
                  </p>
                  <p>{file ? file.name : '点击或拖入 PDF 文件'}</p>
                  <p className={styles.uploadHint}>
                    文件仅上传到本机后端进行处理
                  </p>
                </Dragger>
              </Spin>
            </div>

            <div className={styles.toolBar}>
              {TOOLS.map((tool) => (
                <Tooltip
                  key={tool.key}
                  title={tool.implemented ? undefined : '功能入口已预留'}
                >
                  <Button
                    className={styles.toolButton}
                    disabled={!tool.implemented}
                    icon={tool.icon}
                    type={activeTool === tool.key ? 'primary' : 'default'}
                    onClick={() => {
                      setActiveTool(tool.key);
                      setOperationResult(undefined);
                    }}
                  >
                    {tool.label}
                  </Button>
                </Tooltip>
              ))}
            </div>

            <div className={styles.configArea}>
              <Title level={4} className={styles.configTitle}>
                {currentTool.label} 配置
              </Title>
              {activeTool === 'toPic' ? (
                <ToPicConfig form={toPicForm} selection={selectedSummary} />
              ) : activeTool === 'split' || activeTool === 'extract' ? (
                <SplitConfig
                  form={splitForm}
                  error={pageInputError}
                  mode={activeTool}
                  onPageInput={handlePageInput}
                />
              ) : (
                <Empty description="To Text 配置入口已预留" />
              )}
              {operationResult ? (
                <OperationResult result={operationResult} />
              ) : null}
            </div>

            <div className={styles.executeArea}>
              <Button
                block
                disabled={!currentTool.implemented || !file || metadataLoading}
                icon={<PlayCircleOutlined />}
                loading={executing}
                size="large"
                type="primary"
                onClick={() => void execute()}
              >
                执行 {currentTool.label}
              </Button>
            </div>
          </section>
        </Splitter.Panel>

        <Splitter.Panel defaultSize="60%" min={420}>
          <PdfPreview
            currentPage={currentPage}
            fileName={file?.name}
            metadata={metadata}
            outlineMode={outlineMode}
            outlineVisible={outlineVisible}
            pdfUrl={pdfUrl}
            selectedPages={selectedPages}
            zoom={zoom}
            onCurrentPageChange={setCurrentPage}
            onOutlineModeChange={setOutlineMode}
            onOutlineVisibleChange={setOutlineVisible}
            onSelectedPagesChange={updateSelectedPages}
            onTogglePage={togglePage}
            onZoomChange={setZoom}
          />
        </Splitter.Panel>
      </Splitter>
    </div>
  );
};

interface ToPicConfigProps {
  form: ReturnType<typeof Form.useForm<ToPicValues>>[0];
  selection: string;
}

const ToPicConfig: React.FC<ToPicConfigProps> = ({ form, selection }) => (
  <Form<ToPicValues>
    form={form}
    initialValues={{
      format: 'PNG',
      quality: 92,
      dpi: 300,
      stitch: false,
      colorMode: 'COLOR',
      outputDir: '',
      exportAll: false,
    }}
    layout="vertical"
  >
    <Alert
      className={styles.selectionSummary}
      title={`已选择：${selection}`}
      showIcon
    />
    <Form.Item label="输出格式" name="format">
      <Select
        options={[
          { label: 'PNG', value: 'PNG' },
          { label: 'JPEG', value: 'JPEG' },
          { label: 'BMP', value: 'BMP' },
        ]}
      />
    </Form.Item>
    <Form.Item
      label="输出品质"
      name="quality"
      tooltip="JPEG 为画质；PNG/BMP 主要影响编码参数"
    >
      <Slider min={10} max={100} marks={{ 10: '10', 60: '60', 100: '100' }} />
    </Form.Item>
    <Form.Item
      label="渲染 DPI"
      name="dpi"
      tooltip="DPI 越高，图片越清晰且占用内存越多"
    >
      <InputNumber min={36} max={600} step={36} style={{ width: '100%' }} />
    </Form.Item>
    <Form.Item label="输出颜色" name="colorMode">
      <Select
        options={[
          { label: '彩色', value: 'COLOR' },
          { label: '灰度', value: 'GRAY' },
          { label: '黑白', value: 'BINARY' },
        ]}
      />
    </Form.Item>
    <Form.Item
      label="输出目录"
      name="outputDir"
      rules={[
        { required: true, whitespace: true, message: '请输入后端本机输出目录' },
      ]}
    >
      <Input placeholder="例如 ~/Downloads/arte-pdf" />
    </Form.Item>
    <Form.Item name="stitch" valuePropName="checked">
      <Checkbox>多页纵向拼接为一张图</Checkbox>
    </Form.Item>
    <Form.Item name="exportAll" valuePropName="checked">
      <Checkbox>导出全部页面</Checkbox>
    </Form.Item>
  </Form>
);

interface SplitConfigProps {
  form: ReturnType<typeof Form.useForm<SplitValues>>[0];
  error: string;
  mode: 'extract' | 'split';
  onPageInput: (value: string) => void;
}

const SplitConfig: React.FC<SplitConfigProps> = ({
  form,
  error,
  mode,
  onPageInput,
}) => (
  <Form<SplitValues>
    form={form}
    initialValues={{
      pages: '',
      outputDir: '',
      outputName: 'document-extract',
    }}
    layout="vertical"
  >
    <Alert
      className={styles.selectionSummary}
      title={
        mode === 'split'
          ? '选定页面将按当前顺序组成一个新 PDF'
          : '从原文档抽取页面组成新 PDF'
      }
      description="支持 1,3-7,20；-5 表示前 5 页；5- 表示第 5 页到最后一页。也可在右侧页面大纲中多选。"
      showIcon
      type="info"
    />
    <Form.Item
      help={error || '页码使用 1 起始编号'}
      label="页面范围"
      name="pages"
      required
      validateStatus={error ? 'error' : undefined}
    >
      <Input
        placeholder="1,3-7,20"
        onChange={(event) => onPageInput(event.target.value)}
      />
    </Form.Item>
    <Form.Item
      label="输出文件名"
      name="outputName"
      rules={[
        { required: true, whitespace: true, message: '请输入输出文件名' },
      ]}
    >
      <Input suffix=".pdf" />
    </Form.Item>
    <Form.Item
      label="输出目录"
      name="outputDir"
      rules={[
        { required: true, whitespace: true, message: '请输入后端本机输出目录' },
      ]}
    >
      <Input placeholder="例如 ~/Downloads/arte-pdf" />
    </Form.Item>
  </Form>
);

const OperationResult: React.FC<{ result: PdfOperationResult }> = ({
  result,
}) => (
  <Card className={styles.resultCard} size="small" title="处理结果">
    <Paragraph type="secondary">输出目录：{result.outputDir}</Paragraph>
    {result.files.map((path) => (
      <Paragraph className={styles.resultPath} copyable key={path}>
        {path}
      </Paragraph>
    ))}
  </Card>
);

interface PdfPreviewProps {
  currentPage: number;
  fileName?: string;
  metadata?: PdfMetadata;
  outlineMode: 'pages' | 'bookmarks';
  outlineVisible: boolean;
  pdfUrl: string;
  selectedPages: number[];
  zoom: number;
  onCurrentPageChange: (page: number) => void;
  onOutlineModeChange: (mode: 'pages' | 'bookmarks') => void;
  onOutlineVisibleChange: (visible: boolean) => void;
  onSelectedPagesChange: (pages: number[]) => void;
  onTogglePage: (page: number, checked: boolean) => void;
  onZoomChange: (zoom: number) => void;
}

const PdfPreview: React.FC<PdfPreviewProps> = (props) => {
  const {
    currentPage,
    fileName,
    metadata,
    outlineMode,
    outlineVisible,
    pdfUrl,
    selectedPages,
    zoom,
    onCurrentPageChange,
    onOutlineModeChange,
    onOutlineVisibleChange,
    onSelectedPagesChange,
    onTogglePage,
    onZoomChange,
  } = props;
  const pageCount = metadata?.pageCount ?? 0;
  const pages = useMemo(
    () => Array.from({ length: pageCount }, (_, index) => index + 1),
    []);
  const frameUrl = pdfUrl ? `${pdfUrl}#page=${currentPage}&zoom=${zoom}` : '';

  const goToPage = (page: number | null) => {
    if (page != null)
      onCurrentPageChange(Math.min(Math.max(page, 1), pageCount || 1));
  };

  return (
    <section className={styles.previewPanel}>
      <div className={styles.previewToolbar}>
        <div className={styles.previewToolbarGroup}>
          <Button
            aria-label={outlineVisible ? '隐藏页面大纲' : '显示页面大纲'}
            icon={
              outlineVisible ? <MenuFoldOutlined /> : <MenuUnfoldOutlined />
            }
            onClick={() => onOutlineVisibleChange(!outlineVisible)}
          />
          <FilePdfOutlined />
          <span className={styles.fileName}>{fileName || 'PDF 预览'}</span>
          {metadata ? <Text type="secondary">{pageCount} 页</Text> : null}
        </div>
        <div className={styles.previewToolbarGroup}>
          <Button
            disabled={currentPage <= 1}
            onClick={() => goToPage(currentPage - 1)}
          >
            上一页
          </Button>
          <InputNumber
            disabled={!metadata}
            min={1}
            max={pageCount || 1}
            value={currentPage}
            onChange={goToPage}
            style={{ width: 72 }}
          />
          <Text type="secondary">/ {pageCount || '-'}</Text>
          <Button
            disabled={!metadata || currentPage >= pageCount}
            onClick={() => goToPage(currentPage + 1)}
          >
            下一页
          </Button>
          <Button
            aria-label="缩小"
            disabled={!metadata || zoom <= 50}
            icon={<ZoomOutOutlined />}
            onClick={() => onZoomChange(Math.max(50, zoom - 10))}
          />
          <Text style={{ width: 44, textAlign: 'center' }}>{zoom}%</Text>
          <Button
            aria-label="放大"
            disabled={!metadata || zoom >= 200}
            icon={<ZoomInOutlined />}
            onClick={() => onZoomChange(Math.min(200, zoom + 10))}
          />
        </div>
      </div>

      <div className={styles.previewContent}>
        {outlineVisible ? (
          <aside className={styles.outline}>
            <div className={styles.outlineHeader}>
              <Space.Compact block>
                <Button
                  block
                  icon={<FilePdfOutlined />}
                  type={outlineMode === 'pages' ? 'primary' : 'default'}
                  onClick={() => onOutlineModeChange('pages')}
                >
                  页面
                </Button>
                <Button
                  block
                  icon={<BookOutlined />}
                  type={outlineMode === 'bookmarks' ? 'primary' : 'default'}
                  onClick={() => onOutlineModeChange('bookmarks')}
                >
                  书签
                </Button>
              </Space.Compact>
              {outlineMode === 'pages' && pageCount ? (
                <div className={styles.outlineActions}>
                  <Button
                    size="small"
                    onClick={() => onSelectedPagesChange(pages)}
                  >
                    全选
                  </Button>
                  <Button
                    size="small"
                    onClick={() => onSelectedPagesChange([])}
                  >
                    清空
                  </Button>
                </div>
              ) : null}
            </div>
            <div className={styles.outlineList}>
              {outlineMode === 'pages' ? (
                pages.map((page) => (
                  <PageOutlineItem
                    active={currentPage === page}
                    checked={selectedPages.includes(page)}
                    key={page}
                    page={page}
                    onCheck={(checked) => onTogglePage(page, checked)}
                    onOpen={() => goToPage(page)}
                  />
                ))
              ) : (
                <BookmarkList
                  bookmarks={metadata?.outlines ?? []}
                  selectedPages={selectedPages}
                  onOpen={goToPage}
                  onToggle={onTogglePage}
                />
              )}
            </div>
          </aside>
        ) : null}

        <main className={styles.viewer}>
          {frameUrl ? (
            <iframe
              className={styles.pdfFrame}
              src={frameUrl}
              title={fileName || 'PDF preview'}
            />
          ) : (
            <div className={styles.emptyPreview}>
              <Empty
                description="选择 PDF 后可在此预览"
                image={Empty.PRESENTED_IMAGE_SIMPLE}
              />
            </div>
          )}
        </main>
      </div>
    </section>
  );
};

interface PageOutlineItemProps {
  active: boolean;
  checked: boolean;
  page: number;
  onCheck: (checked: boolean) => void;
  onOpen: () => void;
}

const PageOutlineItem: React.FC<PageOutlineItemProps> = ({
  active,
  checked,
  page,
  onCheck,
  onOpen,
}) => (
  <div
    className={`${styles.pageItem} ${active ? styles.pageItemActive : ''}`}
    onClick={onOpen}
  >
    <Checkbox
      aria-label={`选择第 ${page} 页`}
      checked={checked}
      onClick={(event) => event.stopPropagation()}
      onChange={(event) => onCheck(event.target.checked)}
    />
    <span className={styles.pageLabel}>第 {page} 页</span>
  </div>
);

interface BookmarkListProps {
  bookmarks: PdfOutlineEntry[];
  selectedPages: number[];
  onOpen: (page: number | null) => void;
  onToggle: (page: number, checked: boolean) => void;
}

const BookmarkList: React.FC<BookmarkListProps> = ({
  bookmarks,
  selectedPages,
  onOpen,
  onToggle,
}) => {
  if (!bookmarks.length)
    return (
      <Empty description="文档没有书签" image={Empty.PRESENTED_IMAGE_SIMPLE} />
    );
  return (
    <>
      {bookmarks.map((bookmark) => (
        <div
          className={styles.bookmarkItem}
          key={bookmark.id}
          onClick={() => onOpen(bookmark.page ?? null)}
          style={{ paddingLeft: 8 + Math.min(bookmark.depth, 5) * 14 }}
        >
          <Checkbox
            checked={
              bookmark.page ? selectedPages.includes(bookmark.page) : false
            }
            disabled={!bookmark.page}
            onClick={(event) => event.stopPropagation()}
            onChange={(event) => {
              if (bookmark.page) onToggle(bookmark.page, event.target.checked);
            }}
          />
          <span className={styles.pageLabel}>
            {bookmark.title || '未命名书签'}
          </span>
          {bookmark.page ? (
            <span className={styles.pageNumber}>{bookmark.page}</span>
          ) : null}
        </div>
      ))}
    </>
  );
};

export default PdfHandler;
