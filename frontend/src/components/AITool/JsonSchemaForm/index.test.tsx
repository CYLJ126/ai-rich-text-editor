import {fireEvent, render, screen, waitFor} from '@testing-library/react';
import React, {createRef} from 'react';
import {describe, expect, it, vi} from 'vitest';
import JsonSchemaForm from './index';
import type {JsonSchemaFormRef} from './types';

describe('JsonSchemaForm', () => {
  it('forwards text input changes to the Ant Design form before validation', async () => {
    const ref = createRef<JsonSchemaFormRef>();
    const onChange = vi.fn();
    render(
      <JsonSchemaForm
        ref={ref}
        schema={{
          type: 'object',
          properties: {
            firstName: {type: 'string', description: '名，例如：明'},
          },
          required: ['firstName'],
        }}
        onChange={onChange}
      />,
    );

    await expect(ref.current?.validate()).rejects.toBeDefined();
    expect(await screen.findByText('此字段为必填项')).toBeInTheDocument();

    fireEvent.change(screen.getByRole('textbox', {name: 'firstName'}), {
      target: {value: '春'},
    });

    await waitFor(() =>
      expect(screen.queryByText('此字段为必填项')).not.toBeInTheDocument(),
    );
    await expect(ref.current?.validate()).resolves.toEqual({firstName: '春'});
    expect(onChange).toHaveBeenLastCalledWith({firstName: '春'});
  });
});
