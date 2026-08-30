import {i18nText} from '@/utils/i18n';
import {Button, Input} from 'antd';
import React, {type MouseEvent, useEffect, useState} from 'react';
import type {CommentRecord} from '@/components/Article/extension/comments/commentsExtension';
import {formatCommentTime} from './utils';

const { TextArea } = Input;

type CommentCardProps = {
  comment: CommentRecord;
  canEdit: boolean;
  canDelete: boolean;
  onUpdate: (value: string) => Promise<void> | void;
  onDelete: () => Promise<void> | void;
  onSelect?: (comment: CommentRecord) => void;
};

export default function CommentCard({
  comment,
  canEdit,
  canDelete,
  onUpdate,
  onDelete,
  onSelect,
}: CommentCardProps) {
  const [editing, setEditing] = useState(false);
  const [value, setValue] = useState(comment.content);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    setValue(comment.content);
  }, [comment.content]);

  const stopCardClick = (event: MouseEvent) => {
    event.stopPropagation();
  };

  if (comment.deletedAt) {
    return (
      <div className="rounded border border-dashed border-[var(--ant-color-border-secondary)] bg-[var(--ant-color-fill-quaternary)] px-3 py-2 text-xs text-[var(--ant-color-text-tertiary)]">
        {i18nText("app.article.commentspanel.commentcard.68b5a5b1")}
      </div>
    );
  }

  return (
    <div
      className="rounded border border-[var(--ant-color-border-secondary)] bg-[var(--ant-color-bg-container)] px-3 py-2"
      onClick={(event) => {
        event.stopPropagation();
        onSelect?.(comment);
      }}
    >
      <div className="flex items-center justify-between gap-2 text-xs text-[var(--ant-color-text-tertiary)]">
        <span className="font-medium text-[var(--ant-color-text)]">
          {comment.data.userName || i18nText("app.article.commentspanel.commentcard.2baaa25a")}
        </span>
        <span>{formatCommentTime(comment.createdAt)}</span>
      </div>

      {editing ? (
        <div
          className="mt-2"
          onClick={stopCardClick}
          onMouseDown={stopCardClick}
        >
          <TextArea
            autoSize={{ minRows: 2, maxRows: 4 }}
            disabled={submitting}
            value={value}
            onChange={(event) => setValue(event.target.value)}
          />
          <div className="mt-2 flex justify-end gap-2">
            <Button
              size="small"
              disabled={submitting}
              onClick={() => setEditing(false)}
            >
              {i18nText("app.article.commentspanel.commentcard.021cb45d")}
            </Button>
            <Button
              size="small"
              type="primary"
              loading={submitting}
              disabled={
                submitting || !value.trim() || value === comment.content
              }
              onClick={async () => {
                setSubmitting(true);
                try {
                  await onUpdate(value.trim());
                  setEditing(false);
                } finally {
                  setSubmitting(false);
                }
              }}
            >
              {i18nText("app.article.commentspanel.commentcard.40327ef8")}
            </Button>
          </div>
        </div>
      ) : (
        <>
          <div className="mt-2 whitespace-pre-wrap text-sm leading-6 text-[var(--ant-color-text-secondary)]">
            {comment.content}
          </div>
          <div className="mt-2 flex justify-end gap-2">
            {canEdit ? (
              <Button
                size="small"
                type="text"
                disabled={submitting}
                onClick={(event) => {
                  event.stopPropagation();
                  setEditing(true);
                }}
              >
                {i18nText("app.article.commentspanel.commentcard.1e23fd30")}
              </Button>
            ) : null}
            {canDelete ? (
              <Button
                size="small"
                type="text"
                danger
                loading={submitting}
                disabled={submitting}
                onClick={async (event) => {
                  event.stopPropagation();
                  setSubmitting(true);
                  try {
                    await onDelete();
                  } finally {
                    setSubmitting(false);
                  }
                }}
              >
                {i18nText("app.article.commentspanel.commentcard.fbf3488d")}
              </Button>
            ) : null}
          </div>
        </>
      )}
    </div>
  );
}
