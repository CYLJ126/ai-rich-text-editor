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
      message.warning('请选择图片文件').then();
      return false;
    }
    setCoverUploading(true);
    try {
      const url = await uploadImage(file, 'images/article/cover');
      setCoverDraft((url || '').trim());
      message.success('封面上传成功').then();
    } catch {
      message.error('封面上传失败').then();
    } finally {
      setCoverUploading(false);
    }
    return false;
  }, []);

  // 将封面链接保存到文章信息中
  const handleCoverSave = useCallback(async () => {
    if (!articleInfo?.id) {
      message.info('请先打开一篇文章').then();
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
      message.success(coverDraft ? '封面已保存' : '封面已清除').then();
      onSuccess?.(coverDraft);
    } catch {
      message.error('封面保存失败').then();
      onFailure?.();
    } finally {
      setCoverSaving(false);
    }
  }, [coverDraft, articleInfo]);

  return (
    <Modal
      title="设置文章封面"
      open={visible}
      okText="保存封面"
      cancelText="取消"
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
              alt="文章封面"
              style={{width: '100%', height: '100%', objectFit: 'cover'}}
            />
          ) : (
            <span>暂无封面</span>
          )}
        </div>
        <div style={{display: 'flex', gap: 8, justifyContent: 'flex-end'}}>
          <Button
            icon={<DeleteOutlined/>}
            disabled={!coverDraft || coverUploading || coverSaving}
            onClick={() => setCoverDraft('')}
          >
            清除封面
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
              上传封面
            </Button>
          </Upload>
        </div>
      </div>
    </Modal>
  );
}

export default ArticleCoverModal;
