import {i18nText} from '@/utils/i18n';
import JSZip from 'jszip';

/**
 * 文件名合法性校验
 */
const sanitizeFileName = (name: string): string => {
  return (
    name
      .trim()
      .replace(/[<>:"/\\|?*\p{Cc}]/gu, '_') // Windows/Unix 非法字符
      .replace(/\.+$/, '') // 去除末尾点
      .slice(0, 255) || // 限制最大长度
    'untitled'
  );
};

type LocalResource = {
  originalUrl: string;
  zipPath: string;
  downloadUrl: string;
};

const LOCAL_IMAGE_MARKER = '/richText/file/local/image/';
const LOCAL_FILE_MARKER = '/richText/file/local/file/';

const downloadBlob = (fileName: string, blob: Blob) => {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');

  link.href = url;
  link.download = fileName;

  // 触发下载
  document.body.appendChild(link);
  link.click();

  // 清理资源
  document.body.removeChild(link);
  setTimeout(() => URL.revokeObjectURL(url), 100);
};

const isLocalRichTextResource = (url: string): boolean => {
  try {
    const parsed = new URL(url, window.location.origin);
    return (
      parsed.pathname.includes(LOCAL_IMAGE_MARKER) ||
      parsed.pathname.includes(LOCAL_FILE_MARKER)
    );
  } catch {
    return false;
  }
};

const buildAssetPath = (url: string, usedNames: Set<string>): string => {
  const parsed = new URL(url, window.location.origin);
  const isImage = parsed.pathname.includes(LOCAL_IMAGE_MARKER);
  const folder = isImage ? 'assets/images' : 'assets/files';
  const rawName = decodeURIComponent(
    parsed.pathname.split('/').pop() || 'file',
  );
  const safeName = sanitizeFileName(rawName);
  const dotIndex = safeName.lastIndexOf('.');
  const base = dotIndex > 0 ? safeName.slice(0, dotIndex) : safeName;
  const ext = dotIndex > 0 ? safeName.slice(dotIndex) : '';
  let candidate = `${folder}/${safeName}`;
  let index = 1;

  while (usedNames.has(candidate)) {
    candidate = `${folder}/${base}-${index}${ext}`;
    index += 1;
  }
  usedNames.add(candidate);
  return candidate;
};

const collectLocalResources = (content: string): LocalResource[] => {
  const resources: LocalResource[] = [];
  const usedUrls = new Set<string>();
  const usedNames = new Set<string>();
  const markdownLinkPattern =
    /(!?\[[^\]]*]\()([^)\s]+)((?:\s+["'][^"']*["'])?\))/g;
  let match: RegExpExecArray | null;
  match = markdownLinkPattern.exec(content);
  while (true) {
    match = markdownLinkPattern.exec(content);
    if (!match) break;
    const originalUrl = match[2];
    if (!isLocalRichTextResource(originalUrl) || usedUrls.has(originalUrl)) {
      continue;
    }
    usedUrls.add(originalUrl);
    resources.push({
      originalUrl,
      zipPath: buildAssetPath(originalUrl, usedNames),
      downloadUrl: new URL(originalUrl, window.location.origin).toString(),
    });
  }
  return resources;
};

const replaceResourceUrls = (
  content: string,
  resources: LocalResource[],
): string => {
  let result = content;
  for (const resource of resources) {
    result = result.split(resource.originalUrl).join(resource.zipPath);
  }
  return result;
};

const fetchResourceBlob = async (resource: LocalResource): Promise<Blob> => {
  const response = await fetch(resource.downloadUrl, {
    credentials: 'include',
  });
  if (!response.ok) {
    throw new Error(i18nText("app.common.utils.fileutil.6c0b0d8c", {value0: resource.originalUrl}));
  }
  return response.blob();
};

/**
 * Export Markdown. If it references local rich-text files, export a zip package
 * containing the Markdown and those files.
 */
export const exportFile = async (
  fileName: string,
  content: string,
): Promise<void> => {
  const sanitizedFileName = sanitizeFileName(fileName);
  const markdownFileName = sanitizedFileName.endsWith('.md')
    ? sanitizedFileName
    : `${sanitizedFileName}.md`;
  const resources = collectLocalResources(content);

  if (resources.length === 0) {
    downloadBlob(
      markdownFileName,
      new Blob([content], {
        type: 'text/markdown;charset=utf-8',
      }),
    );
    return;
  }

  const zip = new JSZip();
  zip.file(markdownFileName, replaceResourceUrls(content, resources));

  await Promise.all(
    resources.map(async (resource) => {
      zip.file(resource.zipPath, await fetchResourceBlob(resource));
    }),
  );

  const blob = await zip.generateAsync({ type: 'blob' });
  const zipName = `${sanitizedFileName.replace(/\.md$/i, '')}.zip`;
  downloadBlob(zipName, blob);
};

/**
 * Export the rendered editor content through the browser's PDF print flow.
 */
export const exportPdf = (
  fileName: string,
  editorElement: HTMLElement,
): Promise<void> =>
  new Promise((resolve, reject) => {
    const title = document.title;
    const printStyle = document.createElement('style');
    const exportElements: HTMLElement[] = [editorElement];
    let ancestor = editorElement.parentElement;

    while (ancestor) {
      exportElements.push(ancestor);
      ancestor = ancestor.parentElement;
    }

    editorElement.dataset.pdfExportRoot = 'true';
    exportElements.slice(1).forEach((element) => {
      element.dataset.pdfExportAncestor = 'true';
    });

    printStyle.textContent = `
      @media print {
        @page {
          size: A4;
          margin: 18mm;
        }

        [data-pdf-export-ancestor='true'] {
          box-sizing: border-box !important;
          display: block !important;
          height: auto !important;
          max-height: none !important;
          max-width: none !important;
          min-height: 0 !important;
          overflow: visible !important;
          padding: 0 !important;
          position: static !important;
          width: 100% !important;
        }

        [data-pdf-export-ancestor='true'] >
          :not([data-pdf-export-ancestor='true']):not([data-pdf-export-root='true']) {
          display: none !important;
        }

        [data-pdf-export-root='true'] {
          background: #fff !important;
          box-sizing: border-box !important;
          color: #1f2328;
          height: auto !important;
          max-width: none !important;
          min-height: 0 !important;
          overflow: visible !important;
          padding: 0 !important;
          width: 100% !important;
        }

        [data-pdf-export-root='true'] button,
        [data-pdf-export-root='true'] [data-resize-handle] {
          display: none !important;
        }

        [data-pdf-export-root='true'] img,
        [data-pdf-export-root='true'] svg,
        [data-pdf-export-root='true'] video {
          height: auto;
          max-width: 100%;
        }

        [data-pdf-export-root='true'] h1,
        [data-pdf-export-root='true'] h2,
        [data-pdf-export-root='true'] h3,
        [data-pdf-export-root='true'] h4,
        [data-pdf-export-root='true'] h5,
        [data-pdf-export-root='true'] h6 {
          break-after: avoid;
        }

        [data-pdf-export-root='true'] img,
        [data-pdf-export-root='true'] pre,
        [data-pdf-export-root='true'] blockquote,
        [data-pdf-export-root='true'] tr {
          break-inside: avoid;
        }
      }
    `;
    document.head.appendChild(printStyle);
    document.title = sanitizeFileName(fileName).replace(/\.pdf$/i, '');

    let finished = false;
    const cleanup = () => {
      if (finished) return;
      finished = true;
      window.removeEventListener('afterprint', handleAfterPrint);
      printStyle.remove();
      document.title = title;
      editorElement.removeAttribute('data-pdf-export-root');
      exportElements.slice(1).forEach((element) => {
        element.removeAttribute('data-pdf-export-ancestor');
      });
    };
    const handleAfterPrint = () => {
      cleanup();
      resolve();
    };

    window.addEventListener('afterprint', handleAfterPrint, {once: true});
    window.requestAnimationFrame(() => {
      try {
        window.print();
        window.setTimeout(handleAfterPrint, 60_000);
      } catch (error) {
        cleanup();
        reject(error);
      }
    });
  });
