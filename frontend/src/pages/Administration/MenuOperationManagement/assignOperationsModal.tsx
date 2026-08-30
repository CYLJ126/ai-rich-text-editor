import React, {useRef} from 'react';
import {SimpleTable, TableColumn} from '@/components';
import {
    assignOperationsToRole,
    assignOperationsToUser,
    listMenuOperationsBySource,
} from '@/services/ant-design-pro/rbac';
import {Button, message, Modal} from 'antd';

interface AssignOperationsModalProps {
    visible: boolean;
    sourceName: string; // 角色名或用户名
    sourceType: 'role' | 'user'; // 区分角色还是用户
    onCancel: () => void;
    onSuccess: () => void;
}

const operationColumns: TableColumn[] = [
    {
        title: '权限名称',
        dataIndex: 'operationName',
        width: 150,
    },
    {
        title: '菜单编码',
        dataIndex: 'menuCode',
        width: 100,
    },
    {
        title: '权限编码',
        dataIndex: 'operationCode',
        width: 100,
    },
    {
        title: '描述',
        dataIndex: 'description',
        width: 300,
    },
];

const AssignOperationsModal: React.FC<AssignOperationsModalProps> = ({
                                                                         visible,
                                                                         sourceName,
                                                                         sourceType,
                                                                         onCancel,
                                                                         onSuccess,
                                                                     }) => {
    const operationTableRef = useRef<any>(null);

    // 加载权限列表
    const fetchOperationsData = async (params: any) => {
        const bindingType = sourceType === 'role' ? 'role_to_operation' : 'user_to_operation';
        return await listMenuOperationsBySource({
            source: sourceName,
            bindingType: bindingType,
            ...params,
        });
    };

    // 分配权限
    const handleAssignOperations = () => {
        const selectedOperations = operationTableRef.current?.getSelectedRows() || [];
        if (selectedOperations.length === 0) {
            message.warning('请选择至少一个权限').then();
            return;
        }

        const operationCodes = selectedOperations.map(
            (operation: any) => operation.menuCode + ':' + operation.operationCode,
        );
        let assignPromise;

        if (sourceType === 'role') {
            assignPromise = assignOperationsToRole({
                roleCode: sourceName,
                menuOperations: operationCodes,
            });
        } else {
            assignPromise = assignOperationsToUser({
                userName: sourceName,
                menuOperations: operationCodes,
            });
        }

        assignPromise
            .then(() => {
                message.success('权限分配成功').then();
                onSuccess();
            })
            .catch((error) => {
                message.error(`权限分配失败: ${error.message}`).then();
            });
    };

    // 获取弹窗标题
    const getModalTitle = () => {
        if (sourceType === 'role') {
            return `为角色 ${sourceName} 分配权限`;
        } else {
            return `为用户 ${sourceName} 分配权限`;
        }
    };

    return (
        <Modal
            title={getModalTitle()}
            open={visible}
            onCancel={onCancel}
            footer={[
                <Button key="cancel" onClick={onCancel}>
                    取消
                </Button>,
                <Button key="assign" type="primary" onClick={handleAssignOperations}>
                    分配
                </Button>,
            ]}
            width={1000}
            destroyOnHidden
        >
            <SimpleTable
                ref={operationTableRef}
                columns={operationColumns}
                fetchData={fetchOperationsData}
                tableHeight={400}
                defaultSize={500}
                rowKey="id"
                defaultSelectedField="assigned"
            />
        </Modal>
    );
};

export default AssignOperationsModal;
