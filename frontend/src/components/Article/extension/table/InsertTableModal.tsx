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
                    message.error('编辑器不可用').then();
                    return;
                }
                editorRef.current
                    .chain()
                    .focus()
                    .insertTable({rows, cols, withHeaderRow: true})
                    .run();
                message.success('插入表格成功').then();

                setModalOpen(false);
            })
            .catch((error) => {
                console.error('表单验证失败:', error);
            });
    }, [form, editorRef]);

    /** 插入表格弹窗 JSX，直接渲染到组件树中 */
    const insertTableModal = (
        <Modal
            title="插入表格"
            open={modalOpen}
            onOk={handleInsertConfirm}
            onCancel={() => setModalOpen(false)}
            destroyOnHidden
            width={400}
        >
            <Form form={form} layout="vertical" initialValues={{rows: 3, cols: 3}}>
                <Form.Item
                    label="行数"
                    name="rows"
                    rules={[{required: true, message: '请输入行数'}]}
                >
                    <InputNumber
                        min={1}
                        max={20}
                        style={{width: '100%'}}
                        placeholder="请输入行数"
                    />
                </Form.Item>
                <Form.Item
                    label="列数"
                    name="cols"
                    rules={[{required: true, message: '请输入列数'}]}
                >
                    <InputNumber
                        min={1}
                        max={20}
                        style={{width: '100%'}}
                        placeholder="请输入列数"
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
