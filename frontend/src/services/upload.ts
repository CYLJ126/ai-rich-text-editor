import { request } from '@umijs/max';

const PATH_PREFIX = '/nip';

/**
 * 上传图片到服务器（七牛云），返回图片访问链接
 * @param file 图片文件
 * @param folder 存储目录（可选，如 images/article）
 * @returns 图片 URL
 */
export async function uploadImage(file: File, folder?: string): Promise<string> {
  const formData = new FormData();
  formData.append('file', file);
  if (folder) {
    formData.append('folder', folder);
  }

  return request<{ success: boolean; data: string; message?: string }>(
    `${PATH_PREFIX}/richText/file/uploadImage`,
    {
      method: 'POST',
      data: formData,
      requestType: 'form',
    },
  ).then((result) => {
    if (result.success) {
      return result.data;
    }
    throw new Error(result.message || '上传失败');
  });
}

/**
 * 上传文件到服务器（七牛云低频存储）
 * @param file 文件
 * @param folder 存储目录（可选，如 files/documents）
 * @returns 文件 URL
 */
export async function uploadFile(file: File, folder?: string): Promise<string> {
  const formData = new FormData();
  formData.append('file', file);
  if (folder) {
    formData.append('folder', folder);
  }

  return request<{ success: boolean; data: string; message?: string }>(
    `${PATH_PREFIX}/richText/file/uploadFile`,
    {
      method: 'POST',
      data: formData,
      requestType: 'form',
    },
  ).then((result) => {
    if (result.success) {
      return result.data;
    }
    throw new Error(result.message || '上传失败');
  });
}

/** 通过后端读取已上传的文本文件，避免对象存储跨域限制。 */
export async function readUploadedText(url: string): Promise<string> {
  return request<{ success: boolean; data: string; message?: string }>(
    `${PATH_PREFIX}/richText/file/readText`,
    {method: 'GET', params: {url}},
  ).then((result) => {
    if (result.success) return result.data;
    throw new Error(result.message || '文件读取失败');
  });
}

/** 删除已由富文本文件服务保存的文件。 */
export async function deleteUploadedFile(url: string): Promise<void> {
  const result = await request<{ success: boolean; message?: string }>(
    `${PATH_PREFIX}/richText/file/deleteFile`,
    {method: 'POST', params: {url}},
  );
  if (!result.success) throw new Error(result.message || '文件删除失败');
}

/** 由后端下载 HTTP 图片并转存到当前配置的 LOCAL/七牛云图片存储。 */
export async function importImageByUrl(url: string): Promise<string> {
  return request<{ success: boolean; data: string; message?: string }>(
    `${PATH_PREFIX}/richText/file/importImage`,
    {method: 'POST', params: {url}},
  ).then((result) => {
    if (result.success) return result.data;
    throw new Error(result.message || '远程图片转存失败');
  });
}
