import {jsonPost, jsonPostList} from './api';
// 格式化时间为本地时间
import 'dayjs/locale/zh-cn';

/**  ----------------- MenuController start ----------------- */
/**
 * 获取菜单
 *
 * @param data 请求参数
 */
export async function listRecursiveMenus(data: any) {
  return jsonPost('/rbac/menu/listRecursiveMenus', data);
}

export async function addMenu(data: any) {
  return jsonPost('/rbac/menu/addMenu', data);
}

export async function updateMenu(data: any) {
  return jsonPost('/rbac/menu/updateMenu', data);
}

export async function deactivateMenu(id: number) {
  return jsonPost('/rbac/menu/deactivateMenu', {id: id});
}

export async function getMenuByCode(menuCode: string) {
  return jsonPost('/rbac/menu/getMenuByCode', {menuCode: menuCode});
}

export async function listMenu(data: any) {
  return jsonPostList('/rbac/menu/listMenu', data);
}

export async function listMenusBySource(data: any) {
  return jsonPostList('/rbac/menu/listMenusBySource', data);
}

/**  ----------------- MenuController end ----------------- */

/**  ----------------- UserController start ----------------- */
export async function addUser(param: any) {
  return jsonPost('/rbac/user/addUser', param);
}

export async function updateUser(param: any) {
  return jsonPost('/rbac/user/updateUser', param);
}

export async function deactivateUser(userName: string) {
  return jsonPost('/rbac/user/deactivateUser', {userName: userName});
}

export async function getUserByName(userName: string) {
  return jsonPost('/rbac/user/getUserByName', {userName: userName});
}

export async function listUser(param: any) {
  return jsonPostList('/rbac/user/listUser', param);
}

export async function listUserByTarget(param: any) {
  return jsonPostList('/rbac/user/listUserByTarget', param);
}

export async function assignRolesToUser(param: any) {
  return jsonPost('/rbac/user/assignRolesToUser', param);
}

export async function assignMenusToUser(param: any) {
  return jsonPost('/rbac/user/assignMenusToUser', param);
}

export async function assignOperationsToUser(param: any) {
  return jsonPost('/rbac/user/assignOperationsToUser', param);
}

/**  ----------------- UserController end ----------------- */

/**  ----------------- RoleController start ----------------- */
export async function addRole(param: any) {
  return jsonPost('/rbac/role/addRole', param);
}

export async function updateRole(param: any) {
  return jsonPost('/rbac/role/updateRole', param);
}

export async function deactivateRole(param: any) {
  return jsonPost('/rbac/role/deactivateRole', param);
}

export async function getRoleByCode(roleCode: string) {
  return jsonPost('/rbac/role/getRoleByCode', {roleCode: roleCode});
}

export async function listRole(param: any) {
  return jsonPostList('/rbac/role/listRole', param);
}

export async function listRolesByUser(param: any) {
  return jsonPostList('/rbac/role/listRolesByUser', param);
}

export async function assignMenusToRole(param: any) {
  return jsonPost('/rbac/role/assignMenusToRole', param);
}

export async function assignOperationsToRole(param: any) {
  return jsonPost('/rbac/role/assignOperationsToRole', param);
}

export async function assignRoleToUsers(param: any) {
  return jsonPost('/rbac/role/assignRoleToUsers', param);
}

/**  ----------------- RoleController end ----------------- */

/**  ----------------- MenuOperationController start ----------------- */

export async function listMenuOperations(param: any) {
  return jsonPostList('/rbac/menuOperation/listMenuOperations', param);
}

export async function listMenuOperationsBySource(param: any) {
  return jsonPostList('/rbac/menuOperation/listMenuOperationsBySource', param);
}

export async function addMenuOperation(param: any) {
  return jsonPost('/rbac/menuOperation/addMenuOperation', param);
}

export async function updateMenuOperation(param: any) {
  return jsonPost('/rbac/menuOperation/updateMenuOperation', param);
}

export async function deactivateMenuOperation(param: any) {
  return jsonPost('/rbac/menuOperation/deactivateMenuOperation', param);
}

/**  ----------------- MenuOperationController end ----------------- */
