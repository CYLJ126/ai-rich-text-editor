import {request} from '@umijs/max';
import {jsonPost, jsonPostList} from './api';

/**  ----------------- TagController start ----------------- */

/** 获取用户标签 POST /arte/base/tag/listTags */
export async function getTags(param: any) {
  return jsonPost('/base/tag/listTags', param);
}

export async function getSubTags(param: any) {
  const result = await getTags({...param, status: 1});
  return (
    result?.map((item: any) => {
      return {value: item.id, label: item.name};
    }) || []
  );
}

export async function listRecursive(param: any) {
  return jsonPost('/base/tag/listRecursive', param);
}

/**
 * 插入标签
 * @param param 标签对象
 */
export async function addTag(param: any) {
  return jsonPost('/base/tag/addTag', param);
}

/**
 * 更新标签
 * @param param 标签对象
 */
export async function updateTag(param: any) {
  return jsonPost('/base/tag/updateTag', param);
}

/**
 * 删除标签
 * @param id 标签 ID
 */
export async function deleteTag(id?: number) {
  return jsonPost('/base/tag/deleteTag', {id: id});
}

/**
 * 标签重排序
 * @param params 标签列表
 */
export async function reorderTags(params: any) {
  return jsonPost('/base/tag/reorderTags', params);
}

/**  ----------------- TagController end ----------------- */

/**  ----------------- TagRelationController start ----------------- */
/**
 * 添加标签关系
 * @param sourceId 源对象 ID
 * @param tagId 标签 ID
 * @param tagType 标签类型，见枚举 TagTypeEnum
 */
export async function addTagRelation(sourceId: number, tagId: number, tagType: string) {
  return jsonPost('/tag-relation/addTagRelation', {
    sourceId: sourceId,
    tagId: tagId,
    tagType: tagType,
  });
}

/**
 * 删除标签关系
 * @param sourceId 源对象 ID
 * @param tagId 标签 ID
 * @param tagType 标签类型，见枚举 TagTypeEnum
 */
export async function deleteTagRelation(sourceId: number, tagId: number, tagType: string) {
  return jsonPost('/tag-relation/deleteTagRelation', {
    sourceId: sourceId,
    tagId: tagId,
    tagType: tagType,
  });
}

/**
 * 查询标签关系
 * @param sourceId 源对象 ID
 * @param tagType 标签类型，见枚举 TagTypeEnum
 */
export async function listTagRelations(sourceId: number, tagType: string) {
  return jsonPost('/tag-relation/listTagRelations', {sourceId: sourceId, tagType: tagType});
}

/**  ----------------- TagRelationController end ----------------- */

/**  ----------------- AuthorizationController start ----------------- */

/** 获取公钥，用于加密敏感信息 POST /arte/auth/getPubKey */
export async function getPubKey() {
  return jsonPost('/auth/getPubKey', {});
}

/** 获取当前用户信息 POST /arte/auth/onlineInfo */
export async function queryCurrentUser(options?: { [key: string]: any }) {
  return request<API.CurrentUser>('/arte/auth/onlineInfo', {
    method: 'POST',
    ...(options ?? {}),
  });
}

/** 登录接口 POST /arte/auth/login */
export async function login(
  body: {
    password: string | undefined;
    userName: string | undefined;
    autoLogin: boolean | undefined;
  },
  options?: { [p: string]: any },
) {
  return request<API.LoginResult>('/arte/auth/login', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options ?? {}),
  });
}

/** 登录接口 POST /arte/auth/logout */
export async function logout(
  options?: { [p: string]: any },
) {
  return request<API.LoginResult>('/arte/auth/logout', {
    method: 'POST',
    headers: {},
    ...(options ?? {}),
  });
}

/**
 * 修改密码
 * @param param 请求参数
 */
export async function changePassword(param: any) {
  return jsonPost('/auth/changePassword', param);
}

/**  ----------------- AuthorizationController end ----------------- */

/**  ----------------- SummaryController start ----------------- */
/**
 * 获取总结内容
 *
 * @param type 对应类型
 * @param targetId 对应目标 ID
 */
export async function getSummaryByTargetIdAndType(type: string, targetId: number) {
  return jsonPost('/base/summary/getSummaryByTargetIdAndType', {type: type, targetId: targetId});
}

/**
 * 插入或更新总结
 *
 * @param data 总结对象，无 id 字段说明是插入，有 id 则为更新
 */
export async function saveSummary(data: any) {
  return jsonPost('/base/summary/saveSummary', data);
}

/**
 * 格式化总结内容
 *
 * @param content 总结内容
 * @param operationType 操作类型：formatSerialNo-格式化序号；removeTime-移除工时；
 */
export async function formatContent(content: string, operationType: string) {
  return jsonPost('/base/summary/formatContent', {
    content: content,
    operationType: operationType,
  });
}

/**  ----------------- SummaryController end ----------------- */

/**  ----------------- StandardFieldController start ----------------- */
/**
 * 添加标准字段
 * @param param 标准字段对象
 */
export async function addStandardField(param: any) {
  return jsonPost('/base/standardField/addStandardField', param);
}

/**
 * 更新标准字段
 * @param param 标准字段对象
 */
export async function updateStandardField(param: any) {
  return jsonPost('/base/standardField/updateStandardField', param);
}

/**
 * 切换标准字段状态
 * @param param 标准字段参数
 */
export async function switchStandardFieldStatus(param: any) {
  return jsonPost('/base/standardField/switchStandardFieldStatus', param);
}

/**
 * 删除标准字段（逻辑删除）
 * @param id 标准字段 ID
 */
export async function deleteStandardField(id: number) {
  return jsonPost(`/base/standardField/deleteStandardField/${id}`, {});
}

/**
 * 获取标准字段详情
 * @param id 标准字段 ID
 */
export async function getStandardFieldById(id: number) {
  return jsonPost(`/base/standardField/getStandardField/${id}`, {});
}

/**
 * 查询标准字段列表
 * @param param 查询参数
 */
export async function listStandardFields(param: any) {
  return jsonPostList('/base/standardField/listStandardFields', param);
}

/**  ----------------- StandardFieldController end ----------------- */
