"""Testa agendamento/sincronização SEM INTERNET; snapshots de testes são fictícios."""
from __future__ import annotations

import csv
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.database import COLUNAS, conexao
from app.main import app
from app.sync_state import status
from scripts import autoatualizar as worker
from scripts.sincronizar import Release


def arquivo_mes(destino, registros, ano, mes, *, remover_codigo=None, invalido=False):
    linhas = [dict(r, ano_referencia=str(ano), mes_referencia=str(mes)) for r in registros
             if r['codigo_fipe'] != remover_codigo]
    if invalido:
        linhas.append({**linhas[0], 'mes_referencia': str(1 if mes != 1 else 2)})
    with Path(destino).open('w', newline='', encoding='utf-8') as f:
        w = csv.DictWriter(f, delimiter='\t', fieldnames=COLUNAS)
        w.writeheader()
        w.writerows(linhas)


def fontes(monkeypatch, registros, *, tag='v2026.10.0', erro=False, remover_codigo=None, invalido=False):
    ano, mes = map(int, tag[1:].split('.')[:2])
    monkeypatch.setattr(worker, 'obter_ultima_release', lambda: Release(tag, ano, mes))
    chamados = []

    def baixar(ano, mes, caminho, *, ref='main'):
        chamados.append((ano, mes, ref))
        if erro:
            raise ConnectionError('Falha simulada na conexão FIPEX')
        arquivo_mes(caminho, registros, ano, mes, remover_codigo=remover_codigo, invalido=invalido)
    monkeypatch.setattr(worker, 'baixar_mensal', baixar)
    return chamados


def test_primeiro_bootstrap_e_sem_repetir(base_populada, monkeypatch, registros):
    # Já existe base com outubro; simula o app v1, ainda sem sincronização registrada.
    monkeypatch.setenv('AUTO_SYNC_MIN_REGISTROS', '3')
    chamados = fontes(monkeypatch, registros)
    retorno = worker.sincronizar_uma_vez()
    assert retorno['resultado'] == 'importado'
    assert chamados == [(2026, 10, 'v2026.10.0')]
    s = status()
    assert s['estado'] == 'atualizado'
    assert s['versao_instalada'] == 'v2026.10.0'
    assert s['verificado_em'] and s['atualizado_em'] and s['concluido_em']
    assert s['referencia_local'] == {'ano': 2026, 'mes': 10}
    assert worker.sincronizar_uma_vez()['resultado'] == 'sem_novidades'
    assert len(chamados) == 1
    with conexao() as c:
        assert c.execute('SELECT COUNT(*) FROM precos').fetchone()[0] == 9


def test_falha_download_nao_remove_banco(base_populada, monkeypatch, registros):
    monkeypatch.setenv('AUTO_SYNC_MIN_REGISTROS', '3')
    fontes(monkeypatch, registros, tag='v2026.11.0', erro=True)
    with pytest.raises(ConnectionError):
        worker.sincronizar_uma_vez()
    assert status()['estado'] == 'erro'
    assert 'Falha simulada' in status()['erro']
    assert status()['referencia_local'] == {'ano': 2026, 'mes': 10}
    with conexao() as c:
        assert c.execute('SELECT COUNT(*) FROM precos').fetchone()[0] == 9


def test_falha_validacao_reverte_toda_importacao(base_populada, monkeypatch, registros):
    monkeypatch.setenv('AUTO_SYNC_MIN_REGISTROS', '3')
    fontes(monkeypatch, registros, tag='v2026.10.0', invalido=True)
    with pytest.raises(ValueError, match='suspeito'):
        worker.sincronizar_uma_vez()
    assert status()['estado'] == 'erro'
    with conexao() as c:
        assert c.execute('SELECT COUNT(*) FROM precos').fetchone()[0] == 9


def test_republicacao_do_mes_substitui_apenas_mes(base_populada, monkeypatch, registros):
    monkeypatch.setenv('AUTO_SYNC_MIN_REGISTROS', '3')
    fontes(monkeypatch, registros, tag='v2026.10.0')
    worker.sincronizar_uma_vez()
    fontes(monkeypatch, registros, tag='v2026.10.1', remover_codigo='444444-4')
    worker.sincronizar_uma_vez()
    with conexao() as c:
        assert c.execute('SELECT COUNT(*) FROM precos').fetchone()[0] == 8
        assert c.execute('SELECT COUNT(*) FROM precos WHERE codigo_fipe=?', ('444444-4',)).fetchone()[0] == 0
        assert c.execute('SELECT COUNT(*) FROM precos WHERE mes_referencia=9').fetchone()[0] == 1
    assert status()['versao_instalada'] == 'v2026.10.1'


def test_recupera_meses_em_atraso(base_populada, monkeypatch, registros):
    monkeypatch.setenv('AUTO_SYNC_MIN_REGISTROS', '3')
    chamados = fontes(monkeypatch, registros, tag='v2026.12.0')
    r = worker.sincronizar_uma_vez()
    assert r['resultado'] == 'importado'
    assert chamados == [(2026, 11, 'v2026.12.0'), (2026, 12, 'v2026.12.0')]
    with conexao() as c:
        assert {r[0] for r in c.execute('SELECT DISTINCT mes_referencia FROM precos')} == {9, 10, 11, 12}
    assert status()['referencia_local'] == {'ano': 2026, 'mes': 12}


def test_status_auth_e_painel(base_populada, monkeypatch):
    with TestClient(app) as client:
        s = client.get('/sincronizacao/status')
        assert s.status_code == 200
        assert s.json()['referencia_local'] == {'ano': 2026, 'mes': 10}
        page = client.get('/painel/sincronizacao')
        assert page.status_code == 200
        assert 'Última verificação' in page.text
        monkeypatch.setenv('API_KEY', 'senha-super-secreta')
        assert client.get('/sincronizacao/status').status_code == 401
        assert client.get('/sincronizacao/status', headers={'X-API-Key': 'senha-super-secreta'}).status_code == 200


def test_mes_entre_anos():
    assert list(worker.meses_entre((2026, 11), (2027, 2))) == [
        (2026, 12), (2027, 1), (2027, 2)]


def test_arquivo_truncado_rejeitado_e_banco_preservado(base_populada, monkeypatch, registros):
    monkeypatch.setenv('AUTO_SYNC_MIN_REGISTROS', '1')
    monkeypatch.setenv('AUTO_SYNC_MIN_RATIO', '0.70')
    fontes(monkeypatch, registros, tag='v2026.10.0', remover_codigo='111111-1')
    # Um arquivo quase vazio, mas com linhas válidas, não deve causar a deleção da base.
    def incompleto(ano, mes, caminho, *, ref):
        arquivo_mes(caminho, registros[:1], ano, mes)
    monkeypatch.setattr(worker, 'baixar_mensal', incompleto)
    with pytest.raises(ValueError, match='truncado'):
        worker.sincronizar_uma_vez()
    with conexao() as c:
        assert c.execute('SELECT COUNT(*) FROM precos').fetchone()[0] == 9
    assert status()['estado'] == 'erro'


def test_bootstrap_sem_banco(tmp_path, monkeypatch, registros):
    monkeypatch.setenv('DATABASE_PATH', str(tmp_path / 'novabase.sqlite3'))
    monkeypatch.setenv('AUTO_SYNC_MIN_REGISTROS', '3')
    chamados = fontes(monkeypatch, registros, tag='v2026.10.0')
    assert status()['estado'] == 'nao_iniciado'
    assert worker.sincronizar_uma_vez()['resultado'] == 'importado'
    assert chamados == [(2026, 10, 'v2026.10.0')]
    with conexao() as c:
        assert c.execute('PRAGMA journal_mode').fetchone()[0] == 'wal'
        assert c.execute('SELECT COUNT(*) FROM precos').fetchone()[0] == 8
