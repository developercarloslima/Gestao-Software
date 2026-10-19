"""Regressão do HTTP 415, resolução LFS e validação SHA (sem internet)."""
import hashlib
import io
import json
from urllib.error import HTTPError

import pytest

from scripts import sincronizar as sync


class Resposta(io.BytesIO):
    def __enter__(self):
        return self
    def __exit__(self, *_):
        self.close()


def pointer(payload):
    return (b'version https://git-lfs.github.com/spec/v1\n'
            + b'oid sha256:' + hashlib.sha256(payload).hexdigest().encode() + b'\n'
            + f'size {len(payload)}\n'.encode())


def test_release_api_usar_accept_json_nao_octet_stream(monkeypatch):
    def endpoint(req, timeout):
        assert req.full_url == sync.GITHUB_RELEASE
        assert req.get_header('Accept') == 'application/vnd.github+json'
        assert req.get_header('User-agent')
        return Resposta(b'{"tag_name":"v2026.10.0"}')
    monkeypatch.setattr(sync, 'urlopen', endpoint)
    release = sync.obter_ultima_release()
    assert (release.ano, release.mes, release.tag) == (2026, 10, 'v2026.10.0')


def test_recebe_csv_direto_sem_lfs(tmp_path, monkeypatch):
    data = b'campo\tvalor\nmarca\tmodelo\n'
    def endpoint(req, timeout):
        assert 'media.githubusercontent.com' in req.full_url
        assert req.get_header('Accept') == '*/*'
        return Resposta(data)
    monkeypatch.setattr(sync, 'urlopen', endpoint)
    destino = tmp_path / 'fipe.csv'
    sync.baixar_mensal(2026, 10, destino)
    assert destino.read_bytes() == data


def test_ponteiro_git_lfs_resolve_via_huggingface(tmp_path, monkeypatch):
    data = b'tipo_veiculo\tcodigo_fipe\nCarros\t123456-7\n'
    pont = pointer(data)
    download = 'https://cas-bridge.xethub.hf.co/link-assinado'
    chamadas = []
    def endpoint(req, timeout):
        chamadas.append(req.full_url)
        if 'media.githubusercontent.com' in req.full_url:
            return Resposta(pont)
        if req.full_url == sync.LFS_BATCH:
            assert req.get_header('Accept') == 'application/vnd.git-lfs+json'
            assert req.get_header('Content-type') == 'application/vnd.git-lfs+json'
            req_json = json.loads(req.data)
            assert req_json['objects'][0]['oid'] == hashlib.sha256(data).hexdigest()
            obj = {'oid': hashlib.sha256(data).hexdigest(), 'size': len(data),
                   'actions': {'download': {'href': download}}}
            return Resposta(json.dumps({'objects': [obj]}).encode())
        if req.full_url == download:
            return Resposta(data)
        raise AssertionError(req.full_url)
    monkeypatch.setattr(sync, 'urlopen', endpoint)
    destino = tmp_path / 'fipe.csv'
    sync.baixar_mensal(2026, 10, destino, ref='v2026.10.0')
    assert destino.read_bytes() == data
    assert len(chamadas) == 3


def test_media_404_usa_ponteiro_raw(tmp_path, monkeypatch):
    data = b'csv-real\n'
    pont = pointer(data)
    def endpoint(req, timeout):
        if 'media.githubusercontent.com' in req.full_url:
            raise HTTPError(req.full_url, 404, 'not found', {}, None)
        if 'raw.githubusercontent.com' in req.full_url:
            return Resposta(pont)
        if req.full_url == sync.LFS_BATCH:
            obj = {'oid': hashlib.sha256(data).hexdigest(), 'actions': {'download': {
                'href': 'https://cdn-lfs.huggingface.co/arquivo'}}}
            return Resposta(json.dumps({'objects': [obj]}).encode())
        return Resposta(data)
    monkeypatch.setattr(sync, 'urlopen', endpoint)
    destino = tmp_path / 'fipe.csv'
    sync.baixar_mensal(2026, 10, destino)
    assert destino.read_bytes() == data


def test_download_sha_incorreto_nao_deixa_arquivo_indevido(tmp_path, monkeypatch):
    correto = b'precos-reais\n'
    ponteiro = pointer(correto)
    def endpoint(req, timeout):
        if 'media.githubusercontent.com' in req.full_url:
            return Resposta(ponteiro)
        if req.full_url == sync.LFS_BATCH:
            obj = {'oid': hashlib.sha256(correto).hexdigest(), 'actions': {'download': {
                'href': 'https://cdn-lfs.huggingface.co/arquivo'}}}
            return Resposta(json.dumps({'objects': [obj]}).encode())
        return Resposta(b'conteudo-falso\n')
    monkeypatch.setattr(sync, 'urlopen', endpoint)
    destino = tmp_path / 'fipe.csv'
    with pytest.raises(ValueError, match='(Checksum|incompleto)'):
        sync.baixar_mensal(2026, 10, destino)
    assert not destino.with_suffix('.csv.part').exists()


def test_url_lfs_interna_rejeitada(tmp_path, monkeypatch):
    data = b'preco\n'
    def endpoint(req, timeout):
        if 'media.githubusercontent.com' in req.full_url:
            return Resposta(pointer(data))
        return Resposta(json.dumps({'objects': [{'oid': hashlib.sha256(data).hexdigest(),
                        'actions': {'download': {'href': 'http://127.0.0.1:8080/private'}}}]}).encode())
    monkeypatch.setattr(sync, 'urlopen', endpoint)
    with pytest.raises(ValueError, match='não permitida'):
        sync.baixar_mensal(2026, 10, tmp_path / 'fipe.csv')


def test_referencia_invalida_rejeitada(tmp_path):
    with pytest.raises(ValueError, match='inválida'):
        sync.baixar_mensal(2026, 13, tmp_path / 'x.csv')
    with pytest.raises(ValueError, match='inválida'):
        sync.baixar_mensal(2026, 10, tmp_path / 'x.csv', ref='../../etc')
