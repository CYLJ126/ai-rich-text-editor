#!/usr/bin/env python3
"""重置清单第 1.4、5 节：仅处理明确指定的旧 ES 代际和独占 Redis 搜索命名空间。"""
import json
import os
import re
import subprocess
from urllib.parse import urlparse, quote

# 本地临时 ES 认证；使用后删除再提交。
ES_BASIC_AUTH = "elastic:6180769d-729b-a7b3-08c0eb"


def required(name):
    value = os.environ.get(name, '')
    if not value:
        raise SystemExit(f'缺少参数 {name}')
    return value


def names(name):
    values = json.loads(required(name))
    if not isinstance(values, list) or not values or any(
        not isinstance(value, str) or not re.fullmatch(r'[a-z][a-z0-9_-]{0,127}', value)
        for value in values
    ):
        raise SystemExit(f'{name} 必须是非空精确索引名 JSON 数组，不允许通配符')
    return list(dict.fromkeys(values))


def main():
    if required('ARTE_RESET_SEARCH_CONFIRM') != 'RESET_OLD_SEARCH_GENERATIONS':
        raise SystemExit('搜索重置确认值不匹配')
    if required('ARTE_RESET_REDIS_NAMESPACE_EXCLUSIVE') != 'YES':
        raise SystemExit('必须确认旧 Redis 搜索命名空间仅属于本次待重置环境')
    origin = required('ARTE_RESET_ES_URL').rstrip('/')
    parsed = urlparse(origin)
    if parsed.scheme not in ('http', 'https') or not parsed.hostname or parsed.username or parsed.password or parsed.query or parsed.fragment or parsed.path:
        raise SystemExit('ES_URL 必须是无凭据、无路径的明确 HTTP(S) origin；认证在文件顶部或 curl 配置中设置')
    old = names('ARTE_RESET_ES_OLD_GENERATIONS')
    new = required('ARTE_RESET_ES_NEW_GENERATION')
    read_alias = required('ARTE_RESET_ES_READ_ALIAS')
    write_alias = required('ARTE_RESET_ES_WRITE_ALIAS')
    if any(not re.fullmatch(r'[a-z][a-z0-9_-]{0,127}', value) for value in [new, read_alias, write_alias]):
        raise SystemExit('新代际及别名格式不正确')
    if len({new, read_alias, write_alias}) != 3 or new in old or set(old) & {read_alias, write_alias}:
        raise SystemExit('新旧物理代际和读写别名必须分离')
    namespace = required('ARTE_RESET_REDIS_OLD_NAMESPACE')
    new_namespace = required('ARTE_RESET_REDIS_NEW_NAMESPACE')
    if any(not re.fullmatch(r'[A-Za-z0-9:_-]{1,128}', value) for value in [namespace, new_namespace]) or namespace == new_namespace or namespace.startswith(new_namespace + ':') or new_namespace.startswith(namespace + ':'):
        raise SystemExit('必须明确配置不同且互不包含的新旧 Redis 搜索命名空间')
    redis_args = json.loads(os.environ.get('ARTE_RESET_REDIS_ARGS_JSON', '[]'))
    if not isinstance(redis_args, list) or any(not isinstance(value, str) for value in redis_args):
        raise SystemExit('REDIS_ARGS_JSON 必须是 redis-cli 连接参数数组；密码用 REDISCLI_AUTH')
    # 不接受在参数中替换命令或扩大扫描范围。
    pairs = {'-h', '-p', '-n', '-s', '--cacert', '--cert', '--key', '--user'}
    switches = {'--tls', '--no-auth-warning'}
    index = 0
    while index < len(redis_args):
        if redis_args[index] in pairs and index + 1 < len(redis_args):
            index += 2
        elif redis_args[index] in switches:
            index += 1
        else:
            raise SystemExit('Redis 参数只接受主机、端口、库号、socket、TLS 及用户名；不接受命令或密码参数')
    curl = ['curl']
    config = os.environ.get('ARTE_RESET_ES_CURL_CONFIG')
    if config:
        curl += ['--config', config]
    if ES_BASIC_AUTH:
        curl += ['--user', ES_BASIC_AUTH]
    curl += ['--silent', '--show-error', '--no-location', '--no-insecure', '--connect-timeout', '10', '--max-time', '60']

    def es(method, path, body=None, allow_missing=False):
        command = curl + ['--request', method, '--url', origin + path, '--write-out', '\n%{http_code}']
        if body is not None:
            command += ['--header', 'Content-Type: application/json', '--data-binary', '@-']
        result = subprocess.run(command, input=None if body is None else json.dumps(body),
                                text=True, capture_output=True, check=False)
        if result.returncode != 0:
            raise SystemExit(f'ES {method} {path} 的 curl 执行失败，退出码 {result.returncode}')
        raw, code = result.stdout.rsplit('\n', 1)
        if code == '404' and allow_missing:
            return None
        if not code.startswith('2'):
            raise SystemExit(f'ES {method} {path} 失败，HTTP {code}；停止，保留其余数据')
        return json.loads(raw)

    # 所有范围核验先于第一次删除；不输出认证材料、Redis 值或 PIT ID。
    if es('GET', '/' + quote(new), allow_missing=True) is not None:
        raise SystemExit('新物理代际已存在，请选择一个尚未使用的新名称')
    for alias in [read_alias, write_alias]:
        mapping = es('GET', '/_alias/' + quote(alias), allow_missing=True) or {}
        if set(mapping) - set(old):
            raise SystemExit('别名还指向未列入重置范围的索引，停止')
    for generation in old:
        mapping = es('GET', '/' + quote(generation) + '/_alias', allow_missing=True) or {}
        for value in mapping.values():
            if set(value.get('aliases', {})) - {read_alias, write_alias}:
                raise SystemExit('旧代际还有其他别名使用，停止')
    redis = ['redis-cli'] + redis_args
    if subprocess.run(redis + ['--raw', 'PING'], text=True, capture_output=True, check=True).stdout.strip() != 'PONG':
        raise SystemExit('Redis 连接核验失败')
    keys = list(dict.fromkeys(subprocess.run(redis + ['--raw', '--scan', '--pattern', namespace + ':*'],
                          text=True, capture_output=True, check=True).stdout.splitlines()))
    if any(not key.startswith(namespace + ':') for key in keys):
        raise SystemExit('Redis 扫描返回异常结果，停止')
    new_keys = subprocess.run(redis + ['--raw', '--scan', '--pattern', new_namespace + ':*'],
                              text=True, capture_output=True, check=True).stdout.strip()
    if new_keys:
        raise SystemExit('新 Redis 搜索命名空间已有记录，请选择尚未使用的新名称')
    pit_ids = json.loads(os.environ.get('ARTE_RESET_ES_OLD_PIT_IDS_JSON', '[]'))
    if not isinstance(pit_ids, list) or any(not isinstance(value, str) or not value for value in pit_ids):
        raise SystemExit('OLD_PIT_IDS_JSON 必须是已确认旧 PIT 的 ID 数组')
    if pit_ids:
        es('DELETE', '/_pit', {'id': pit_ids})
    # 未提供的旧 PIT 由服务端 keep_alive 到期释放；新命名空间使旧应用游标失效。
    for generation in old:
        es('DELETE', '/' + quote(generation), allow_missing=True)
    for key in keys:
        result = subprocess.run(redis + ['--raw', 'UNLINK', key], text=True, capture_output=True, check=True)
        if not result.stdout.strip().isdigit():
            raise SystemExit('Redis UNLINK 失败；停止，检查实际完成范围')
    for alias in [read_alias, write_alias]:
        if es('GET', '/_alias/' + quote(alias), allow_missing=True) is not None:
            raise SystemExit('旧别名仍存在，请检查后再开放搜索')
    print(f'SEARCH_RESET_OK: 旧代际 {len(old)} 个，旧命名空间键 {len(keys)} 个；尚未创建新索引。')
    print('启动前设置新的 sync.generation、session.namespace 和 initialize-on-startup；核验新别名及登记后再开放查询。')


if __name__ == '__main__':
    main()
