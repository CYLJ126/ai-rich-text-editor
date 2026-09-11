import { request } from '@umijs/max';
import type {
  PdfMetadata,
  PdfOperationResult,
  ResultContext,
  SplitValues,
  ToPicValues,
} from './types';

const PDF_API = '/arte/tools/pdf';

export async function readPdfMetadata(file: File): Promise<PdfMetadata> {
  return postPdf<PdfMetadata>('/metadata', file);
}

export async function convertPdfToPictures(
  file: File,
  pages: string,
  values: ToPicValues,
): Promise<PdfOperationResult> {
  return postPdf<PdfOperationResult>('/to-pic', file, { ...values, pages });
}

export async function extractPdfPages(
  file: File,
  values: SplitValues,
): Promise<PdfOperationResult> {
  return postPdf<PdfOperationResult>('/split', file, values);
}

async function postPdf<T>(
  path: string,
  file: File,
  fields?: object,
): Promise<T> {
  const data = new FormData();
  data.append('file', file);
  Object.entries(fields ?? {}).forEach(([key, value]) => {
    data.append(key, String(value));
  });

  const result = await request<ResultContext<T>>(`${PDF_API}${path}`, {
    method: 'POST',
    data,
    requestType: 'form',
  });
  if (!result.success) throw new Error(result.desc || 'PDF 处理失败');
  return result.data;
}
