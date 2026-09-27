import {request} from '@umijs/max';
import {createToolApiError} from '@/features/ai-tool';
import type {ToolApiEnvelope} from '@/types/ai.tool.type';

const ARTE_API_PREFIX = '/arte';

export interface ToolRequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  params?: Record<string, unknown>;
  data?: unknown;
  signal?: AbortSignal;
  headers?: Record<string, string>;
  skipErrorHandler?: boolean;
}

/**
 * 工具域统一请求入口。
 *
 * 网络错误继续交由 Umi 全局错误处理；HTTP 200 中的业务失败会转换为 ToolApiError，
 * 页面可据此展示重试按钮、审批提示或字段错误，不再依赖 undefined 判断。
 */
export async function requestToolApi<T>(
  path: string,
  options: ToolRequestOptions = {},
): Promise<T> {
  const response = await request<ToolApiEnvelope<T>>(
    `${ARTE_API_PREFIX}${path}`,
    {
      ...options,
      headers: {
        ...(options.data === undefined
          ? {}
          : {'Content-Type': 'application/json'}),
        ...options.headers,
      },
    },
  );

  if (!response || response.success !== true) {
    throw createToolApiError(
      response || {code: 'EMPTY_RESPONSE', desc: '工具服务返回空响应'},
    );
  }
  return response.data as T;
}

export function encodeToolPathPart(value: string): string {
  return encodeURIComponent(value);
}

