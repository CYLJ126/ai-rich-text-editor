/** 配置编辑保留数字原文，避免 JSON.parse/stringify 将精确小数和大整数改写为 double。 */
export type JsonValue =
  | { kind: 'object'; fields: Map<string, JsonValue> }
  | { kind: 'array'; items: JsonValue[] }
  | { kind: 'string'; value: string }
  | { kind: 'number'; raw: string }
  | { kind: 'boolean'; value: boolean }
  | { kind: 'null' };

const sensitive = new Set(['password', 'passwd', 'secret', 'apikey', 'accesstoken', 'refreshtoken', 'authorization', 'privatekey', 'clientsecret', 'token']);

export function parseConfiguration(source: string): Extract<JsonValue, { kind: 'object' }> {
  if (new TextEncoder().encode(source).length > 1_000_000) throw new Error('配置不能超过 1 MB。');
  let at = 0;
  let count = 0;
  const fail = (): never => {
    throw new Error(`配置 JSON 格式无效（位置 ${at + 1}），请检查后重试。`);
  };
  const whitespace = () => {
    while (/[\t\r\n ]/.test(source[at] ?? '') && at < source.length) at++;
  };
  const string = (): string => {
    const start = at++;
    while (at < source.length) {
      if (source[at] === '\\') {
        at += 2;
        continue;
      }
      if (source[at++] === '"') {
        try {
          return JSON.parse(source.slice(start, at));
        } catch {
          return fail();
        }
      }
    }
    return fail();
  };
  const read = (depth: number): JsonValue => {
    whitespace();
    if (depth > 24 || ++count > 8192) throw new Error('配置层级或节点数量超出限制。');
    const ch = source[at];
    if (ch === '{') {
      at++;
      whitespace();
      const fields = new Map<string, JsonValue>();
      if (source[at] === '}') {
        at++;
        return {kind: 'object', fields};
      }
      while (at < source.length) {
        whitespace();
        if (source[at] !== '"') return fail();
        const name = string();
        whitespace();
        if (fields.has(name)) throw new Error(`配置字段重复：${name}`);
        if (sensitive.has(name.replace(/[_-]/g, '').toLowerCase())) throw new Error('配置中不能填写密码或凭据，请使用平台支持的凭据管理方式。');
        if (fields.size >= 256 || source[at++] !== ':') return fail();
        fields.set(name, read(depth + 1));
        whitespace();
        if (source[at] === '}') {
          at++;
          return {kind: 'object', fields};
        }
        if (source[at++] !== ',') return fail();
      }
      return fail();
    }
    if (ch === '[') {
      at++;
      whitespace();
      const items: JsonValue[] = [];
      if (source[at] === ']') {
        at++;
        return {kind: 'array', items};
      }
      while (at < source.length) {
        if (items.length >= 256) return fail();
        items.push(read(depth + 1));
        whitespace();
        if (source[at] === ']') {
          at++;
          return {kind: 'array', items};
        }
        if (source[at++] !== ',') return fail();
      }
      return fail();
    }
    if (ch === '"') return {kind: 'string', value: string()};
    for (const token of ['true', 'false', 'null']) if (source.startsWith(token, at)) {
      at += token.length;
      return token === 'null' ? {kind: 'null'} : {kind: 'boolean', value: token === 'true'};
    }
    const number = /^-?(?:0|[1-9]\d*)(?:\.\d+)?(?:[eE][+-]?\d+)?/.exec(source.slice(at));
    if (!number) return fail();
    at += number[0].length;
    return {kind: 'number', raw: number[0]};
  };
  const value = read(0);
  whitespace();
  if (at !== source.length) return fail();
  if (value.kind !== 'object') throw new Error('配置必须是 JSON 对象。');
  return value;
}

export function encodeJson(value: JsonValue): string {
  switch (value.kind) {
    case 'object':
      return `{${[...value.fields].map(([key, child]) => `${JSON.stringify(key)}:${encodeJson(child)}`).join(',')}}`;
    case 'array':
      return `[${value.items.map(encodeJson).join(',')}]`;
    case 'number':
      return value.raw;
    case 'null':
      return 'null';
    default:
      return JSON.stringify(value.value);
  }
}
