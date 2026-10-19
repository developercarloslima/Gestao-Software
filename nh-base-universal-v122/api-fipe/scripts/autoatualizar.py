"""Sincronizador periódico FIPEX: checa release, importa mês novo, não interrompe a API.

Executar: python -m scripts.autoatualizar [--uma-vez]
"""
from __future__ import annotations

import argparse
import logging
import os
import signal
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from threading import Event

from filelock import FileLock, Timeout

from app.database import conexao, database_path, referencia_atual
from app.sync_state import agora, registrar, status
from scripts.importar import importar
from scripts.sincronizar import baixar_mensal, obter_ultima_release

log = logging.getLogger('fipe.autoatualizacao')
parar = Event()


def meses_entre(inicial: tuple[int, int], final: tuple[int, int]):
    """Meses posteriores a inicial, até final, inclusive."""
    ano, mes = inicial
    while (ano, mes) < final:
        mes += 1
        if mes == 13:
            ano, mes = ano + 1, 1
        yield ano, mes


def sincronizar_uma_vez() -> dict:
    """Uma verificação sob lock interprocessos. Exceções reportadas em status.

    No primeiro bootstrap importa apenas a referência mais nova, evitando baixar
    anos de histórico; depois recupera lacunas mensais caso fique offline.
    """
    lock_path = database_path().parent / 'atualizador.lock'
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with FileLock(str(lock_path), timeout=0):
            try:
                registrar(estado='verificando', verificado_em=agora(), erro=None)
                release = obter_ultima_release()
                ultima = (release.ano, release.mes)
                info = status()
                registrar(estado='verificando', versao_remota=release.tag)
                try:
                    with conexao() as c:
                        local = referencia_atual(c)
                except FileNotFoundError:
                    local = None
                if local is not None and local > ultima:
                    raise ValueError('A fonte é mais antiga que a referência já instalada; não será feito downgrade.')
                if local is None:
                    pendentes = [ultima]
                elif local < ultima:
                    pendentes = list(meses_entre(local, ultima))
                elif info['versao_instalada'] != release.tag:
                    # Republicação/correção da mesma referência, ou base antiga sem metadados.
                    pendentes = [ultima]
                else:
                    pendentes = []

                if not pendentes:
                    registrar(estado='atualizado', concluido_em=agora(), erro=None)
                    log.info('Nenhuma atualização disponível (%s).', release.tag)
                    return {'resultado': 'sem_novidades', 'versao': release.tag, 'referencia': ultima}

                minimo = max(1, int(os.environ.get('AUTO_SYNC_MIN_REGISTROS', '1000')))
                tolerancia = float(os.environ.get('AUTO_SYNC_MAX_INVALIDOS_FRACAO', '0.005'))
                if not 0 <= tolerancia <= 1:
                    raise ValueError('AUTO_SYNC_MAX_INVALIDOS_FRACAO deve estar entre 0 e 1.')
                proporcao = float(os.environ.get('AUTO_SYNC_MIN_RATIO', '0.70'))
                if not 0 <= proporcao <= 1:
                    raise ValueError('AUTO_SYNC_MIN_RATIO deve estar entre 0 e 1.')
                atualizados = []
                for ano, mes in pendentes:
                    if parar.is_set():
                        raise InterruptedError('Sincronização interrompida durante atualização.')
                    registrar(estado='baixando')
                    with tempfile.TemporaryDirectory(prefix='fipe-auto-') as d:
                        arquivo = Path(d) / f'fipex-{ano}-{mes:02d}.csv'
                        # Conteúdo sempre fixado na tag, e não numa branch mutável.
                        baixar_mensal(ano, mes, arquivo, ref=release.tag)
                        registrar(estado='importando')
                        resultado = importar(
                            arquivo, referencia_esperada=(ano, mes),
                            minimo_registros=minimo, limite_invalidos_proporcao=tolerancia,
                            substituir_mes=True, proporcao_minima_anterior=proporcao)
                    registrar(atualizado_em=agora(), linhas_ultima_importacao=resultado['linhas_validas'])
                    log.info('Importado FIPEX %04d-%02d: %s linhas válidas.', ano, mes, resultado['linhas_validas'])
                    atualizados.append({'ano': ano, 'mes': mes, **resultado})
                registrar(estado='atualizado', concluido_em=agora(),
                          versao_instalada=release.tag, erro=None)
                return {'resultado': 'importado', 'versao': release.tag, 'meses': atualizados}
            except Exception as e:
                log.exception('Falha ao sincronizar FIPEX. Banco anterior preservado.')
                registrar(estado='erro', erro=f'{type(e).__name__}: {e}'[:1500], concluido_em=agora())
                raise
    except Timeout:
        log.warning('Outra instância já está sincronizando. Ignorando execução duplicada.')
        return {'resultado': 'ocupado'}


def main() -> int:
    parser = argparse.ArgumentParser(description='Verificador automático mensal FIPEX')
    parser.add_argument('--uma-vez', action='store_true', help='Verifica agora, sem agendamento contínuo')
    args = parser.parse_args()
    logging.basicConfig(level=os.environ.get('LOG_LEVEL', 'INFO').upper(),
                        format='%(asctime)s %(levelname)s %(name)s %(message)s')
    if not args.uma_vez:
        for sig in (signal.SIGINT, signal.SIGTERM):
            signal.signal(sig, lambda *_: parar.set())
    try:
        intervalo = int(os.environ.get('AUTO_SYNC_INTERVAL_SECONDS', '86400'))
    except ValueError:
        parser.error('AUTO_SYNC_INTERVAL_SECONDS deve ser um inteiro')
    if intervalo < 3600:
        parser.error('Intervalo mínimo de checagem: 3600 segundos (1 hora)')
    while not parar.is_set():
        try:
            sincronizar_uma_vez()
        except Exception:
            if args.uma_vez:
                return 1
        if args.uma_vez:
            return 0
        log.info('Próxima verificação em %s horas (salvo reinício).', intervalo / 3600)
        parar.wait(intervalo)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
