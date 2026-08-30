import {i18nText} from '@/utils/i18n';
import {Form, InputNumber, message, Modal} from 'antd';
import React, {useCallback, useState} from 'react';
import {Editor} from "@tiptap/core";

// ─── 插入表格弹窗 ───
export function useInsertTable(editorRef: React.RefObject<Editor>) {
    const [modalOpen, setModalOpen] = useState(false);
    const [form] = Form.useForm();

    /** 打开插入表格弹窗 */
    const handleOpenInsertModal = useCallback(() => {
        form.resetFields();
        setModalOpen(true);
    }, [form]);

    /** 确认插入表格 */
    const handleInsertConfirm = useCallback(() => {
        form
            .validateFields()
            .then((values) => {
                const {rows, cols} = values;
                if (!editorRef.current) {
                    message.error(i18nText("app.article.table.inserttablemodal.2dfbed4a")).then();
                    return;
                }
                editorRef.current
                    .chain()
                    .focus()
                    .insertTable({rows, cols, withHeaderRow: true})
                    .run();
                message.success(i18nText("app.article.table.inserttablemodal.cbd45850")).then();

                setModalOpen(false);
            })
            .catch((error) => {
                console.error('表单验证失败:', error);
            });
    }, [form, editorRef]);

    /** 插入表格弹窗 JSX，直接渲染到组件树中 */
    const insertTableModal = (
        <Modal
            title={i18nText("app.article.table.inserttablemodal.2db4cb94")}
            open={modalOpen}
            onOk={handleInsertConfirm}
            onCancel={() => setModalOpen(false)}
            destroyOnHidden
            width={400}
        >
            <Form form={form} layout="vertical" initialValues={{rows: 3, cols: 3}}>
                <Form.Item
                    label={i18nText("app.article.table.inserttablemodal.6179be83")}
                    name="rows"
                    rules={[{required: true, message: i18nText("app.article.table.inserttablemodal.832a2b0b")}]}
                >
                    <InputNumber
                        min={1}
                        max={20}
                        style={{width: '100%'}}
                        placeholder={i18nText("app.article.table.inserttablemodal.832a2b0b")}
                    />
                </Form.Item>
                <Form.Item
                    label={i18nText("app.article.table.inserttablemodal.2aeb002e")}
                    name="cols"
                    rules={[{required: true, message: i18nText("app.article.table.inserttablemodal.721e928a")}]}
                >
                    <InputNumber
                        min={1}
                        max={20}
                        style={{width: '100%'}}
                        placeholder={i18nText("app.article.table.inserttablemodal.721e928a")}
                    />
                </Form.Item>
            </Form>
        </Modal>
    );

    return {
        modalOpen,
        handleOpenInsertModal,
        handleInsertConfirm,
        insertTableModal,
    };
}
