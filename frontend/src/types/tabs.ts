// src/types/tabs.ts
export interface TabItem {
  key: string;
  label: string;
  pathname: string;
  search?: string;
  closable?: boolean;
}

export interface TabsContextType {
  tabs: TabItem[];
  activeKey: string;
  addTab: (tab: TabItem) => void;
  removeTab: (key: string) => void;
  setActiveTab: (key: string) => void;
}
