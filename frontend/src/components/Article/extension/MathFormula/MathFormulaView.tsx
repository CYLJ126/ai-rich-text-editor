import React, {useCallback, useEffect, useState} from 'react';
import MathFormulaModal from './MathFormulaModal';
import modalBridge, {type MathFormulaType} from './modalBridge';
import {useEditorStore} from "@/components";

interface ModalState {
  open: boolean;
  type: MathFormulaType;
  latex: string;
  onUpdate?: (newLatex: string, newType: MathFormulaType) => void;
}

const CLOSED: ModalState = { open: false, type: 'block', latex: '' };

/**
 * 只负责注册 bridge + 渲染弹窗，不渲染任何按钮
 * 放在编辑器根组件里即可，不需要传 editor
 */
const MathFormulaView: React.FC = () => {
  const operationMode = useEditorStore(state => state.operationMode);
  const [modalState, setModalState] = useState<ModalState>(CLOSED);

  useEffect(() => {
    modalBridge.handler = {
      openModal: (type, currentLatex, onUpdate) => {
        operationMode === "edit" && setModalState({open: true, type, latex: currentLatex, onUpdate});
      },
    };
    return () => {
      modalBridge.handler = null;
    };
  }, [operationMode]);

  // eslint-disable-next-line @typescript-eslint/no-unused-vars
  const handleModalConfirm = useCallback(
    (latex: string, type: MathFormulaType) => {
      setModalState((prev) => {
        prev.onUpdate?.(latex, type);
        return CLOSED;
      });
    },
    [],
  );

  const handleModalCancel = useCallback(() => {
    setModalState(CLOSED);
  }, []);

  return (
    <MathFormulaModal
      open={modalState.open}
      initialLatex={modalState.latex}
      initialType={modalState.type}
      onConfirm={handleModalConfirm}
      onCancel={handleModalCancel}
    />
  );
};

export default MathFormulaView;
