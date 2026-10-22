#!/usr/bin/env python3
"""Serve o build e a área administrativa com proxy da API exclusivamente local."""
from http.client import HTTPConnection
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / 'web'
HOP_HEADERS = {'connection', 'keep-alive', 'proxy-authenticate', 'proxy-authorization',
               'te', 'trailer', 'transfer-encoding', 'upgrade'}


class Handler(SimpleHTTPRequestHandler):
    def translate_path(self, path):
        path = unquote(urlsplit(path).path).lstrip('/')
        base = WEB if path.split('/')[0] in {'admin', 'contract', 'shared'} else WEB / 'site-src/dist'
        if path.startswith('assets/') and not (base / path).is_file():
            base = WEB
        target = (base / path).resolve()
        if not target.is_relative_to(base.resolve()):
            return str(WEB / '__invalid_path__')
        return str(target)

    def do_GET(self):
        if self.path.startswith('/api/'):
            self.proxy_api()
        else:
            super().do_GET()

    def proxy_api(self):
        connection = HTTPConnection('127.0.0.1', 8080, timeout=30)
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if length < 0 or length > 30 * 1024 * 1024 or self.headers.get('Transfer-Encoding'):
                self.send_error(413)
                return
            headers = {k: v for k, v in self.headers.items() if k.lower() not in HOP_HEADERS}
            connection.request(self.command, self.path, self.rfile.read(length), headers)
            response = connection.getresponse()
            body = response.read()
            self.send_response(response.status)
            for key, value in response.getheaders():
                if key.lower() not in HOP_HEADERS | {'content-length'}:
                    self.send_header(key, value)
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)
        except (OSError, ValueError):
            self.send_error(502, 'Backend local indisponível')
        finally:
            connection.close()

    def do_POST(self):
        if self.path.startswith('/api/'):
            self.proxy_api()
        else:
            self.send_error(405)

    do_PUT = do_PATCH = do_DELETE = do_OPTIONS = do_POST


if __name__ == '__main__':
    ThreadingHTTPServer(('127.0.0.1', 3100), Handler).serve_forever()
