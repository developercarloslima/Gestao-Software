"""API de consulta FIPE baseada em snapshots independentes do FIPEX."""
from __future__ import annotations

import os
import sqlite3
import time
from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

from app.database import normalizar, pronto, referencia_atual
from app.sync_state import status as status_sincronizacao
from fastapi.responses import HTMLResponse, Response
from starlette.concurrency import run_in_threadpool
import orjson
from app.performance import leitura, consulta_cacheada, cache_http
from app.database import database_path

app = FastAPI(
    title='API FIPE Independente',
    version='1.4.1',
    description=(
        'Consulta marca, modelo, ano e todas as versões FIPE disponíveis. '
        'Fonte: dataset público FIPEX. Dados de veículo limitados aos 12 campos publicados.'
    ),
)
origens = [x.strip() for x in os.getenv('CORS_ORIGINS', '').split(',') if x.strip()]
app.add_middleware(CORSMiddleware, allow_origins=origens, allow_methods=['GET'], allow_headers=['X-API-Key'])


class PrecoVeiculo(BaseModel):
    tipo_veiculo: str
    codigo_fipe: str
    nome_modelo: str
    nome_marca: str
    nome_combustivel: str
    sigla_combustivel: str
    ano_modelo: int
    zero_km: bool
    valor_centavos: int
    valor_formatado: str
    mes_referencia: int
    ano_referencia: int


async def autorizar(x_api_key: Annotated[str | None, Header()] = None):
    esperado = os.getenv('API_KEY', '').strip()
    if esperado:
        import hmac
        if not x_api_key or not hmac.compare_digest(x_api_key, esperado):
            raise HTTPException(status_code=401, detail='Chave de API ausente ou inválida.')


def abrir_db():
    """Retorna uma conexão de leitura thread-local via context manager."""
    from contextlib import contextmanager

    @contextmanager
    def contexto():
        try:
            with leitura() as c:
                if referencia_atual(c) is None:
                    raise HTTPException(status_code=503, detail='Sem dados FIPE importados.')
                yield c
        except (FileNotFoundError, sqlite3.DatabaseError) as exc:
            raise HTTPException(status_code=503, detail=f'Base FIPE indisponível: {exc}') from exc

    return contexto()


def selecao_referencia(c: sqlite3.Connection, ano: int | None, mes: int | None) -> tuple[int, int]:
    if (ano is None) != (mes is None):
        raise HTTPException(status_code=422, detail='Informe ano_referencia e mes_referencia juntos.')
    if ano is not None and mes is not None:
        return ano, mes
    r = referencia_atual(c)
    assert r is not None
    return r


def escapar_like(valor: str) -> str:
    return valor.replace('\\', '\\\\').replace('%', '\\%').replace('_', '\\_')


def validar_ano_modelo(ano: int) -> None:
    if ano != 32000 and ano > 2200:
        raise HTTPException(status_code=422, detail='Ano inválido: informe um ano-modelo até 2200 ou 32000 para zero km.')


def formatar_registro(r: sqlite3.Row) -> dict:
    resultado = {k: r[k] for k in PrecoVeiculo.model_fields}
    resultado['zero_km'] = bool(resultado['zero_km'])
    return resultado


@app.get('/health', tags=['Sistema'])
def health():
    carregada = pronto()
    return {'status': 'ok' if carregada else 'sem_dados', 'base_carregada': carregada}


@app.get('/sincronizacao/status', dependencies=[Depends(autorizar)], tags=['Sincronização'])
def sincronizacao_status():
    """Exibe último check, versão instalada/remota, erro e estado do importador."""
    return status_sincronizacao()


@app.get('/painel/sincronizacao', response_class=HTMLResponse, tags=['Sincronização'])
def painel_sincronizacao():
    """Página mínima de monitoramento. Chave fica só na memória da aba."""
    return """<!doctype html><html lang="pt-BR"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Status de sincronização FIPE</title>
<style>body{font-family:system-ui,sans-serif;background:#f6f8fa;color:#142032;padding:24px;margin:auto;max-width:850px}
section{background:#fff;border:1px solid #dce2e8;border-radius:14px;padding:22px;margin-top:18px}
section div{padding:7px 0;border-bottom:1px solid #eee}b{display:inline-block;min-width:240px}
button,input{padding:10px;border:1px solid #b8c2d2;border-radius:8px}button{cursor:pointer}
#erro{white-space:pre-wrap;color:#af2437}</style></head><body>
<h1>API FIPE — Sincronização</h1><p>Atualizações consultadas no FIPEX a cada intervalo configurado.</p>
<label>Chave de API (se configurada): <input id="chave" type="password" autocomplete="off"></label>
<button id="atualizar">Atualizar status</button>
<section><div><b>Estado:</b> <span id="estado">Carregando...</span></div>
<div><b>Referência local:</b> <span id="ref">—</span></div>
<div><b>Versão instalada:</b> <span id="instalada">—</span></div>
<div><b>Versão disponível:</b> <span id="remota">—</span></div>
<div><b>Última verificação:</b> <span id="verificado">—</span></div>
<div><b>Última conclusão:</b> <span id="concluido">—</span></div>
<div><b>Última importação:</b> <span id="importado">—</span></div>
<div><b>Registros do último lote:</b> <span id="linhas">—</span></div>
<div><b>Erro:</b> <span id="erro">—</span></div></section>
<script>
let chave='';
const fmt=v=>v||'—', data=v=>v?new Date(v).toLocaleString('pt-BR'):'—';
async function atualizar(){
  chave=document.getElementById('chave').value;
  try{
    const h=chave?{'X-API-Key':chave}:{};
    const r=await fetch('/sincronizacao/status',{headers:h,cache:'no-store'});
    if(!r.ok)throw new Error('HTTP '+r.status+(r.status===401?' — chave de API inválida ou ausente':''));
    const d=await r.json();
    const campos={estado:fmt(d.estado),ref:d.referencia_local?`${d.referencia_local.mes}/${d.referencia_local.ano}`:'—',
      instalada:fmt(d.versao_instalada),remota:fmt(d.versao_remota),
      verificado:data(d.verificado_em),concluido:data(d.concluido_em),importado:data(d.atualizado_em),
      linhas:fmt(d.linhas_ultima_importacao),erro:fmt(d.erro)};
    Object.entries(campos).forEach(([id,v])=>document.getElementById(id).textContent=v);
  }catch(e){document.getElementById('erro').textContent=e.message;}
}
document.getElementById('atualizar').addEventListener('click',atualizar);
setInterval(atualizar,60000);atualizar();
</script></body></html>"""


@app.get('/metadados', dependencies=[Depends(autorizar)], tags=['Sistema'])
def metadados():
    with abrir_db() as c:
        ano, mes = selecao_referencia(c, None, None)
        registros = consulta_cacheada(c, 'SELECT COUNT(*) FROM precos', [], unica=True)[0]
        atuais = consulta_cacheada(c, 'SELECT COUNT(*) FROM precos WHERE ano_referencia=? AND mes_referencia=?', (ano, mes), unica=True)[0]
        return {'referencia_mais_recente': {'ano': ano, 'mes': mes},
                'total_registros': registros, 'registros_na_referencia': atuais,
                'fonte': 'https://github.com/fipex-labs/dataset'}


@app.get('/tipos', dependencies=[Depends(autorizar)], tags=['Catálogo'])
def tipos():
    """Categorias reais presentes na última referência, sem presumir nomenclaturas."""
    with abrir_db() as c:
        ano, mes = selecao_referencia(c, None, None)
        dados = consulta_cacheada(c, "SELECT DISTINCT tipo_veiculo FROM precos WHERE ano_referencia=? AND mes_referencia=? ORDER BY tipo_veiculo COLLATE NOCASE", (ano, mes))
        return {'referencia': {'ano': ano, 'mes': mes}, 'tipos': [r[0] for r in dados]}


@app.get('/marcas', dependencies=[Depends(autorizar)], tags=['Catálogo'])
def marcas(tipo: str | None = None, busca: str | None = None,
           limite: int = Query(200, ge=1, le=1000), offset: int = Query(0, ge=0)):
    with abrir_db() as c:
        ano, mes = selecao_referencia(c, None, None)
        sql = 'SELECT DISTINCT nome_marca FROM precos WHERE ano_referencia=? AND mes_referencia=?'
        args: list = [ano, mes]
        if tipo:
            sql += ' AND tipo_busca=?'
            args.append(normalizar(tipo))
        if busca:
            sql += " AND marca_busca LIKE ? ESCAPE '\\'"
            args.append('%' + escapar_like(normalizar(busca)) + '%')
        sql += ' ORDER BY nome_marca COLLATE NOCASE LIMIT ? OFFSET ?'
        args.extend([limite, offset])
        return {'referencia': {'ano': ano, 'mes': mes}, 'marcas': [r[0] for r in consulta_cacheada(c, sql, args)]}


@app.get('/modelos', dependencies=[Depends(autorizar)], tags=['Catálogo'])
def modelos(marca: str = Query(min_length=1), tipo: str | None = None,
            busca: str | None = None, limite: int = Query(500, ge=1, le=2000),
            offset: int = Query(0, ge=0)):
    with abrir_db() as c:
        ano, mes = selecao_referencia(c, None, None)
        sql = 'SELECT DISTINCT nome_modelo FROM precos WHERE ano_referencia=? AND mes_referencia=? AND marca_busca=?'
        args: list = [ano, mes, normalizar(marca)]
        if tipo:
            sql += ' AND tipo_busca=?'
            args.append(normalizar(tipo))
        if busca:
            sql += " AND modelo_busca LIKE ? ESCAPE '\\'"
            args.append('%' + escapar_like(normalizar(busca)) + '%')
        sql += ' ORDER BY nome_modelo COLLATE NOCASE LIMIT ? OFFSET ?'
        args.extend([limite, offset])
        return {'referencia': {'ano': ano, 'mes': mes}, 'marca': marca, 'modelos': [r[0] for r in consulta_cacheada(c, sql, args)]}


@app.get('/anos', dependencies=[Depends(autorizar)], tags=['Catálogo'])
def anos(marca: str = Query(min_length=1), modelo: str = Query(min_length=1), tipo: str | None = None):
    with abrir_db() as c:
        refano, refmes = selecao_referencia(c, None, None)
        sql = '''SELECT DISTINCT ano_modelo FROM precos
                 WHERE ano_referencia=? AND mes_referencia=? AND marca_busca=?
                   AND modelo_busca LIKE ? ESCAPE '\\' '''
        args: list = [refano, refmes, normalizar(marca), '%' + escapar_like(normalizar(modelo)) + '%']
        if tipo:
            sql += ' AND tipo_busca=?'
            args.append(normalizar(tipo))
        sql += ' ORDER BY ano_modelo DESC'
        return {'referencia': {'ano': refano, 'mes': refmes}, 'marca': marca,
                'modelo': modelo, 'anos': [r[0] for r in consulta_cacheada(c, sql, args)]}


def _consulta_sql(marca: str, modelo: str, ano: int, tipo: str | None,
                  combustivel: str | None, zero_km: bool | None,
                  ano_referencia: int | None, mes_referencia: int | None,
                  limite: int, offset: int) -> bytes:
    """Executa SELECTs numa thread apenas quando a resposta ainda não está cacheada.

    Mantém o contrato de retorno e filtros da API v1.3.2.
    """
    with abrir_db() as c:
        ra, rm = selecao_referencia(c, ano_referencia, mes_referencia)
        sql = ''' FROM precos WHERE ano_referencia=? AND mes_referencia=?
                  AND marca_busca=? AND ano_modelo=?
                  AND modelo_busca LIKE ? ESCAPE '\\' '''
        args: list = [ra, rm, normalizar(marca), ano, '%' + escapar_like(normalizar(modelo)) + '%']
        if tipo:
            sql += ' AND tipo_busca=?'
            args.append(normalizar(tipo))
        if combustivel:
            sql += ' AND (combustivel_busca=? OR sigla_combustivel=?)'
            args.extend([normalizar(combustivel), normalizar(combustivel)])
        if zero_km is not None:
            sql += ' AND zero_km=?'
            args.append(int(zero_km))
        total = consulta_cacheada(c, 'SELECT COUNT(*) ' + sql, args, unica=True)[0]
        rows = consulta_cacheada(c, 'SELECT * ' + sql + ''' ORDER BY nome_modelo COLLATE NOCASE,
             codigo_fipe, zero_km LIMIT ? OFFSET ?''', [*args, limite, offset])
        conteudo = {'consulta': {'marca': marca, 'modelo': modelo, 'ano': ano, 'tipo': tipo,
                                 'combustivel': combustivel, 'zero_km': zero_km},
                    'referencia': {'ano': ra, 'mes': rm}, 'total': total,
                    'limite': limite, 'offset': offset,
                    'multiplas_versoes': total > 1,
                    'veiculos': [formatar_registro(r) for r in rows]}
        return orjson.dumps(conteudo)


@app.get('/consulta', dependencies=[Depends(autorizar)], tags=['Consultas'])
async def consulta(marca: str = Query(min_length=1), modelo: str = Query(min_length=1),
             ano: int = Query(ge=0, le=32000), tipo: str | None = None,
             combustivel: str | None = None, zero_km: bool | None = None,
             ano_referencia: int | None = Query(None, ge=1900, le=2200),
             mes_referencia: int | None = Query(None, ge=1, le=12),
             limite: int = Query(200, ge=1, le=1000), offset: int = Query(0, ge=0)):
    inicio_consulta = time.perf_counter()
    validar_ano_modelo(ano)
    # Somente payload HTTP 200 é cacheado: nenhuma resposta de autenticação ou
    # erro de DB é cacheada. A chave inclui todos os filtros e a identidade do DB.
    key = (str(database_path()), marca, modelo, ano, tipo, combustivel, zero_km,
           ano_referencia, mes_referencia, limite, offset)
    body = cache_http.get(key)
    if body is not None:
        ms = (time.perf_counter() - inicio_consulta) * 1000
        return Response(content=body, media_type='application/json',
                        headers={'X-FIPE-Cache': 'HIT', 'Cache-Control': 'no-store',
                                 'Server-Timing': f'consulta;dur={ms:.3f}'})

    body = await run_in_threadpool(_consulta_sql, marca, modelo, ano, tipo,
                                   combustivel, zero_km, ano_referencia,
                                   mes_referencia, limite, offset)
    cache_http.put(key, body)
    ms = (time.perf_counter() - inicio_consulta) * 1000
    return Response(content=body, media_type='application/json',
                    headers={'X-FIPE-Cache': 'MISS', 'Cache-Control': 'no-store',
                             'Server-Timing': f'consulta;dur={ms:.3f}'})



@app.get('/buscar-modelos', dependencies=[Depends(autorizar)], tags=['Consultas'])
def buscar_modelos(modelo: str = Query(min_length=2, max_length=120), ano: int = Query(ge=0, le=32000),
                  tipo: str | None = None, zero_km: bool | None = None,
                  limite: int = Query(70, ge=1, le=100), offset: int = Query(0, ge=0)):
    """Pesquisa de versões por nome composto (marca + modelo) ou só modelo.

    API privada utilizada pelo NH. Nunca constrói SQL com dados do usuário.
    Todos os termos devem estar presentes em marca ou modelo, impedindo colisões
    grosseiras de nomes entre veículos distintos.
    """
    validar_ano_modelo(ano)
    termos = [x for x in normalizar(modelo).split() if len(x) >= 2]
    if not termos:
        raise HTTPException(status_code=422, detail='Informe um modelo válido.')
    with abrir_db() as c:
        ra, rm = selecao_referencia(c, None, None)
        filtros = ['ano_referencia=?', 'mes_referencia=?', '(ano_modelo=? OR (zero_km=1 AND ano_modelo=32000))' if zero_km is True and ano != 32000 else 'ano_modelo=?']
        args: list = [ra, rm, ano]
        if tipo:
            filtros.append('tipo_busca=?')
            args.append(normalizar(tipo))
        if zero_km is not None:
            filtros.append('zero_km=?')
            args.append(int(zero_km))
        for termo in termos[:10]:
            filtros.append("(marca_busca LIKE ? ESCAPE '\\' OR modelo_busca LIKE ? ESCAPE '\\')")
            padrao = '%' + escapar_like(termo) + '%'
            args.extend([padrao, padrao])
        where = ' AND '.join(filtros)
        total = consulta_cacheada(c, 'SELECT COUNT(*) FROM precos WHERE ' + where, args, unica=True)[0]
        dados = consulta_cacheada(c, 'SELECT * FROM precos WHERE ' + where +
            ' ORDER BY nome_marca COLLATE NOCASE, nome_modelo COLLATE NOCASE, codigo_fipe LIMIT ? OFFSET ?',
            [*args, limite, offset])
        return {'referencia': {'ano': ra, 'mes': rm}, 'total': total,
                'veiculos': [formatar_registro(r) for r in dados]}


@app.get('/historico', dependencies=[Depends(autorizar)], tags=['Consultas'])
def historico(codigo_fipe: str = Query(min_length=1), ano: int = Query(ge=0, le=32000),
              zero_km: bool | None = None, combustivel: str | None = None,
              limite: int = Query(240, ge=1, le=1000), offset: int = Query(0, ge=0)):
    validar_ano_modelo(ano)
    with abrir_db() as c:
        sql = ' FROM precos WHERE codigo_fipe=? AND ano_modelo=?'
        args: list = [codigo_fipe.strip(), ano]
        if zero_km is not None:
            sql += ' AND zero_km=?'
            args.append(int(zero_km))
        if combustivel:
            sql += ' AND (combustivel_busca=? OR sigla_combustivel=?)'
            args.extend([normalizar(combustivel), normalizar(combustivel)])
        total = consulta_cacheada(c, 'SELECT COUNT(*) ' + sql, args, unica=True)[0]
        rows = consulta_cacheada(c, 'SELECT * ' + sql + ''' ORDER BY ano_referencia DESC,
                         mes_referencia DESC LIMIT ? OFFSET ?''', [*args, limite, offset])
        return {'codigo_fipe': codigo_fipe, 'ano_modelo': ano, 'total': total,
                'limite': limite, 'offset': offset,
                'precos': [formatar_registro(r) for r in rows]}
