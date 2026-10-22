#!/usr/bin/env python3
"""Smoke HTTP local, sem trocar senhas, criar registros ou acessar bancos externos."""
import json
import os
import re
from urllib.error import HTTPError
from urllib.request import ProxyHandler, Request, build_opener

BASE = 'http://127.0.0.1:3100'
HTTP = build_opener(ProxyHandler({}))
checks = 0


def request(path, expected=200, data=None, token=None):
    global checks
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    req = Request(BASE + path, json.dumps(data).encode() if data is not None else None, headers)
    try:
        response = HTTP.open(req, timeout=15)
    except HTTPError as exc:
        response = exc
    assert response.code == expected, f'{path}: HTTP {response.code}, esperado {expected}'
    checks += 1
    body = response.read()
    return json.loads(body) if 'application/json' in response.headers.get('Content-Type', '') else body


request('/api/health')
html = request('/').decode()
assert 'Gestão' in html
for asset in re.findall(r'(?:src|href)="(/assets/[^\"]+)"', html):
    request(asset)
request('/admin/login.html')
request('/admin/')
request('/api/auth/me', 403)
request('/api/platform/companies', 403)
request('/api/auth/me', 403, token='invalid-token')
for username, key in [('carlos.lima', 'GESTAO_BOOTSTRAP_CARLOS_PASSWORD'),
                      ('pedro.henrique', 'GESTAO_BOOTSTRAP_PEDRO_PASSWORD')]:
    secret = os.environ[key]
    result = request('/api/auth/login', data={'username': username, 'password': secret})
    assert result['username'] == username and result['role'] == 'PLATFORM_ADMIN'
    me = request('/api/auth/me', token=result['token'])
    assert me['username'] == username
    assert result['passwordChangeRequired'] == me['passwordChangeRequired']
    if me['passwordChangeRequired']:
        request('/api/platform/companies', 403, token=result['token'])
    request('/api/auth/login', 401, data={'username': username, 'password': 'invalid-' + secret})
    print(f'PASS: {username}: login, sessão, senha inválida e proteção de primeiro acesso')
print(f'PASS: {checks} requisições HTTP locais; nenhuma senha alterada')
