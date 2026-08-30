export type MathFormulaType = 'inline' | 'block';

export interface ModalBridgeHandler {
  openModal: (
    type: MathFormulaType,
    currentLatex: string,
    onUpdate: (newLatex: string) => void,
  ) => void;
}

// 挂到 window 上，彻底避免多实例问题
declare global {
  interface Window {
    __mathFormulaModalBridge__: ModalBridgeHandler | null;
  }
}

if (typeof window !== 'undefined') {
  window.__mathFormulaModalBridge__ = null;
}

const modalBridge = {
  get handler(): ModalBridgeHandler | null {
    return typeof window !== 'undefined' ? window.__mathFormulaModalBridge__ : null;
  },
  set handler(val: ModalBridgeHandler | null) {
    if (typeof window !== 'undefined') {
      window.__mathFormulaModalBridge__ = val;
    }
  },
};

export default modalBridge;
