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

/**
 * 操作权限映射
 * 由于前端的 MenuCode（即路由路径）和后端的权限 Code（即 Controller 方法上的操作权限标识）不一致，需要进行映射
 */
const MENU_CODE_MAP: Record<string, string[]> = {
  'HomePage': ['homePage', 'website'],
  'Administration': ['administration', 'admin'],
  'MenuManagement': ['menu', 'menuOperation'],
  'UserManagement': ['user'],
  'RoleManagement': ['role'],
  'Summary': ['summary'],
  "TagManagement": ['tag', 'tagRelation'],
  "StickyNote": ['sticky'],
};

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
        if (!menuCode || !MENU_CODE_MAP[menuCode]) return;
        setLoading(true);
        try {
            const response = await listMenuOperations({menuCodes: MENU_CODE_MAP[menuCode]});
            if (response && response.records) {
                setOperations(response.records);
            }
        } catch (error) {
            message.error(i18nText("app.administration.manageoperationsmodal.failedToGetOperationPermissions")).then();
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
                    menuCode: formValues.menuCode,
                    operationCode: formValues.operationCode,
                    operationName: formValues.operationName,
                    status: formValues.status,
                    description: formValues.description,
                });
            } else {
                // 更新操作
                response = await updateMenuOperation({
                    id: record.id,
                    menuCode: formValues.menuCode,
                    operationCode: formValues.operationCode,
                    operationName: formValues.operationName,
                    status: formValues.status,
                    description: formValues.description,
                    rowVersion: record.rowVersion,
                });
            }

            if (response) {
                message.success(i18nText("app.administration.manageoperationsmodal.savedSuccessfully")).then();
                setEditingKey(null);
                loadOperations().then();
            } else {
                message.error(i18nText("app.administration.manageoperationsmodal.saveFailed")).then();
            }
        } catch (error) {
            message.error(i18nText("app.administration.manageoperationsmodal.saveFailed")).then();
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
                message.success(i18nText("app.administration.manageoperationsmodal.statusChangedSuccessfully")).then();
                loadOperations().then();
            } else {
                message.error(i18nText("app.administration.manageoperationsmodal.failedToChangeStatus")).then();
            }
        } catch (error) {
            message.error(i18nText("app.administration.manageoperationsmodal.failedToChangeStatus")).then();
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
            title: i18nText("app.administration.manageoperationsmodal.menuCode"),
            dataIndex: 'menuCode',
            key: 'menuCode',
            width: 120,
            render: (text: string, record: Operation) => {
                if (isEditing(record)) {
                    return (
                        <Form.Item
                            name="menuCode"
                            rules={[{required: true, message: i18nText("app.administration.manageoperationsmodal.enterMenuCode")}]}
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
            title: i18nText("app.administration.manageoperationsmodal.operationCode"),
            dataIndex: 'operationCode',
            key: 'operationCode',
            width: 120,
            render: (text: string, record: Operation) => {
                if (isEditing(record)) {
                    return (
                        <Form.Item
                            name="operationCode"
                            rules={[{required: true, message: i18nText("app.administration.manageoperationsmodal.enterOperationCode")}]}
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
            title: i18nText("app.administration.manageoperationsmodal.operationName"),
            dataIndex: 'operationName',
            key: 'operationName',
            width: 120,
            render: (text: string, record: Operation) => {
                if (isEditing(record)) {
                    return (
                        <Form.Item
                            name="operationName"
                            rules={[{required: true, message: i18nText("app.administration.manageoperationsmodal.enterOperationName")}]}
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
            title: i18nText("app.administration.manageoperationsmodal.status"),
            dataIndex: 'status',
            key: 'status',
            width: 100,
            render: (text: number, record: Operation) => {
                if (isEditing(record)) {
                    return (
                        <Form.Item name="status" rules={[{required: true, message: i18nText("app.administration.manageoperationsmodal.selectStatus")}]} noStyle>
                            <Select style={{width: '100%'}}>
                                <Option value={1}>{i18nText("app.administration.manageoperationsmodal.enabled")}</Option>
                                <Option value={3}>{i18nText("app.administration.manageoperationsmodal.inactive")}</Option>
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
                        <Option value={1}>{i18nText("app.administration.manageoperationsmodal.enabled")}</Option>
                        <Option value={3}>{i18nText("app.administration.manageoperationsmodal.inactive")}</Option>
                    </Select>
                );
            },
        },
        {
            title: i18nText("app.administration.manageoperationsmodal.description"),
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
            title: i18nText("app.administration.manageoperationsmodal.operation"),
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
                            {i18nText("app.administration.manageoperationsmodal.save")}
                        </Button>
                        <Button size="small" onClick={handleCancel}>
                            {i18nText("app.administration.manageoperationsmodal.cancel")}
                        </Button>
                    </>
                ) : (
                    <Button type="link" size="small" onClick={() => handleEdit(record)}>
                        {i18nText("app.administration.manageoperationsmodal.edit")}
                    </Button>
                );
            },
        },
    ];

    return (
        <Modal
            title={i18nText("app.administration.manageoperationsmodal.operationPermissionManagement", {value0: menuName})}
            open={visible}
            onCancel={onClose}
            footer={null}
            width={800}
        >
            <div style={{marginBottom: 16}}>
                <Button type="primary" onClick={handleAdd} disabled={editingKey !== null}>
                    {i18nText("app.administration.manageoperationsmodal.newOperationPermission")}
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
