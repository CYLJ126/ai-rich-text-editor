/**
 * 会话、固定用户轮次、历史路径与回答候选；执行状态通过 Invocation 引用查询，不维护第二权威。
 * 普通轮次按会话版本受理；主动重新生成保留 Turn 并新建 Invocation，编辑重发新建 Turn。
 */
package com.arte.ainew.pojo.conversation;
