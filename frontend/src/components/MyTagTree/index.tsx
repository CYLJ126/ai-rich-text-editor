import {i18nText} from '@/utils/i18n';
import {DownOutlined} from '@ant-design/icons';
import {Button, Checkbox, Flex, Input, Space, Tree} from 'antd';
import React, {useCallback, useEffect, useRef, useState} from 'react';
import AddIcon from '@/icons/AddIcon';
import DeleteIcon from '@/icons/DeleteIcon';
import TagIcon from '@/icons/TagIcon';
import {
  addTag,
  addTagRelation,
  deleteTag,
  deleteTagRelation,
  listRecursive,
  updateTag,
} from '@/services/ant-design-pro/base';
import {useTagStyles, useTreeStyles} from './indexStyle';
import {getColorByIndex} from '@/utils/colorUtil';

/** 标签基础数据（与后端字段对应） */
export interface TagData {
  id?: number;
  name?: string;
  orderId?: number;
  fatherId?: number;
  status?: number;
  checked?: boolean;
  description?: string;
  createBy?: string;
  updateBy?: string;
  createTime?: string;
  updateTime?: string;
  children?: TagData[];
}

/** 树节点：TagData + 树渲染所需字段 */
export interface TagNode extends TagData {
  key: string;
  title?: React.ReactNode;
  icon?: React.ReactNode;
  color?: string;
  level?: number;
  children?: TagNode[];
  /** 勾选变化回调（由父组件注入，供 Title 回调） */
  onCheckChange?: (node: TagNode, checked: boolean) => void;
}

/** 操作指令，附加到 TagNode 上触发 CRUD */
type TagAction = TagNode & {
  /** 当前操作类型 */
  action?: 'add' | 'update' | 'delete';
};

/**
 * onDrop 回调参数类型
 */
interface DropInfo {
  dragNode: TagNode;
  node: TagNode;
  dropPosition: number;
  dropToGap: boolean;
}

interface ExpandInfo {
  expanded: boolean;
  node: TagNode;
}

/**
 * findNodeByKey 返回值类型
 */
interface FindNodeResult {
  node: TagNode;
  parent: TagNode | null;
  index: number;
}

/**
 * 从 TagAction 提取可序列化的纯数据，避免存在 ReactNode 时，axios 在深度合并/克隆请求参数时触发无限递归。
 * @param action
 */
function toApiPayload(action: TagAction): TagData {
  return {
    id: action.id,
    name: action.name,
    fatherId: action.fatherId,
    orderId: action.orderId,
    status: action.status,
    description: action.description,
    createBy: action.createBy,
    updateBy: action.updateBy,
    createTime: action.createTime,
    updateTime: action.updateTime,
  };
}

/**
 * 根据 key 在树中查找节点信息
 * 返回 { node, parent, index } —— 节点本身、父节点（根节点时为 null）、在兄弟中的下标
 */
function findNodeByKey(
  nodes: TagNode[],
  key: string,
  parent: TagNode | null = null,
): FindNodeResult | null {
  for (let i = 0; i < nodes.length; i++) {
    if (nodes[i].key === key) return {node: nodes[i], parent, index: i};
    if (nodes[i].children?.length) {
      const found = findNodeByKey(nodes[i].children!, key, nodes[i]);
      if (found) return found;
    }
  }
  return null;
}

/**
 * 从树中移除指定 key 的节点，返回被移除的节点
 */
function removeNodeByKey(nodes: TagNode[], key: string): TagNode | null {
  for (let i = 0; i < nodes.length; i++) {
    if (nodes[i].key === key) return nodes.splice(i, 1)[0];
    if (nodes[i].children?.length) {
      const removed = removeNodeByKey(nodes[i].children!, key);
      if (removed) return removed;
    }
  }
  return null;
}

/** 深拷贝树（title/icon 为 ReactNode，保持引用即可） */
function cloneTree(nodes: TagNode[]): TagNode[] {
  return nodes.map((n) => ({...n, children: n.children ? cloneTree(n.children) : []}));
}

/** 递归收集所有 checked=true 的节点（纯数据，不含 ReactNode） */
function collectCheckedNodes(nodes: TagNode[]): TagNode[] {
  const result: TagNode[] = [];

  function walk(list: TagNode[]) {
    for (const node of list) {
      if (node.checked) {
        result.push(toPureNode(node, true));
      }
      if (node.children?.length) walk(node.children);
    }
  }

  walk(nodes);
  return result;
}

/** 提取纯数据节点（去掉 ReactNode 字段，避免父组件拿到不可序列化的数据） */
function toPureNode(node: TagNode, checked: boolean): TagNode {
  return {
    id: node.id,
    key: node.key,
    name: node.name,
    color: node.color,
    level: node.level,
    fatherId: node.fatherId,
    orderId: node.orderId,
    checked,
  };
}

/** 递归收集所有节点的 key */
function collectAllKeys(nodes: TagNode[]): string[] {
  const keys: string[] = [];

  function walk(list: TagNode[]) {
    for (const node of list) {
      keys.push(node.key);
      if (node.children?.length) walk(node.children);
    }
  }

  walk(nodes);
  return keys;
}

/** 递归收集所有节点的 id（排除 undefined） */
function collectAllIds(nodes: TagNode[]): number[] {
  const ids: number[] = [];

  function walk(list: TagNode[]) {
    for (const node of list) {
      if (node.id !== undefined) ids.push(node.id);
      if (node.children?.length) walk(node.children);
    }
  }

  walk(nodes);
  return ids;
}

interface TitleProps {
  /** 树节点数据 */
  node: TagNode;
  /** 操作函数 */
  onAction: (action: TagAction) => void;
  /** 是否显示勾选框（tagTypes 只有一个元素时为 true） */
  showCheckbox: boolean;
  /** 关联的业务源 ID（如文章 ID、视频 ID 等） */
  sourceId?: number;
  /** 标签类型，即 tagTypes[0] */
  tagType?: string;
}

function Title({node, onAction, showCheckbox, sourceId, tagType}: TitleProps) {
  const [titleText, setTitleText] = useState(node.name ?? '');
  /** 用本地 state 管理勾选，避免等待后端响应期间 UI 无响应 */
  const [checked, setChecked] = useState(!!node.checked);
  /** 防止并发重复请求 */
  const [loading, setLoading] = useState(false);

  const {styles} = useTagStyles({title: titleText, color: node.color ?? '', level: node.level ?? 0});

  /** 勾选框变化处理 */
  async function handleCheckChange(nextChecked: boolean) {
    if (loading || !node.id || !showCheckbox || !sourceId || !tagType) return;

    const prev = checked;
    // 乐观更新本地 UI
    setChecked(nextChecked);
    setLoading(true);
    try {
      if (nextChecked) {
        await addTagRelation(sourceId, node.id, tagType);
      } else {
        await deleteTagRelation(sourceId, node.id, tagType);
      }
      // ✅ 接口成功后，通知父组件更新 checkedTags
      node.onCheckChange?.(node, nextChecked);
    } catch {
      // 接口失败时回滚本地 UI
      setChecked(prev);
    } finally {
      setLoading(false);
    }
  }

  return (
    <Space>
      <Space.Compact>
        <Input
          className={styles.title}
          value={titleText}
          onChange={(e) => setTitleText(e.target.value)}
          onBlur={() => onAction({
            id: node.id,
            key: node.key,
            name: titleText,
            fatherId: node.fatherId,
            orderId: node.orderId,
            status: node.status,
            color: node.color,
            level: node.level,
            action: 'update',
          })}
        />
      </Space.Compact>

      <Space.Compact className={styles.addIcon}>
        <AddIcon
          width={20}
          height={20}
          color={node.color}
          onClick={() => onAction({
            id: node.id,
            key: node.key,
            name: titleText,
            fatherId: node.fatherId,
            orderId: node.orderId,
            status: node.status,
            color: node.color,
            level: node.level,
            action: 'add',
          })}
        />
      </Space.Compact>

      <Space.Compact className={styles.deleteIcon}>
        <DeleteIcon
          color={node.color}
          onClick={() => onAction({
            id: node.id,
            key: node.key,
            action: 'delete',
          })}
        />
      </Space.Compact>

      {/* 仅 tagTypes 长度为 1 时渲染 */}
      {showCheckbox && (
        <Checkbox
          checked={checked}
          disabled={loading}
          onChange={(e) => handleCheckChange(e.target.checked)}
          // 阻止事件冒泡，避免触发树节点展开/折叠
          onClick={(e) => e.stopPropagation()}
        />
      )}
    </Space>
  );
}

export interface MyTagTreeProps {
  /** 标签类型，与后端枚举一一对应 */
  tagTypes?: string[];
  /** 附加的查询参数 */
  queryParam?: object;
  /** tagTypes.length === 1 时必须传入，用于关联业务实体 */
  sourceId?: number;
  /** 初始进入时是否展开 */
  defaultExpanded?: boolean;
  /** 勾选时触发的回调 */
  onChecked?: (checkedNodes: TagNode[]) => void;
  /** 页面高度 */
  pageHeight?: number;
}

const MyTagTree: React.FC<MyTagTreeProps> = ({
                                               tagTypes = [],
                                               queryParam = {},
                                               sourceId,
                                               onChecked,
                                               defaultExpanded = false,
                                               pageHeight = 500,
                                             }) => {
  const {styles} = useTreeStyles({pageHeight});
  const [expanded, setExpanded] = useState(defaultExpanded);
  const [treeData, setTreeData] = useState<TagNode[]>([]);
  const [expandedKeys, setExpandedKeys] = useState<string[]>([]);
  /** 标签树实例引用 */
  const treeRef = useRef<any>(null);
  /** 已勾选节点列表（用 ref 避免闭包陈旧值，变更时同步通知父组件） */
  const checkedNodesRef = useRef<TagNode[]>([]);
  /** 跨渲染周期记录已展开节点的 id，用于数据刷新后恢复展开状态 */
  const expandedIdsRef = useRef<number[]>([]);
  /** 是否为首次加载的标志，用于区分首次加载与后续 CRUD 刷新 */
  const isFirstLoadRef = useRef<boolean>(true);
  /** 是否启用勾选模式 */
  const checkboxEnabled = tagTypes.length === 1 && !!sourceId;
  /** 标签类型，即 tagTypes[0] */
  const tagType = checkboxEnabled ? tagTypes[0] : undefined;

  // ─── 切换展开收起状态 ────────────────────────────────────────────────────────────────
  const toggleExpandState = useCallback(() => {
    const recursiveExpanded = (nodes: TagNode[]) => {
      const container = [] as string[];
      nodes.forEach(node => {
        container.push(node.key);
        if (node.children && node.children.length > 0) {
          container.push(...recursiveExpanded(node.children ?? []));
        }
      });
      return container;
    };

    const prev = expanded;
    setExpanded(!prev);
    if (prev) {
      setExpandedKeys([]);
      // 收起时清空记录的展开 id
      expandedIdsRef.current = [];
    } else {
      const allKeys = recursiveExpanded(treeData);
      setExpandedKeys(allKeys);
      // 展开时同步记录所有 id
      expandedIdsRef.current = collectAllIds(treeData);
    }
  }, [expanded, treeData]);

  // ─── 展开第一层 ────────────────────────────────────────────────────────────────
  const expandTopTags = useCallback(() => {
    setExpanded(true);
    const topKeys: string[] = [];
    const topIds: number[] = [];
    console.log('treeData: ', treeData);
    treeData.forEach(node => {
      node.key && topKeys.push(node.key);
      node.id && topIds.push(node.id);
    });
    setExpandedKeys(topKeys);
    expandedIdsRef.current = topIds;
  }, [expanded, treeData]);

  // ─── 勾选处理 ────────────────────────────────────────────────────────────────
  const handleCheckChange = useCallback(
    (node: TagNode, checked: boolean) => {
      const pure = toPureNode(node, checked);
      const prev = checkedNodesRef.current;

      checkedNodesRef.current = checked
        ? prev.some((t) => t.id === pure.id)
          ? prev
          : [...prev, pure]
        : prev.filter((t) => t.id !== pure.id);

      onChecked?.(checkedNodesRef.current);
    },
    [onChecked],
  );

  // ─── 节点渲染 ────────────────────────────────────────────────────────────────
  /** 将 TagData 转换为带 title/icon 的 TagNode（需要传入 onAction 闭包） */
  const buildNode = useCallback(
    (data: TagData, key: string, color: string, level: number, onAction: (a: TagAction) => void): TagNode => {
      const node: TagNode = {
        ...data,
        key,
        name: data.name,
        color,
        level,
        onCheckChange: handleCheckChange, // 将 handleChecked 注入到 tagDto，供 Title 回调
        children: data.children as TagNode[],
      };

      node.title = (
        <Title
          node={node}
          onAction={onAction}
          showCheckbox={checkboxEnabled}
          sourceId={sourceId}
          tagType={tagType}
        />
      );

      node.icon = (
        <TagIcon width={18} height={18} color={color} margin="7px 15px 0 0"/>
      );

      return node;
    },
    [handleCheckChange, sourceId],
  );

  /** 递归将 TagData[] 转换为 TagNode[]，同时收集需要展开的 key */
  const buildTree = useCallback(
    (
      dataList: TagData[],
      level: number,
      timestamp: number,
      onAction: (a: TagAction) => void,
      expandedKeysOut: string[],
    ): TagNode[] => {
      return (dataList ?? []).map((data, index) => {
        const color = getColorByIndex(index, 0);
        const key = `${data.id}_${timestamp}`;
        const node = buildNode(data, key, color, level, onAction);

        if (data.id !== undefined && expandedIdsRef.current.includes(data.id)) {
          expandedKeysOut.push(key);
        }

        node.children = buildTree(data.children ?? [], level + 1, timestamp, onAction, expandedKeysOut);
        return node;
      });
    },
    [buildNode],
  );

  /** 从后端重新拉取并刷新整棵树，同时恢复已勾选状态 */
  const refresh = useCallback(
    (onAction: (a: TagAction) => void) => {
      listRecursive({...queryParam, tagTypes, sourceId}).then((result: TagData[]) => {
        const expandedKeysOut: string[] = [];
        const nodes = buildTree(result, 1, Date.now(), onAction, expandedKeysOut);

        setTreeData(nodes);

        // 首次加载且 defaultExpanded=true：展开所有节点并同步状态
        if (isFirstLoadRef.current && defaultExpanded) {
          const allKeys = collectAllKeys(nodes);
          setExpandedKeys(allKeys);
          setExpanded(true);
          expandedIdsRef.current = collectAllIds(nodes);
        } else {
          // 非首次刷新：根据 expandedIdsRef 恢复上次记录的展开状态
          setExpandedKeys(expandedKeysOut);
        }

        // 标记首次加载已完成
        isFirstLoadRef.current = false;

        const checked = collectCheckedNodes(nodes);
        checkedNodesRef.current = checked;
        onChecked?.(checked);
      });
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [buildTree, defaultExpanded, queryParam, tagTypes, sourceId, onChecked],
  );

  const handleAction = useCallback(
    (action: TagAction) => {
      if (action.action === 'delete') {
        if (!action.id) return;
        deleteTag(action.id).then(() => refresh(handleAction));

      } else if (action.action === 'update') {
        const payload = toApiPayload(action);
        if (action.id) {
          updateTag(payload).then(() => refresh(handleAction));
        } else {
          addTag(payload).then(() => refresh(handleAction));
        }

      } else if (action.action === 'add') {
        // 乐观插入临时子节点，保存后由 refresh 替换为真实数据
        const timestamp = Date.now();
        const newChildData: TagData = {
          name: i18nText("app.common.mytagtree.8d5ae0f6"),
          status: 1,
          fatherId: action.id,
        };
        const newChild = buildNode(
          newChildData,
          `tmp_${timestamp}`,
          '#919191',
          (action.level ?? 0) + 1,
          handleAction,
        );

        const insertChild = (nodes: TagNode[]): TagNode[] =>
          nodes.map((n) => {
            if (n.id === action.id) {
              return {...n, children: [...(n.children ?? []), newChild]};
            }
            if (n.children?.length) {
              return {...n, children: insertChild(n.children)};
            }
            return n;
          });

        setTreeData((prev) => insertChild(prev));
      }
    },
    // handleAction 内部引用自身，用 ref 转发避免循环依赖
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [buildNode, refresh],
  );

  // ─── 添加根标签 ────────────────────────────────────────────────────────────────
  const addRootTag = useCallback(() => {
    const topFirstNode = treeData?.[0];
    const level = topFirstNode?.level ?? 1;
    const fatherId = topFirstNode?.fatherId ?? 0;
    const timestamp = Date.now();
    // 构造临时新节点数据
    const newRootData: TagData = {
      name: i18nText("app.common.mytagtree.8d5ae0f6"),
      status: 1,
      fatherId: fatherId === 0 ? undefined : fatherId,
    };
    // 乐观插入临时节点到树末尾
    const newRootNode = buildNode(
      newRootData,
      `tmp_${timestamp}`,
      getColorByIndex(treeData.length, 0),
      level,
      handleAction,
    );
    setTreeData((prev) => [...prev, newRootNode]);
    // 向后端提交，成功后用真实数据刷新
    addTag({
      name: i18nText("app.common.mytagtree.8d5ae0f6"),
      status: 1,
      fatherId: fatherId === 0 ? undefined : fatherId,
      orderId: treeData.length,
    }).then(() => refresh(handleAction));
  }, [treeData, buildNode, handleAction, refresh]);

  // ─── 拖拽处理 ────────────────────────────────────────────────────────────────
  const onDrop = useCallback(
    ({dragNode, node: dropNode, dropPosition, dropToGap}: DropInfo) => {
      const newTree = cloneTree(treeData);
      const dragKey = dragNode.key;
      const dropKey = dropNode.key;

      const dragged = removeNodeByKey(newTree, dragKey);
      if (!dragged) return;

      let newFatherId: number | undefined;
      let insertIndex: number;

      if (!dropToGap) {
        // 放入目标节点内部
        const dropInfo = findNodeByKey(newTree, dropKey);
        if (!dropInfo) return;
        const target = dropInfo.node;
        target.children = [...(target.children ?? []), dragged];
        insertIndex = target.children.length - 1;
        newFatherId = target.id;
      } else {
        // 放到目标节点同级
        const dropInfo = findNodeByKey(newTree, dropKey);
        if (!dropInfo) return;
        const siblings = dropInfo.parent ? dropInfo.parent.children! : newTree;
        newFatherId = dropInfo.parent?.id;

        const refIndex = siblings.findIndex((n) => n.key === dropKey);
        insertIndex = dropPosition === -1
          ? Math.max(refIndex, 0)
          : (refIndex < 0 ? siblings.length : refIndex + 1);
        siblings.splice(insertIndex, 0, dragged);
      }

      setTreeData(newTree);

      if (!dragged.id) return;
      updateTag({id: dragged.id, fatherId: newFatherId, orderId: insertIndex}).then(
        () => refresh(handleAction),
      );
    },
    [treeData, refresh, handleAction],
  );

  // ─── 展开/折叠 ────────────────────────────────────────────────────────────────
  const onExpand = useCallback((keys: any[], {expanded, node}: ExpandInfo) => {
    setExpandedKeys(keys);
    if (node.id === undefined) return;
    if (expanded) {
      if (!expandedIdsRef.current.includes(node.id)) {
        expandedIdsRef.current = [...expandedIdsRef.current, node.id];
      }
    } else {
      expandedIdsRef.current = expandedIdsRef.current.filter((id) => id !== node.id);
    }
  }, []);

  useEffect(() => {
    refresh(handleAction);
  }, [sourceId]);

  return (
    <div className={styles.container}>
      {/* 工具栏：固定高度，不参与滚动 */}
      <Flex gap="small" className={styles.buttonBar}>
        <Button
          className={styles.toggleBtn}
          onClick={toggleExpandState}
        >
          {expanded ? i18nText("app.common.mytagtree.13a5ec9f") : i18nText("app.common.mytagtree.a18eaa8a")}
        </Button>
        <Button
          className={styles.toggleBtn}
          onClick={expandTopTags}
        >
          {i18nText("app.common.mytagtree.35dc27d4")}
        </Button>
        <Button
          className={styles.addBtn}
          onClick={addRootTag}
        >
          {i18nText("app.common.mytagtree.657065b0")}
        </Button>
      </Flex>

      {/* 滚动包裹层：flex:1 占满剩余空间，超出时滚动且不显示滚动条 */}
      <div className={styles.treeScrollWrapper}>
        <Tree
          className={styles.tagTree}
          ref={treeRef}
          showLine
          showIcon
          draggable
          blockNode
          expandedKeys={expandedKeys}
          onExpand={onExpand}
          onDrop={onDrop}
          switcherIcon={<DownOutlined/>}
          treeData={treeData}
        />
      </div>
    </div>
  );
};

export default MyTagTree;
