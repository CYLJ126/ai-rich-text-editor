import React, { useEffect, useRef } from 'react';
import { Input } from 'antd';
import type { InputRef } from 'antd';

interface AutoFocusInputProps {
  placeholder?: string;
  onChange: (e: React.ChangeEvent<HTMLInputElement>) => void;
  onPressEnter: () => void;
  defaultValue?: string;
}

const AutoFocusInput: React.FC<AutoFocusInputProps> = ({ placeholder, onChange, onPressEnter, defaultValue }) => {
  const inputRef = useRef<InputRef>(null);

  useEffect(() => {
    // 延迟 150ms 等待 Modal 展开动画结束，确保能够成功捕获焦点
    const timer = setTimeout(() => {
      inputRef.current?.focus({
        cursor: 'end', // 保持光标在文字末尾
      });
    }, 150);

    return () => clearTimeout(timer);
  }, []);

  return (
    <Input
      defaultValue={defaultValue}
      ref={inputRef}
      placeholder={placeholder}
      onChange={onChange}
      onPressEnter={onPressEnter}
    />
  );
};

export {AutoFocusInput}
