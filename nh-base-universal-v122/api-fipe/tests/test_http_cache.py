"""Garante que cache HTTP acelera consultas sem alterar segurança ou contrato."""
from app.performance import CacheHTTP, cache_http

PARAMS = {'marca': 'Toyota', 'modelo': 'Corolla', 'ano': 2022}


def test_cache_http_hit_miss_mantem_conteudo(cliente):
    cache_http.clear()
    primeira = cliente.get('/consulta', params=PARAMS)
    segunda = cliente.get('/consulta', params=PARAMS)
    assert primeira.status_code == segunda.status_code == 200
    assert primeira.headers['X-FIPE-Cache'] == 'MISS'
    assert segunda.headers['X-FIPE-Cache'] == 'HIT'
    assert primeira.content == segunda.content
    assert segunda.json()['total'] == 5
    assert segunda.headers['Cache-Control'] == 'no-store'
    assert primeira.headers['Server-Timing'].startswith('consulta;dur=')
    assert segunda.headers['Server-Timing'].startswith('consulta;dur=')


def test_cache_http_separa_filtros_e_respeita_autenticacao(cliente, monkeypatch):
    cache_http.clear()
    p = cliente.get('/consulta', params=PARAMS)
    assert p.status_code == 200
    assert cliente.get('/consulta', params={**PARAMS, 'limite': 1}).headers['X-FIPE-Cache'] == 'MISS'
    assert cliente.get('/consulta', params={**PARAMS, 'limite': 1}).headers['X-FIPE-Cache'] == 'HIT'
    monkeypatch.setenv('API_KEY', 'segredo-cache')
    assert cliente.get('/consulta', params=PARAMS).status_code == 401
    r = cliente.get('/consulta', params=PARAMS, headers={'X-API-Key': 'segredo-cache'})
    assert r.status_code == 200
    assert r.json() == p.json()


def test_cache_http_ttl_e_limite_de_corpo():
    agora = [1.]
    c = CacheHTTP(max_entries=2, ttl=2, max_bytes_per_entry=5, clock=lambda: agora[0])
    c.put(('a',), b'ok')
    assert c.get(('a',)) == b'ok'
    c.put(('grande',), b'muito-grande')
    assert c.get(('grande',)) is None
    agora[0] = 3.
    assert c.get(('a',)) is None
    c.put(('b',), b'1')
    c.put(('c',), b'2')
    c.put(('d',), b'3')
    assert len(c._items) == 2
    assert c.get(('b',)) is None


def test_consulta_vazia_e_paginacao_cacheadas(cliente):
    cache_http.clear()
    vazio = cliente.get('/consulta', params={'marca': 'MarcaInexistente', 'modelo': 'Nada', 'ano': 2022})
    assert vazio.status_code == 200
    assert vazio.json()['total'] == 0
    assert cliente.get('/consulta', params={'marca': 'MarcaInexistente', 'modelo': 'Nada', 'ano': 2022}).headers['X-FIPE-Cache'] == 'HIT'
    paginado = cliente.get('/consulta', params={**PARAMS, 'offset': 3, 'limite': 1}).json()
    assert paginado['total'] == 5
    assert len(paginado['veiculos']) == 1


def test_respostas_grandes_nao_ficam_no_cache(cliente, monkeypatch):
    # API segue retornando conteúdo mesmo quando cache HTTP está desativado.
    monkeypatch.setattr(cache_http, 'max_bytes_per_entry', 5)
    cache_http.clear()
    r1 = cliente.get('/consulta', params=PARAMS)
    r2 = cliente.get('/consulta', params=PARAMS)
    assert r1.status_code == r2.status_code == 200
    assert r1.headers['X-FIPE-Cache'] == 'MISS'
    assert r2.headers['X-FIPE-Cache'] == 'MISS'
