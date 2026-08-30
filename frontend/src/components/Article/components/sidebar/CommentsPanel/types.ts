export type CommentCurrentUser = API.CurrentUser & {
  userName?: string;
};

export type BackendComment = {
  commentId?: string;
  content?: string;
  createBy?: string;
  updateBy?: string;
  createTime?: number | string;
  updateTime?: number | string;
  deletedAt?: number | string;
};

export type BackendCommentThread = {
  threadId?: string;
  createBy?: string;
  updateBy?: string;
  createTime?: number | string;
  updateTime?: number | string;
  resolvedAt?: number | string;
  comments?: BackendComment[];
};
