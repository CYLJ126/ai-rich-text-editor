export type PdfToolKey = 'extract' | 'toPic' | 'toText' | 'split';

export interface PdfOutlineEntry {
  id: string;
  title: string;
  page?: number;
  depth: number;
}

export interface PdfMetadata {
  pageCount: number;
  title?: string;
  outlines: PdfOutlineEntry[];
  defaultOutputDir: string;
}

export interface PdfOperationResult {
  outputDir: string;
  files: string[];
  pages: number[];
}

export interface ToPicValues {
  format: 'PNG' | 'JPEG' | 'BMP';
  quality: number;
  dpi: number;
  stitch: boolean;
  colorMode: 'COLOR' | 'GRAY' | 'BINARY';
  outputDir: string;
  exportAll: boolean;
}

export interface SplitValues {
  pages: string;
  outputDir: string;
  outputName: string;
}

export interface ResultContext<T> {
  success: boolean;
  data: T;
  desc?: string;
}
