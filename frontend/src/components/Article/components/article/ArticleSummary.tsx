import {i18nText} from '@/utils/i18n';
import {Button, Flex, Input, message} from "antd";
import React, {useCallback, useEffect, useRef, useState} from 'react';
import type {ArticleInfoType} from "@/types/rt.type";
import {createStyles} from "antd-style";
import {DraggableLine} from "@/components";
import {ModelConfig} from "@/types/ai.model.type";
import {STREAM_ARTICLE_SUMMARY_URL, streamChat} from "@/services/ant-design-pro/ai.chat";

export interface ArticleSummaryType {
  /** 当前文章信息 */
  articleInfo: ArticleInfoType,
  /** 更新文章信息中的总结 */
  onSummaryUpdate: (id: number, summary: string) => void;
  /** 当前用户 */
  currentUser: string | undefined;
  /** 当前模型 */
  currentModel: ModelConfig | undefined;
  /** 总结字数上限 */
  characterCountCeil: number | undefined;
}

const SUMMARY_TYPE = 'article';
const MIN_INPUT_HEIGHT = 180;

const useStyles = createStyles(({token}) => {
  return {
    button: {
      height: 25,
    }
  };
});

/**
 * 文章摘要/总结组件
 * @constructor
 */
const ArticleSummary: React.FC<ArticleSummaryType> = ({
                                                        articleInfo,
                                                        onSummaryUpdate,
                                                        currentUser,
                                                        currentModel,
                                                        characterCountCeil
                                                      }) => {
  const {styles} = useStyles();
  const [content, setContent] = useState<string>(articleInfo.summary || '');
  const [aiOutput, setAiOutput] = useState<string>('');
  const [inputHeight, setInputHeight] = useState(MIN_INPUT_HEIGHT);
  const [showAiInput, setShowAiInput] = useState(false);
  const currentModelRef = useRef<ModelConfig | undefined>(currentModel);
  const [acceptDisabled, setAcceptDisabled] = useState(true);
  const [generateDisabled, setGenerateDisabled] = useState(false);

  const generateSummary = useCallback((generateType: string) => {
    setGenerateDisabled(true);
    setAcceptDisabled(true);
    if (!currentModelRef.current) {
      message.warning(i18nText("app.article.article.articlesummary.54557824")).then();
      return;
    }
    if(generateType === 'polish') {
      if(!content) {
        message.warning(i18nText("app.article.article.articlesummary.fd917650")).then();
        return;
      }
    }
    const param = {
      originalText: content,
      characterCountCeil,
      modelId: currentModelRef.current?.id,
      generateType,
      scene: 'writing_summary',
      userName: currentUser,
      chatRagRequest: {
        knowledgeBaseType: SUMMARY_TYPE,
        articleIds: [articleInfo.id]
      }
    }
    setAiOutput('');
    streamChat(STREAM_ARTICLE_SUMMARY_URL, param, {
      onMessageId: () => setShowAiInput(true),
      onContent: (delta: string) => {
        setAiOutput(prev => prev + delta);
        if (aiOutput.length > 0 && !showAiInput) {
          setShowAiInput(true)
        }
      },
      onDone: () => {
        setGenerateDisabled(false);
        setAcceptDisabled(false);
      }
    });
  }, [content, characterCountCeil, showAiInput, currentUser, articleInfo.id, aiOutput.length]);

  useEffect(() => {
    currentModelRef.current = currentModel;
  }, [currentModel]);

  useEffect(() => {
    setContent(articleInfo.summary || '');
  }, [articleInfo]);

  return (
    <div>
      <Input.TextArea
        style={{height: inputHeight, minHeight: MIN_INPUT_HEIGHT, scrollbarWidth: 'none'}}
        value={content}
        placeholder={i18nText("app.article.article.articlesummary.60f5f0c8")}
        onChange={(e) => setContent(e.target.value)}
      />
      <DraggableLine
        direction="vertical"
        size={inputHeight}
        className="-mt-1 opacity-0"
        minSize={MIN_INPUT_HEIGHT}
        onSizeChange={setInputHeight}
      />
      <Flex gap="small" justify="flex-end" className="my-2!">
        <Button className={styles.button} disabled={generateDisabled} onClick={() => generateSummary('summary')}>{i18nText("app.article.article.articlesummary.65b24e88")}</Button>
        <Button className={styles.button} disabled={generateDisabled || !content} onClick={() => generateSummary('polish')}>{i18nText("app.article.article.articlesummary.90bc4fbb")}</Button>
        <Button className={styles.button} disabled={acceptDisabled} onClick={() => {
          setContent(aiOutput);
          setShowAiInput(false);
          setAcceptDisabled(true);
        }}>{i18nText("app.article.article.articlesummary.548f51db")}</Button>
        <Button className={styles.button} type="primary" onClick={() => {
          onSummaryUpdate(articleInfo.id as number, content);
        }}>{i18nText("app.article.article.articlesummary.78021264")}</Button>
      </Flex>
      {showAiInput && <Input.TextArea autoSize value={aiOutput} className="scrollbar-none"/>}
    </div>
  )
};

export default ArticleSummary;
