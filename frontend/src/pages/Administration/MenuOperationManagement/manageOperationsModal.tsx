import {i18nText} from '@/utils/i18n';
import React, {useEffect, useState} from 'react';
import {Button, Form, Input, message, Modal, Select, Table} from 'antd';
import {
    addMenuOperation,
    deactivateMenuOperation,
    listMenuOperations,
    updateMenuOperation,
} from '@/services/ant-design-pro/rbac';

const {TextArea} = Input;
const {Option} = Select;

interface Operation {
    id: number;
    menuCode: string;
    operationCode: string;
    operationName: string;
    status: number;
    description: string;
    rowVersion: string;
}

export interface ManageOperationsModalProps {
    visible: boolean;
    menuCode: string;
    menuName: string;
    onClose: () => void;
}

const ManageOperationsModal: React.FC<ManageOperationsModalProps> = ({
                                                                         visible,
                                                                         menuCode,
                                                                         menuName,
                                                                         onClose,
                                                                     }) => {
    const [operations, setOperations] = useState<Operation[]>([]);
    const [loading, setLoading] = useState(false);
    const [editingKey, setEditingKey] = useState<number | null>(null);
    const [form] = Form.useForm();

    // 加载操作权限列表
    const loadOperations = async () => {
        if (!menuCode) return;
        setLoading(true);
        try {
            const response = await listMenuOperations({menuCode});
            if (response && response.records) {
                setOperations(response.records);
            }
        } catch (error) {
            message.error(i18nText("app.administration.menuoperationmanagement.manageoperationsmodal.f289caec")).then();
            console.error('获取操作权限失败:', error);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        if (visible && menuCode) {
            loadOperations().then();
        }
    }, [visible, menuCode]);

    // 新增操作权限
    const handleAdd = () => {
        if (editingKey !== null) return;
        const newOperation: Operation = {
            id: -1,
            menuCode,
            operationCode: '',
            operationName: '',
            status: 1,
            description: '',
            rowVersion: '',
        };
        setOperations([...operations, newOperation]);
        setEditingKey(newOperation.id);
        // 重置表单，避免填充上一次编辑的内容
        form.resetFields();
    };

    // 保存操作权限
    const handleSave = async (record: Operation) => {
        try {
            const formValues = await form.validateFields();
            let response;
            if (record.id === -1) {
                // 新增操作
                response = await addMenuOperation({
                    menuCode: menuCode,
                    operationCode: formValues.operationCode,
                    operationName: formValues.operationName,
                    status: formValues.status,
                    description: formValues.description,
                });
            } else {
                // 更新操作
                response = await updateMenuOperation({
                    id: record.id,
                    menuCode: menuCode,
                    operationCode: formValues.operationCode,
                    operationName: formValues.operationName,
                    status: formValues.status,
                    description: formValues.description,
                    rowVersion: record.rowVersion,
                });
            }

            if (response) {
                message.success(i18nText("app.administration.menuoperationmanagement.manageoperationsmodal.6bc14f4b")).then();
                setEditingKey(null);
                loadOperations().then();
            } else {
                message.error(i18nText("app.administration.menuoperationmanagement.manageoperationsmodal.b994840f")).then();
            }
        } catch (error) {
            message.error(i18nText("app.administration.menuoperationmanagement.manageoperationsmodal.b994840f")).then();
            console.error('保存操作权限失败:', error);
        }
    };

    // 取消编辑
    const handleCancel = () => {
        setEditingKey(null);
        // 移除临时记录
        setOperations(operations.filter((op) => op.id !== -1));
    };

    // 切换状态
    const handleStatusChange = async (record: Operation, newStatus: number) => {
        try {
            const operation = operations.find((op) => op.id === record.id);
            if (!operation) return;

            const response = await deactivateMenuOperation({
                id: record.id,
                status: newStatus,
                menuCode: menuCode,
                operationCode: record.operationCode,
            });
            if (response) {
                message.success(i18nText("app.administration.menuoperationmanagement.manageoperationsmodal.aeef8e76")).then();
                loadOperations().then();
            } else {
                message.error(i18nText("app.administration.menuoperationmanagement.manageoperationsmodal.41a14c81")).then();
            }
        } catch (error) {
            message.error(i18nText("app.administration.menuoperationmanagement.manageoperationsmodal.41a14c81")).then();
            console.error('状态切换失败:', error);
        }
    };

    // 编辑单元格
    const isEditing = (record: Operation) => record.id === editingKey;

    const handleEdit = (record: Operation) => {
        form.setFieldsValue(record);
        setEditingKey(record.id);
    };

    // 列定义
    const columns = [
        {
            title: i18nText("app.administration.menuoperationmanagement.manageoperationsmodal.a56ab5d4"),
            dataIndex: 'operationCode',
            key: 'operationCode',
            width: 120,
            render: (text: string, record: Operation) => {
                if (isEditing(record)) {
                    return (
                        <Form.Item
                            name="operationCode"
                            rules={[{required: true, message: i18nText("app.administration.menuoperationmanagement.manageoperationsmodal.2d23b4a5")}]}
                            noStyle
                        >
                            <Input/>
                        </Form.Item>
                    );
                }
                return text;
            },
        },
        {
            title: i18nText("app.administration.menuoperationmanagement.manageoperationsmodal.aec3ec38"),
            dataIndex: 'operationName',
            key: 'operationName',
            width: 120,
            render: (text: string, record: Operation) => {
                if (isEditing(record)) {
                    return (
                        <Form.Item
                            name="operationName"
                            rules={[{required: true, message: i18nText("app.administration.menuoperationmanagement.manageoperationsmodal.813d641d")}]}
                            noStyle
                        >
                            <Input/>
                        </Form.Item>
                    );
                }
                return text;
            },
        },
        {
            title: i18nText("app.administration.menuoperationmanagement.manageoperationsmodal.1d5641ba"),
            dataIndex: 'status',
            key: 'status',
            width: 100,
            render: (text: number, record: Operation) => {
                if (isEditing(record)) {
                    return (
                        <Form.Item name="status" rules={[{required: true, message: i18nText("app.administration.menuoperationmanagement.manageoperationsmodal.e36153a8")}]} noStyle>
                            <Select style={{width: '100%'}}>
                                <Option value={1}>{i18nText("app.administration.menuoperationmanagement.manageoperationsmodal.937823e5")}</Option>
                                <Option value={3}>{i18nText("app.administration.menuoperationmanagement.manageoperationsmodal.81580528")}</Option>
                            </Select>
                        </Form.Item>
                    );
                }
                return (
                    <Select
                        style={{width: '100%'}}
                        value={text}
                        onChange={(value) => handleStatusChange(record, value)}
                    >
                        <Option value={1}>{i18nText("app.administration.menuoperationmanagement.manageoperationsmodal.937823e5")}</Option>
                        <Option value={3}>{i18nText("app.administration.menuoperationmanagement.manageoperationsmodal.81580528")}</Option>
                    </Select>
                );
            },
        },
        {
            title: i18nText("app.administration.menuoperationmanagement.manageoperationsmodal.0cd6383d"),
            dataIndex: 'description',
            key: 'description',
            render: (text: string, record: Operation) => {
                if (isEditing(record)) {
                    return (
                        <Form.Item name="description" noStyle>
                            <TextArea rows={1}/>
                        </Form.Item>
                    );
                }
                return text;
            },
        },
        {
            title: i18nText("app.administration.menuoperationmanagement.manageoperationsmodal.680040eb"),
            key: 'action',
            width: 140,
            render: (_: any, record: Operation) => {
                const editable = isEditing(record);
                return editable ? (
                    <>
                        <Button
                            type="primary"
                            size="small"
                            style={{marginRight: 8}}
                            onClick={() => handleSave(record)}
                        >
                            {i18nText("app.administration.menuoperationmanagement.manageoperationsmodal.6994b13f")}
                        </Button>
                        <Button size="small" onClick={handleCancel}>
                            {i18nText("app.administration.menuoperationmanagement.manageoperationsmodal.0ac05fe3")}
                        </Button>
                    </>
                ) : (
                    <Button type="link" size="small" onClick={() => handleEdit(record)}>
                        {i18nText("app.administration.menuoperationmanagement.manageoperationsmodal.a16152e9")}
                    </Button>
                );
            },
        },
    ];

    return (
        <Modal
            title={i18nText("app.administration.menuoperationmanagement.manageoperationsmodal.ee250568", {value0: menuName})}
            open={visible}
            onCancel={onClose}
            footer={null}
            width={800}
        >
            <div style={{marginBottom: 16}}>
                <Button type="primary" onClick={handleAdd} disabled={editingKey !== null}>
                    {i18nText("app.administration.menuoperationmanagement.manageoperationsmodal.070a129e")}
                </Button>
            </div>
            <Form form={form} component={false}>
                <Table
                    columns={columns}
                    dataSource={operations}
                    rowKey="id"
                    loading={loading}
                    pagination={false}
                    rowClassName="editable-row"
                    size="small"
                />
            </Form>
        </Modal>
    );
};

export default ManageOperationsModal;
