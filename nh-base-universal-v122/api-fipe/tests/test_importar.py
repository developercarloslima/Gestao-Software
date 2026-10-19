from pathlib import Path
import csv
import pytest

from scripts.importar import importar, converter
from scripts.sincronizar import ultima_referencia
from app.database import COLUNAS


def test_reimportar_nao_duplica(base_populada):
    _, arquivo = base_populada
    assert importar(arquivo)['linhas_validas'] == 9
    from app.database import conexao
    with conexao() as c:
        assert c.execute('SELECT COUNT(*) FROM precos').fetchone()[0] == 9


def test_lfs_ponteiro_rejeitado(tmp_path, monkeypatch):
    monkeypatch.setenv('DATABASE_PATH', str(tmp_path/'fipe.sqlite3'))
    caminho = tmp_path/'lfs.csv'
    caminho.write_text('version https://git-lfs.github.com/spec/v1\noid sha256:123\nsize 5\n')
    with pytest.raises(ValueError, match='ponteiro'):
        importar(caminho)


def test_arquivo_nao_tsv_rejeitado(tmp_path, monkeypatch):
    monkeypatch.setenv('DATABASE_PATH', str(tmp_path/'fipe.sqlite3'))
    caminho = tmp_path/'qualquer.csv'
    caminho.write_text('marca,modelo,ano\nToyota,Corolla,2022\n')
    with pytest.raises(ValueError, match='12 colunas'):
        importar(caminho)


def test_registro_invalido_ignorado(base_populada, tmp_path):
    _, caminho = base_populada
    with caminho.open('a', encoding='utf-8') as f:
        f.write('\t'.join(['Carros','123','Teste','Fiat','Flex','f','2020','hmm','14000','R$ 140,00','10','2026'])+'\n')
    r = importar(caminho)
    assert r['linhas_validas'] == 9
    assert r['linhas_ignoradas'] == 1


def test_importacao_vazia_nao_destroi_banco(base_populada):
    _, caminho = base_populada
    with caminho.open('w') as f:
        f.write('\t'.join(COLUNAS)+'\n')
    with pytest.raises(ValueError, match='Nenhum registro'):
        importar(caminho, substituir=True)
    from app.database import conexao
    with conexao() as c:
        assert c.execute('SELECT COUNT(*) FROM precos').fetchone()[0] == 9
