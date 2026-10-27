#!/usr/bin/env python3
"""Login real em loopback; não submete o formulário de troca de senha."""
import os
from playwright.sync_api import sync_playwright

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True, executable_path=os.environ.get('CHROMIUM_PATH', '/usr/bin/chromium'),
                                args=['--no-sandbox', '--disable-dev-shm-usage'])
    try:
        for username, key in [('carlos.lima', 'GESTAO_BOOTSTRAP_CARLOS_PASSWORD'),
                              ('pedro.henrique', 'GESTAO_BOOTSTRAP_PEDRO_PASSWORD')]:
            context = browser.new_context(viewport={'width': 1366, 'height': 900})
            # O teste só permite requisições para os serviços locais.
            context.route('**/*', lambda route: route.continue_() if route.request.url.startswith('http://127.0.0.1:3100/') else route.abort())
            page = context.new_page()
            errors = []
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.goto('http://127.0.0.1:3100/')
            page.locator('a[href="/admin/login.html"]').first.wait_for()
            page.goto('http://127.0.0.1:3100/admin/login.html')
            page.locator('#username').fill(username)
            page.locator('#password').fill(os.environ[key])
            page.locator('button[type=submit]').click()
            page.wait_for_url('**/admin/')
            page.locator('#password-dialog[open]').wait_for()
            assert not errors, 'Erro JavaScript na navegação administrativa'
            print(f'PASS navegador: {username}, login real e diálogo de troca obrigatória, sem alteração de senha')
            context.close()
    finally:
        browser.close()
