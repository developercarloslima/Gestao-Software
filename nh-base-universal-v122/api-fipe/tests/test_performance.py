"""Correção da camada de performance: cache, leituras concorrentes, migração."""
from concurrent.futures import ThreadPoolExecutor

from app.database import conexao, criar_schema
from app.performance import CacheConsultas, consulta_cacheada, conexao_leitura, cache


def test_lru_ttl_sem_dados_velhos():
    tempo = [10.]
    c = CacheConsultas(2, 2, clock=lambda: tempo[0])
    chamados = [0]

    def carregar():
        chamados[0] += 1
        return chamados[0]
    assert c.get_or_load(('x',), carregar) == 1
    assert c.get_or_load(('x',), carregar) == 1
    assert c.hits == 1
    tempo[0] = 12.
    assert c.get_or_load(('x',), carregar) == 2
    assert chamados[0] == 2


def test_cache_limite_memoria_e_desabilitado():
    c = CacheConsultas(2, 99, clock=lambda: 0.)
    for i in range(5):
        assert c.get_or_load((i,), lambda: i) == i
    assert len(c._items) == 2
    desligado = CacheConsultas(0, 99)
    assert desligado.get_or_load((0,), lambda: 11) == 11
    assert not desligado._items


def test_leitura_read_only_e_reaproveitada(base_populada):
    c1 = conexao_leitura()
    c2 = conexao_leitura()
    assert c1 is c2
    assert c1.execute('PRAGMA query_only').fetchone()[0] == 1
    assert c1.execute('SELECT COUNT(*) FROM precos').fetchone()[0] == 9


def test_cache_select_e_index_migracao(base_populada):
    cache.clear()
    with conexao(create=True) as c:
        criar_schema(c)
        nomes = {r[1] for r in c.execute('PRAGMA index_list(precos)')}
        assert {'ix_precos_consulta_rapida', 'ix_precos_catalogo_rapido',
                'ix_precos_marcas_rapida'}.issubset(nomes)
    c = conexao_leitura()
    a = consulta_cacheada(c, 'SELECT COUNT(*) FROM precos', [], unica=True)
    b = consulta_cacheada(c, 'SELECT COUNT(*) FROM precos', [], unica=True)
    assert a[0] == b[0] == 9
    assert cache.hits >= 1
    cache.clear()


def test_multiplas_threads_atendem_consultas(cliente):
    # Requisições GET reais através de ASGI/TestClient, sem acesso externo.
    params = {'marca':'Toyota', 'modelo':'Corolla', 'ano':2022}

    def consultar(_):
        resposta = cliente.get('/consulta', params=params)
        return resposta.status_code, resposta.json().get('total')

    with ThreadPoolExecutor(max_workers=12) as pool:
        resultados = list(pool.map(consultar, range(100)))
    assert resultados == [(200, 5)] * 100


def test_mais_de_cem_requisicoes_sem_429(cliente):
    params = {'marca': 'Toyota', 'modelo': 'Corolla', 'ano': 2022}
    codigos = [cliente.get('/consulta', params=params).status_code for _ in range(150)]
    assert codigos == [200] * 150


def test_migracao_indice_em_base_existente_preserva_precos(base_populada):
    from scripts.otimizar import otimizar
    with conexao(create=True) as c:
        antes = c.execute('SELECT COUNT(*) FROM precos').fetchone()[0]
        c.execute('DROP INDEX ix_precos_consulta_rapida')
    otimizar()
    with conexao() as c:
        depois = c.execute('SELECT COUNT(*) FROM precos').fetchone()[0]
        nomes = {r[1] for r in c.execute('PRAGMA index_list(precos)')}
    assert antes == depois == 9
    assert 'ix_precos_consulta_rapida' in nomes
