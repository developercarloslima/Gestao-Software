#!/usr/bin/env python3
"""Gera uma chave por VPS sem enviar a chave-mestra para o servidor do cliente.
Execute somente no servidor central ou máquina administrativa offline.
PROVISIONER_MASTER_KEY deve ser a mesma variável PROVISIONER_WORKER_TOKEN na Gestão.
"""
import os, hmac, hashlib, sys
from uuid import UUID
if len(sys.argv) != 2:
    raise SystemExit('Uso: python gerar_token_vps.py <UUID da VPS>')
master=os.getenv('PROVISIONER_MASTER_KEY','')
if len(master)<32:
    raise SystemExit('Defina PROVISIONER_MASTER_KEY (32+ caracteres) em ambiente administrativo seguro.')
name=str(UUID(sys.argv[1]))
print(hmac.new(master.encode(),name.encode(),hashlib.sha256).hexdigest())
