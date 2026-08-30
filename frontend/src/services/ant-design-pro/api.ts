// @ts-ignore
/* eslint-disable */
import {request} from '@umijs/max';
import {message} from "antd";

const PATH_PREFIX = '/arte'

/******************************************** 公共方法 ********************************************/
/**
 * jsonPost 请求后端
 *
 * @param path 请求路径
 * @param data 请求参数
 * @param options 请求时的选项
 * @return ResultContext 类型
 */
export async function jsonPost(path: string, data: any, options?: { [key: string]: any }) {
  const resultContext = await request<API.ResultContext>(PATH_PREFIX + path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: data,
    ...(options ?? {}),
  });
  if (resultContext.success) {
    return resultContext.data;
  }
}

export async function jsonGet(path: string, params?: any, options?: { [key: string]: any }) {
  const resultContext = await request<API.ResultContext>(PATH_PREFIX + path, {
    method: 'GET',
    params,
    ...(options ?? {}),
  });
  if (resultContext.success) {
    return resultContext.data;
  }
}

/**
 * jsonPost 请求后端返回列表，以处理列表统计数据，如总数等
 *
 * @param path 请求路径
 * @param data 请求参数
 * @param options 请求时的选项
 * @return ResultContext 类型
 */
export async function jsonPostList(path: string, data: any, options?: { [key: string]: any }) {
  return request<API.ResultContext>(PATH_PREFIX + path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: data,
    ...(options ?? {}),
  }).then((pageView: any) => {
    if (pageView.success) {
      if (!pageView.records || pageView.records.length === 0) {
        pageView.records = [];
      }
      return pageView;
    }
  });
}
/**
 * 请求 Blob 数据
 *
 * @param path 请求路径
 * @param data 请求参数
 * @param options 请求配置项
 */
export function jsonBlob(path: string, data: any, options?: { [key: string]: any }) {
  return request<API.ResultContext>(PATH_PREFIX + path, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: data,
    ...(options ?? {}),
    responseType: 'blob',
  }).then((resp: any) => {
    if (resp) {
      return resp;
    } else {
      message.error(`Blob 请求出错`).then((r) => {
      });
    }
  });
}

/******************************************** 公共方法 ********************************************/


/** 获取当前的用户 GET /api/currentUser */
export async function currentUser(options?: { [key: string]: any }) {
  return request<{
    data: API.CurrentUser;
  }>('/api/currentUser', {
    method: 'GET',
    ...(options || {}),
  });
}

/** 退出登录接口 POST /api/login/outLogin */
export async function outLogin(options?: { [key: string]: any }) {
  return request<Record<string, any>>('/api/login/outLogin', {
    method: 'POST',
    ...(options || {}),
  });
}

/** 登录接口 POST /api/login/account */
export async function login(body: API.LoginParams, options?: { [key: string]: any }) {
  return request<API.LoginResult>('/api/login/account', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: body,
    ...(options || {}),
  });
}

/** 此处后端没有提供注释 GET /api/notices */
export async function getNotices(options?: { [key: string]: any }) {
  return request<API.NoticeIconList>('/api/notices', {
    method: 'GET',
    ...(options || {}),
  });
}

/** 获取规则列表 GET /api/rule */
export async function rule(
  params: {
    // query
    /** 当前的页码 */
    current?: number;
    /** 页面的容量 */
    pageSize?: number;
  },
  options?: { [key: string]: any },
) {
  return request<API.RuleList>('/api/rule', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 更新规则 PUT /api/rule */
export async function updateRule(options?: { [key: string]: any }) {
  return request<API.RuleListItem>('/api/rule', {
    method: 'POST',
    data: {
      method: 'update',
      ...(options || {}),
    },
  });
}

/** 新建规则 POST /api/rule */
export async function addRule(options?: { [key: string]: any }) {
  return request<API.RuleListItem>('/api/rule', {
    method: 'POST',
    data: {
      method: 'post',
      ...(options || {}),
    },
  });
}

/** 删除规则 DELETE /api/rule */
export async function removeRule(options?: { [key: string]: any }) {
  return request<Record<string, any>>('/api/rule', {
    method: 'POST',
    data: {
      method: 'delete',
      ...(options || {}),
    },
  });
}
