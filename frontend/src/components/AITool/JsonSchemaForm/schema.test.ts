import {describe, expect, it} from 'vitest';
import {JsonSchemaParseError, parseJsonSchema, resolveSchema, schemaDefaultValue,} from './schema';

describe('JSON Schema utilities', () => {
  it('parses the ToolSchema transport format', () => {
    expect(
      parseJsonSchema({
        dialect: 'https://json-schema.org/draft/2020-12/schema',
        schema: JSON.stringify({
          type: 'object',
          properties: {title: {type: 'string'}},
        }),
      }),
    ).toMatchObject({type: 'object'});
  });

  it('resolves local references and allOf constraints', () => {
    const root = parseJsonSchema({
      $defs: {
        identifier: {type: 'string', minLength: 2},
      },
      type: 'object',
      properties: {
        id: {
          allOf: [{$ref: '#/$defs/identifier'}, {maxLength: 32}],
        },
      },
    });
    const idSchema = root.properties?.id;
    expect(idSchema).toBeDefined();
    const resolved = resolveSchema(idSchema || {}, root);
    expect(resolved).toMatchObject({
      type: 'string',
      minLength: 2,
      maxLength: 32,
    });
  });

  it('collects nested defaults without inventing empty values', () => {
    expect(
      schemaDefaultValue({
        type: 'object',
        properties: {
          enabled: {type: 'boolean', default: true},
          options: {
            type: 'object',
            properties: {limit: {type: 'integer', default: 10}},
          },
          untouched: {type: 'string'},
        },
      }),
    ).toEqual({enabled: true, options: {limit: 10}});
  });

  it('rejects malformed schemas with a stable error type', () => {
    expect(() => parseJsonSchema('{not-json}')).toThrow(JsonSchemaParseError);
    expect(() => parseJsonSchema('[]')).toThrow('JSON Schema 必须是对象');
  });
});
