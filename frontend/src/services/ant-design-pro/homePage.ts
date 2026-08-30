import {jsonBlob, jsonPost} from './api';

/**  ----------------- WebsiteInfoController start ----------------- */

/** 获取首页闻链接 POST /homePage/websiteInfo/listWebsiteNews */
export async function listWebsiteNews(param: any) {
  return jsonPost('/homePage/websiteInfo/listWebsiteNews', param);
}

/**
 * 获取新闻网站的图标
 *
 * @param id 新闻网站 id
 * @param logoUrl 图标地址
 */
export async function getWebsiteLogo(id: number, logoUrl: string) {
  return jsonBlob('/homePage/websiteInfo/getWebsiteLogo', {id, logoUrl});
}

/**  ----------------- WebsiteInfoController end ----------------- */
