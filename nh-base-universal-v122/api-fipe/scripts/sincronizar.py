"""Obtém snapshots mensais FIPEX via GitHub (e Git LFS em Hugging Face).

O endpoint de releases do GitHub fala JSON; arquivos CSV são baixados
separadamente. Caso o GitHub devolva um ponteiro LFS em vez do conteúdo,
resolve o objeto no armazenamento LFS oficial indicado em .lfsconfig.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scripts.importar import importar  # noqa: E402

GITHUB_RELEASE = 'https://api.github.com/repos/fipex-labs/dataset/releases/latest'
GITHUB_MEDIA = 'https://media.githubusercontent.com/media/fipex-labs/dataset'
GITHUB_RAW = 'https://raw.githubusercontent.com/fipex-labs/dataset'
LFS_BATCH = 'https://huggingface.co/datasets/alanwgt/fipex-veiculos-brasil.git/info/lfs/objects/batch'
AGENTE = 'FIPE-Independente/1.2 (+https://github.com/fipex-labs/dataset)'
MAX_BYTES = 100 * 1024 * 1024
PONTEIRO = b'version https://git-lfs.github.com/spec/v1'


def request(url: str, *, accept: str = '*/*', data: bytes | None = None,
            content_type: str | None = None, extra_headers: dict | None = None) -> Request:
    """Cada destino recebe o Accept correto; não forçar octet-stream no JSON."""
    headers = {'User-Agent': AGENTE, 'Accept': accept}
    if content_type:
        headers['Content-Type'] = content_type
    if extra_headers:
        headers.update(extra_headers)
    return Request(url, headers=headers, data=data)


@dataclass(frozen=True)
class Release:
    tag: str
    ano: int
    mes: int


def obter_ultima_release() -> Release:
    with urlopen(request(GITHUB_RELEASE, accept='application/vnd.github+json'), timeout=30) as resposta:
        info = json.load(resposta)
    tag = info.get('tag_name', '')
    match = re.fullmatch(r'v(20\d{2})\.(0[1-9]|1[0-2])\.(\d+)', tag)
    if not match:
        raise RuntimeError(f'Tag de release inesperada: {tag!r}')
    return Release(tag=tag, ano=int(match.group(1)), mes=int(match.group(2)))


def ultima_referencia() -> tuple[int, int]:
    release = obter_ultima_release()
    return release.ano, release.mes


def _download(url: str, destino: Path, *, headers: dict | None = None,
              sha256: str | None = None, size: int | None = None) -> None:
    """Streaming limitado e atômico: só move para destino depois de validar."""
    total = 0
    digest = hashlib.sha256()
    temp = destino.with_name(destino.name + '.part')
    try:
        with urlopen(request(url, extra_headers=headers), timeout=120) as origem, temp.open('wb') as saida:
            while True:
                pedaco = origem.read(1024 * 1024)
                if not pedaco:
                    break
                total += len(pedaco)
                if total > MAX_BYTES:
                    raise ValueError('Download excedeu o limite de 100 MiB.')
                saida.write(pedaco)
                digest.update(pedaco)
        if total == 0:
            raise ValueError('Download vazio.')
        if size is not None and total != size:
            raise ValueError(f'Download incompleto: {total} bytes recebidos, esperado {size}.')
        if sha256 is not None and digest.hexdigest() != sha256:
            raise ValueError('Checksum SHA-256 do objeto FIPEX não confere.')
        temp.replace(destino)
    finally:
        temp.unlink(missing_ok=True)


def _parse_pointer(data: bytes) -> tuple[str, int]:
    if not data.startswith(PONTEIRO):
        raise ValueError('Resposta do GitHub não é CSV nem ponteiro Git LFS válido.')
    texto = data.decode('ascii', errors='strict')
    hash_match = re.search(r'^oid sha256:([0-9a-f]{64})$', texto, flags=re.M)
    size_match = re.search(r'^size ([0-9]+)$', texto, flags=re.M)
    if not hash_match or not size_match:
        raise ValueError('Ponteiro Git LFS sem SHA-256 ou tamanho válido.')
    tamanho = int(size_match.group(1))
    if not 1 <= tamanho <= MAX_BYTES:
        raise ValueError('Tamanho do objeto LFS fora do limite de segurança.')
    return hash_match.group(1), tamanho


def _download_lfs(destino: Path, ponteiro: bytes) -> None:
    oid, tamanho = _parse_pointer(ponteiro)
    payload = json.dumps({
        'operation': 'download', 'transfers': ['basic'],
        'objects': [{'oid': oid, 'size': tamanho}]
    }).encode('utf-8')
    r = request(LFS_BATCH, accept='application/vnd.git-lfs+json',
                content_type='application/vnd.git-lfs+json', data=payload)
    with urlopen(r, timeout=30) as resposta:
        doc = json.load(resposta)
    objetos = doc.get('objects') or []
    if len(objetos) != 1 or objetos[0].get('oid') != oid:
        raise RuntimeError('Servidor LFS retornou dados inconsistentes.')
    obj = objetos[0]
    if obj.get('error'):
        raise RuntimeError(f'Erro do armazenamento Git LFS: {obj["error"]}')
    action = (obj.get('actions') or {}).get('download') or {}
    href = action.get('href', '')
    parts = urlsplit(href)
    dominio = (parts.hostname or '').lower()
    # Impede requisições a localhost/rede interna usando links retornados pela API.
    dominios = ('huggingface.co', 'hf.co', 'amazonaws.com', 'cloudfront.net', 'googleapis.com')
    if (parts.scheme != 'https' or parts.username or parts.password or
            not any(dominio == d or dominio.endswith('.' + d) for d in dominios)):
        raise ValueError('URL de download Git LFS não permitida.')
    headers = action.get('header') or {}
    if not isinstance(headers, dict):
        raise ValueError('Cabeçalhos LFS inválidos.')
    _download(href, destino, headers=headers, sha256=oid, size=tamanho)


def baixar_mensal(ano: int, mes: int, destino: Path, *, ref: str = 'main') -> None:
    if not 2000 <= ano <= 2200 or not 1 <= mes <= 12:
        raise ValueError('Referência inválida')
    if ref != 'main' and not re.fullmatch(r'v20\d{2}\.(0[1-9]|1[0-2])\.\d+', ref):
        raise ValueError('Tag de download inválida')
    caminho = f'{ref}/{ano}/{mes:02d}/fipex-prices.csv'
    url = f'{GITHUB_MEDIA}/{caminho}'
    try:
        _download(url, destino)
        with destino.open('rb') as f:
            inicio = f.read(256)
        if not inicio.startswith(PONTEIRO):
            return  # GitHub já entregou o CSV real.
        ponteiro = destino.read_bytes()
    except HTTPError as e:
        if e.code not in (403, 404, 415):
            raise
        # Algumas configurações Git LFS deixam apenas o ponteiro no GitHub.
        destino.unlink(missing_ok=True)
        with urlopen(request(f'{GITHUB_RAW}/{caminho}'), timeout=30) as resp:
            ponteiro = resp.read(2048)
    # .lfsconfig aponta para o armazenamento Hugging Face do próprio FIPEX.
    _download_lfs(destino, ponteiro)
    with destino.open('rb') as f:
        if f.read(128).startswith(PONTEIRO):
            raise RuntimeError('O armazenamento devolveu o ponteiro LFS, não o CSV real.')


def main():
    p = argparse.ArgumentParser(description='Atualiza preços FIPE pelo dataset FIPEX (snapshot mensal)')
    p.add_argument('--ano', type=int, help='Ano de referência fixo; requer --mes')
    p.add_argument('--mes', type=int, help='Mês de referência fixo; requer --ano')
    p.add_argument('--substituir', action='store_true', help='Substitui todo o banco de preços; padrão preserva histórico')
    args = p.parse_args()
    if (args.ano is None) != (args.mes is None):
        p.error('--ano e --mes devem ser informados juntos')
    ano, mes = (args.ano, args.mes) if args.ano else ultima_referencia()
    print(f'Baixando snapshot FIPEX {ano}-{mes:02d}...')
    with tempfile.TemporaryDirectory(prefix='fipex-') as temp:
        arquivo = Path(temp) / 'fipex-prices.csv'
        baixar_mensal(ano, mes, arquivo)
        print(f'Arquivo recebido: {arquivo.stat().st_size} bytes. Importando...')
        print(importar(arquivo, substituir=args.substituir))
    print('Concluído. API consultará o banco SQLite local.')


if __name__ == '__main__':
    main()
