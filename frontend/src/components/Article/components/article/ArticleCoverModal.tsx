import {i18nText} from '@/utils/i18n';
import {Button, message, Modal, Upload} from "antd";
import {DeleteOutlined, UploadOutlined} from "@ant-design/icons";
import React, {useCallback, useState} from "react";
import {updateArticle} from "@/services/ant-design-pro/richText";
import {ArticleInfoType} from "@/types/rt.type";
import {uploadImage} from "@/services/upload";

export interface ArticleModalProps {
  visible: boolean; // 由父组件控制
  articleInfo?: ArticleInfoType | null; // 文章信息
  onClose: () => void; // 关闭回调
  onSuccess?: (cover: string) => void; // 封面设置成功回调
  onFailure?: () => void; // 封面设置失败回调
}

const ArticleCoverModal: React.FC<ArticleModalProps> = ({visible, articleInfo, onClose, onSuccess, onFailure}) => {
  const [coverDraft, setCoverDraft] = useState<string>(articleInfo?.cover || '');
  const [coverUploading, setCoverUploading] = useState(false);
  const [coverSaving, setCoverSaving] = useState(false);

  // 先上传图片，并返回链接
  const handleCoverUpload = useCallback(async (file: File) => {
    if (!file.type.startsWith('image/')) {
      message.warning(i18nText("app.article.article.articlecovermodal.73a463b2")).then();
      return false;
    }
    setCoverUploading(true);
    try {
      const url = await uploadImage(file, 'images/article/cover');
      setCoverDraft((url || '').trim());
      message.success(i18nText("app.article.article.articlecovermodal.37b367c7")).then();
    } catch {
      message.error(i18nText("app.article.article.articlecovermodal.d18c4b48")).then();
    } finally {
      setCoverUploading(false);
    }
    return false;
  }, []);

  // 将封面链接保存到文章信息中
  const handleCoverSave = useCallback(async () => {
    if (!articleInfo?.id) {
      message.info(i18nText("app.article.article.articlecovermodal.84318691")).then();
      return;
    }
    setCoverSaving(true);
    try {
      await updateArticle({
        id: articleInfo.id,
        title: articleInfo.title,
        summary: articleInfo.summary,
        cover: coverDraft,
      });
      message.success(coverDraft ? i18nText("app.article.article.articlecovermodal.20028740") : i18nText("app.article.article.articlecovermodal.2eb6f68a")).then();
      onSuccess?.(coverDraft);
    } catch {
      message.error(i18nText("app.article.article.articlecovermodal.15c989b4")).then();
      onFailure?.();
    } finally {
      setCoverSaving(false);
    }
  }, [coverDraft, articleInfo]);

  return (
    <Modal
      title={i18nText("app.article.article.articlecovermodal.3480ee81")}
      open={visible}
      okText={i18nText("app.article.article.articlecovermodal.f29e631f")}
      cancelText={i18nText("app.article.article.articlecovermodal.6907f718")}
      confirmLoading={coverSaving}
      onOk={handleCoverSave}
      onCancel={onClose}
    >
      <div style={{display: 'flex', flexDirection: 'column', gap: 12}}>
        <div
          style={{
            width: '100%',
            aspectRatio: '16 / 9',
            border: '1px solid #f0f0f0',
            borderRadius: 6,
            overflow: 'hidden',
            background: '#fafafa',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            color: '#999',
          }}
        >
          {coverDraft ? (
            <img
              src={coverDraft}
              alt={i18nText("app.article.article.articlecovermodal.799b2c41")}
              style={{width: '100%', height: '100%', objectFit: 'cover'}}
            />
          ) : (
            <span>{i18nText("app.article.article.articlecovermodal.5ef295fa")}</span>
          )}
        </div>
        <div style={{display: 'flex', gap: 8, justifyContent: 'flex-end'}}>
          <Button
            icon={<DeleteOutlined/>}
            disabled={!coverDraft || coverUploading || coverSaving}
            onClick={() => setCoverDraft('')}
          >
            {i18nText("app.article.article.articlecovermodal.404e21e3")}
          </Button>
          <Upload
            accept="image/*"
            showUploadList={false}
            beforeUpload={handleCoverUpload}
          >
            <Button
              type="primary"
              icon={<UploadOutlined/>}
              loading={coverUploading}
            >
              {i18nText("app.article.article.articlecovermodal.707ea8b9")}
            </Button>
          </Upload>
        </div>
      </div>
    </Modal>
  );
}

export default ArticleCoverModal;
