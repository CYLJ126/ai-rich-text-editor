#!/usr/bin/env python3
"""隔离验证官方 Nginx 镜像的 SSE 首段交付及 HTTP 上游连接复用，不调用业务服务。"""
import http.client
import http.server
import json
import os
from pathlib import Path
import socket
import subprocess
import threading
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / 'deploy/frontend/15-backend-upstream.envsh'

for origin, expected in [('http://backend:12636', 0), ('https://backend:443/', 0),
                         ('http://[::1]:12636', 0), ('http://user:secret@backend', 1),
                         ('http://backend/path', 1), ('http://backend;invalid', 1),
                         ('http://2001:db8::1', 1)]:
    result = subprocess.run(['sh', '-c', '. "$1"', 'sh', str(SCRIPT)],
                            env={**os.environ, 'NGINX_BACKEND_UPSTREAM': origin}, capture_output=True)
    assert (result.returncode != 0) == (expected != 0), '上游 origin 校验错误'

for origin, authority in [('https://backend', 'backend:443'), ('http://backend', 'backend:80'),
                          ('https://[::1]', '[::1]:443'), ('https://backend:8443', 'backend:8443')]:
    result = subprocess.run(['sh', '-c', '. "$1"; printf "%s" "$NGINX_BACKEND_AUTHORITY"', 'sh', str(SCRIPT)],
                            env={**os.environ, 'NGINX_BACKEND_UPSTREAM': origin}, capture_output=True, check=True)
    assert result.stdout.decode() == authority, '上游默认端口错误'

ports, encodings = [], []

class Provider(http.server.BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def log_message(self, *_): pass
    def do_GET(self):
        self.send_response(200); self.send_header('Content-Length', '2'); self.end_headers()
        self.wfile.write(b'ok'); self.wfile.flush()
    def do_POST(self):
        self.rfile.read(int(self.headers.get('Content-Length', 0)))
        ports.append(self.client_address[1]); encodings.append(self.headers.get('Accept-Encoding'))
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Transfer-Encoding', 'chunked')
        self.end_headers()
        def chunk(body):
            self.wfile.write(f'{len(body):x}\r\n'.encode() + body + b'\r\n'); self.wfile.flush()
        chunk(b'data: first\n\n')
        time.sleep(1.5)
        chunk(b'data: last\n\n')
        self.wfile.write(b'0\r\n\r\n'); self.wfile.flush()

server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Provider)
threading.Thread(target=server.serve_forever, daemon=True).start()
container = None
client = None
try:
    cmd = ['docker', 'run', '-d', '--rm', '--name', 'arte-ai-stream-test-' + uuid.uuid4().hex[:10],
           '-p', '127.0.0.1::8080',
           '-v', f'{ROOT / "deploy/frontend/nginx.conf.template"}:/etc/nginx/templates/default.conf.template:ro',
           '-v', f'{SCRIPT}:/docker-entrypoint.d/15-backend-upstream.envsh:ro']
    env = {'NGINX_BACKEND_UPSTREAM': f'http://host.docker.internal:{server.server_port}',
           'NGINX_SERVER_NAME': '_', 'NGINX_CLIENT_MAX_BODY_SIZE': '1m',
           'NGINX_DRAWIO_UPSTREAM': 'http://127.0.0.1:9', 'NGINX_ROOT_REDIRECT': '/test',
           'NGINX_SPA_FALLBACK': '/index.html'}
    for key, value in env.items(): cmd += ['-e', f'{key}={value}']
    container = subprocess.check_output(cmd + ['nginx:1.27-alpine'], text=True).strip()
    port = int(subprocess.check_output(['docker', 'port', container, '8080'], text=True).strip().rsplit(':', 1)[1])
    end = time.monotonic() + 10
    while True:
        try:
            probe = http.client.HTTPConnection('127.0.0.1', port, timeout=.5)
            probe.request('GET', '/arte/ai-new/health')
            ready = probe.getresponse(); ready.read(); probe.close()
            if ready.status == 200: break
        except (OSError, http.client.HTTPException):
            if time.monotonic() > end: raise RuntimeError('临时 Nginx 未就绪')
            time.sleep(.1)
    client = http.client.HTTPConnection('127.0.0.1', port, timeout=5)
    measurements = []
    for _ in range(2):
        began = time.monotonic()
        client.request('POST', '/arte/ai-new/invocation/watchInvocation', '{}',
                       {'Content-Type': 'application/json', 'Accept-Encoding': 'gzip'})
        response = client.getresponse(); assert response.status == 200
        frame = b''
        while not frame.endswith(b'\n\n'): frame += response.read(1)
        first = (time.monotonic() - began) * 1000
        assert frame == b'data: first\n\n'; assert first < 1000, '首段被代理缓冲'
        assert response.getheader('Content-Encoding') is None
        assert b'data: last' in response.read()
        measurements.append({'firstFrameMs': round(first, 2), 'totalMs': round((time.monotonic() - began) * 1000, 2)})
    assert len(ports) == 2 and ports[0] == ports[1], '上游 TCP 未复用'
    assert encodings == ['identity', 'identity']
    print(json.dumps({'proxy': 'nginx:1.27-alpine', 'syntheticTailDelayMs': 1500,
                      'upstreamTcpReused': True, 'gzipDisabled': True, 'samples': measurements}))
except Exception:
    if container:
        logs = subprocess.run(['docker', 'logs', '--tail', '20', container], capture_output=True, text=True)
        import sys
        print(logs.stdout + logs.stderr, file=sys.stderr)
    raise
finally:
    if client: client.close()
    if container: subprocess.run(['docker', 'stop', '-t', '1', container], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    server.shutdown(); server.server_close()
