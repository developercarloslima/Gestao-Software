/* Cooperativas internas + BI auditável de pagamentos. A fonte da verdade é a
 * API financeira da base, nunca uma contagem feita pelo navegador. */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  if (!$('tab-sales-teams')) return;

  let teams = [];
  let report = null;
  let editingId = null;
  const brl = new Intl.NumberFormat('pt-BR',{style:'currency',currency:'BRL'});
  const escape = value => String(value ?? '').replace(/[&<>"']/g,c=>({
    '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'
  })[c]);
  const safeDate = value => value ? new Date(value).toLocaleString('pt-BR') : '—';
  const activeTab = () => !$('tab-sales-teams').hidden;
  const message = (value, error=false) => {
    $('teams-status').textContent = value || '';
    $('teams-status').classList.toggle('teams-message-error',error);
  };
  async function api(path, options={}) {
    const token = localStorage.getItem('nhPortalToken');
    if (!token) throw new Error('Sua sessão expirou. Entre novamente no painel.');
    const response = await fetch(path,{
      ...options,
      headers:{Authorization:`Bearer ${token}`,...(options.body?{'Content-Type':'application/json'}:{}),...(options.headers||{})}
    });
    if (!response.ok) {
      let description;
      try {
        const data = await response.json();
        description = data.message || data.error || data.detail;
      } catch { /* Nem todo erro de proxy devolve JSON. */ }
      throw new Error(description || `Falha na operação (HTTP ${response.status}).`);
    }
    return response.status === 204 ? null : response.json();
  }
  const metric = (label,value,help='') => `<div class="teams-bi-metric">
    <span>${escape(label)}</span><strong>${escape(value)}</strong><small>${escape(help)}</small></div>`;
  function params() {
    const query=new URLSearchParams();
    if ($('teams-from').value) query.set('from',$('teams-from').value);
    if ($('teams-to').value) query.set('to',$('teams-to').value);
    if ($('teams-filter').value) query.set('teamId',$('teams-filter').value);
    return query;
  }
  function initialDates() {
    const pieces=new Intl.DateTimeFormat('en-CA',{timeZone:'America/Sao_Paulo',year:'numeric',month:'2-digit',day:'2-digit'}).formatToParts(new Date());
    const part=name=>pieces.find(p=>p.type===name)?.value;
    const date=`${part('year')}-${part('month')}-${part('day')}`;
    if (!$('teams-from').value) $('teams-from').value=date.slice(0,7)+'-01';
    if (!$('teams-to').value) $('teams-to').value=date;
  }
  function renderBars(id,rows) {
    const target=$(id);
    const highest=Math.max(1,...rows.map(row=>Number(row.count||0)));
    target.innerHTML=rows.length ? rows.map(row=>`<div class="teams-bar">
      <span class="teams-bar-label" title="${escape(row.label)}">${escape(row.label)}</span>
      <div class="teams-bar-track"><div class="teams-bar-fill" style="width:${Math.max(0,100*Number(row.count||0)/highest)}%"></div></div>
      <strong>${Number(row.count||0)}</strong></div>`).join('')
      : '<p class="helper">Nenhuma venda com adesão paga no período.</p>';
  }
  function drawReport() {
    if (!report) return;
    const r=report, a=r.companyAcceptance||{}, m=r.company||{};
    $('teams-metrics').innerHTML=[
      metric('Total acompanhado',a.total||0,'Cadastros, aceites e vendas'),
      metric('Aceites',a.accepted||0,'Assinaturas confirmadas'),
      metric('Pendentes de aceite',a.pendingAcceptance||0,'Liberados para assinatura'),
      metric('Em cadastro',a.inProgress||0,'Aguardando liberação'),
      metric('Adesão pendente',a.pendingPayment||0,'Aceite feito, pagamento ainda não recebido'),
      metric('Vendas efetivadas',m.sales||0,'Somente aceites com adesão paga'),
      metric('Vendas por boleto',r.salesByBoleto||0,'Pagamento identificado pelo Asaas'),
      metric('Vendas por Pix',r.salesByPix||0,'Pagamento identificado pelo Asaas'),
      metric('Outros meios',r.salesByOther||0,'Adesões recebidas por outros meios'),
      metric('Ticket médio mensal',brl.format(Number(m.averageMonthlyTicket||0)),'Mensalidade dos contratos pagos'),
      metric('Inadimplência',m.delinquencyRate==null?'N/D':`${Number(m.delinquencyRate).toFixed(2)}%`,'Parcelas com mais de 6 dias de atraso'),
      metric('Base ativa',m.activeBase==null?'N/D':m.activeBase,'Veículos com status ativo e adesão recebida'),
      metric('Churn',m.churnRate==null?'N/D':`${Number(m.churnRate).toFixed(2)}%`,'Aguardando histórico de cancelamentos'),
      metric('Cooperativas',r.teamCount||0,'Na mesma empresa'),
      metric('Consultores',r.consultantCount||0,'Participantes no recorte')
    ].join('');
    const team=teams.find(t=>t.id===$('teams-filter').value);
    $('teams-ranking-title').textContent=`Ranking de consultores — ${team?.name||'Toda a empresa'}`;
    renderBars('teams-chart-by-team',(r.teams||[]).map(t=>({label:t.name,count:t.metrics?.sales})));
    renderBars('teams-chart-by-day',(r.days||[]).map(d=>({label:d.day.split('-').reverse().join('/'),count:d.sales})));
    $('teams-consultants').innerHTML=(r.consultants||[]).map(c=>`<tr>
      <td><strong>${escape(c.name)}</strong></td><td>${escape(c.currentTeamName||'Sem equipe')}</td>
      <td><strong>${Number(c.metrics?.sales||0)}</strong></td>
      <td>${Number(c.acceptance?.accepted||0)}</td>
      <td>${Number(c.acceptance?.pendingAcceptance||0)}</td>
      <td>${Number(c.acceptance?.inProgress||0)}</td>
      <td>${Number(c.acceptance?.pendingPayment||0)}</td>
      <td>${brl.format(Number(c.metrics?.averageMonthlyTicket||0))}</td>
      <td><button type="button" class="outline" data-teams-results="${escape(c.id)}">Ver resultados</button></td>
      </tr>`).join('')||'<tr><td colspan="9">Nenhum consultor encontrado neste recorte.</td></tr>';
  }
  function drawTeams() {
    const filter=$('teams-filter'), selected=filter.value;
    filter.innerHTML='<option value="">Todas as cooperativas</option>'+
      teams.map(t=>`<option value="${escape(t.id)}">${escape(t.name)}${t.active?'':' (inativa)'}</option>`).join('');
    if (teams.some(t=>t.id===selected)) filter.value=selected;
    $('teams-list').innerHTML=teams.map(t=>{
      const progress=report?.teams?.find(row=>row.id===t.id);
      return `<article class="teams-tile">
        <div><h3>${escape(t.name)}</h3><p>${Number(t.memberCount||0)} participantes · ${t.active?'Ativa':'Desativada'}
        ${progress?` · ${Number(progress.metrics?.sales||0)} vendas pagas`:''}</p></div>
        <div class="teams-tile-actions">
          <button type="button" class="outline" data-teams-action="results" data-id="${escape(t.id)}">Ver resultados</button>
          <button type="button" class="secondary" data-teams-action="edit" data-id="${escape(t.id)}">Editar</button>
          <button type="button" class="outline" data-teams-action="toggle" data-id="${escape(t.id)}">${t.active?'Desativar':'Reativar'}</button>
          <button type="button" class="danger" data-teams-action="delete" data-id="${escape(t.id)}">Excluir</button>
        </div></article>`;
    }).join('')||'<p class="helper">Nenhuma cooperativa cadastrada. Clique em Criar equipe.</p>';
  }
  async function refresh() {
    initialDates();
    message('Consultando a fonte financeira e as equipes...');
    try {
      const [newTeams,newReport]=await Promise.all([
        api('/api/admin/sales-teams'),
        api('/api/admin/sales-teams/analytics?'+params().toString())
      ]);
      teams=newTeams;report=newReport;
      drawTeams();drawReport();
      message(`Período ${report.from} até ${report.to}. ${report.salesDefinition}`);
    } catch(error) {message(error.message,true);}
  }
  function closeDialog(id) {$(id)?.close();}
  function renderMembers(candidates=[]) {
    const groups={CONSULTANT:'Consultores',ANALYST:'Analistas',SUPERVISION_ANALYSIS:'Supervisores',ADMIN:'Administradores'};
    $('teams-members').innerHTML=Object.entries(groups).map(([role,label])=>{
      const people=candidates.filter(person=>person.role===role);
      if (!people.length) return '';
      return `<fieldset class="teams-member-group"><legend><strong>${label} (${people.length})</strong></legend>
        ${people.map(p=>`<div class="teams-member">
          <input type="checkbox" id="team-person-${escape(p.key.replace(':','-'))}" value="${escape(p.key)}" ${p.selected?'checked':''}>
          <label for="team-person-${escape(p.key.replace(':','-'))}"><strong>${escape(p.name)}</strong><small>${escape(p.currentTeams||'Sem equipe')}</small></label>
          <button type="button" class="danger" data-teams-remove="${escape(p.key)}" ${p.selected?'':'hidden'}>Remover</button>
        </div>`).join('')}</fieldset>`;
    }).join('')||'<p>Nenhuma pessoa ativa disponível.</p>';
  }
  async function editTeam(id=null) {
    editingId=id;
    const team=teams.find(t=>t.id===id);
    $('teams-name').value=team?.name||'';
    $('teams-dialog-title').textContent=team?`Editar equipe · ${team.name}`:'Criar equipe';
    $('teams-members').textContent='Carregando colaboradores...';
    $('teams-save').disabled=true;
    $('teams-dialog').showModal();
    try {
      const rows=await api(id?`/api/admin/sales-teams/${encodeURIComponent(id)}/members`:'/api/admin/sales-teams/candidates');
      if (!$('teams-dialog').open) return;
      renderMembers(rows);
      $('teams-save').disabled=false;
    } catch(error) {$('teams-members').textContent=error.message;}
  }
  async function saveTeam(event) {
    event.preventDefault();
    const name=$('teams-name').value.trim();
    const keys=[...$('teams-members').querySelectorAll('input:checked')].map(input=>input.value);
    const button=$('teams-save');
    button.disabled=true;
    try {
      await api(editingId?`/api/admin/sales-teams/${encodeURIComponent(editingId)}`:'/api/admin/sales-teams',{
        method:editingId?'PUT':'POST',body:JSON.stringify({name,keys})
      });
      closeDialog('teams-dialog');
      await refresh();
    } catch(error){window.alert(error.message);}
    finally{button.disabled=false;}
  }
  async function takeAction(action,id) {
    const team=teams.find(t=>t.id===id);
    if (!team) return;
    if (action==='results') {
      $('teams-filter').value=id;
      await refresh();
      $('teams-ranking').scrollIntoView({behavior:'smooth'});
      return;
    }
    if (action==='edit') return editTeam(id);
    if (action==='toggle') {
      if (!window.confirm(`${team.active?'Desativar':'Reativar'} ${team.name}? O histórico de vendas será preservado.`)) return;
      try {await api(`/api/admin/sales-teams/${encodeURIComponent(id)}`,{
        method:'PATCH',body:JSON.stringify({active:!team.active})});await refresh();}
      catch(error){window.alert(error.message);}
    }
    if (action==='delete') {
      if (!window.confirm(`Excluir ${team.name}? A equipe ficará arquivada, preservando o histórico.`)) return;
      try {await api(`/api/admin/sales-teams/${encodeURIComponent(id)}`,{method:'DELETE'});await refresh();}
      catch(error){window.alert(error.message);}
    }
  }
  function showConsultant(id) {
    const person=report?.consultants?.find(c=>c.id===id);
    if (!person) return;
    $('teams-person-title').textContent=`${person.name} · ${person.currentTeamName||'Sem equipe'}`;
    $('teams-person-metrics').innerHTML=[
      metric('Aceites',person.acceptance?.accepted||0),
      metric('Pendentes de aceite',person.acceptance?.pendingAcceptance||0),
      metric('Aguardando adesão',person.acceptance?.pendingPayment||0),
      metric('Vendas pagas',person.metrics?.sales||0),
      metric('Ticket médio mensal',brl.format(Number(person.metrics?.averageMonthlyTicket||0)))
    ].join('');
    const sold=(person.associates||[]).map(s=>`<tr>
      <td>${escape(s.associateName)}</td><td>${escape(s.plate||'Sem placa')}</td>
      <td>Adesão paga</td><td>${escape(safeDate(s.completedAt))}</td>
      <td>${escape(s.paymentMethod||'Não identificado')}</td>
      <td>${brl.format(Number(s.monthlyValue||0))}</td></tr>`);
    const waiting=(person.pendingAssociates||[]).map(s=>`<tr>
      <td>${escape(s.associateName)}</td><td>${escape(s.plate||'Sem placa')}</td>
      <td>${escape(s.awaitingPayment?'Aceito, aguardando adesão':s.awaitingAcceptance?'Pendente de aceite':'Em cadastro')}</td>
      <td>${escape(safeDate(s.createdAt))}</td><td>—</td>
      <td>${brl.format(Number(s.monthlyValue||0))}</td></tr>`);
    $('teams-person-rows').innerHTML=[...sold,...waiting].join('')||'<tr><td colspan="6">Sem registros no período.</td></tr>';
    $('teams-person-dialog').showModal();
  }
  async function exportCsv() {
    const dataset=$('teams-export-dataset').value;
    const token=localStorage.getItem('nhPortalToken');
    const path=`/api/admin/sales-teams/analytics/export/${encodeURIComponent(dataset)}.csv?${params()}`;
    try {
      const response=await fetch(path,{headers:{Authorization:`Bearer ${token}`}});
      if (!response.ok) throw new Error(`Falha na exportação: HTTP ${response.status}`);
      const blob=await response.blob(), url=URL.createObjectURL(blob);
      const link=document.createElement('a');
      link.href=url;link.download=`nh-bi-${dataset}.csv`;
      document.body.appendChild(link);link.click();link.remove();
      setTimeout(()=>URL.revokeObjectURL(url),2000);
    } catch(error){message(error.message,true);}
  }
  $('teams-create').addEventListener('click',()=>editTeam());
  $('teams-form').addEventListener('submit',saveTeam);
  $('teams-refresh').addEventListener('click',refresh);
  $('teams-apply').addEventListener('click',refresh);
  $('teams-export').addEventListener('click',exportCsv);
  $('teams-list').addEventListener('click',event=>{
    const button=event.target.closest('[data-teams-action]');
    if (button) takeAction(button.dataset.teamsAction,button.dataset.id);
  });
  $('teams-consultants').addEventListener('click',event=>{
    const button=event.target.closest('[data-teams-results]');
    if (button) showConsultant(button.dataset.teamsResults);
  });
  $('teams-members').addEventListener('change',event=>{
    if (event.target.matches('input[type=checkbox]')) {
      const remove=event.target.closest('.teams-member')?.querySelector('[data-teams-remove]');
      if (remove) remove.hidden=!event.target.checked;
    }
  });
  $('teams-members').addEventListener('click',event=>{
    const remove=event.target.closest('[data-teams-remove]');
    if (!remove) return;
    const input=remove.closest('.teams-member')?.querySelector('input[type=checkbox]');
    if (input) input.checked=false;
    remove.hidden=true;
  });
  document.querySelectorAll('[data-teams-close]').forEach(button=>button.addEventListener('click',()=>{
    button.closest('dialog')?.close();
  }));
  document.querySelector('[data-tab="sales-teams"]')?.addEventListener('click',()=>{
    // O controlador principal alterna as abas no mesmo evento de clique.
    queueMicrotask(()=>{if (activeTab()) refresh();});
  });
})();
