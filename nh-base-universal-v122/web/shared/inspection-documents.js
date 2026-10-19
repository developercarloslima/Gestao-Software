/* V83.128 — Painel comum de envio/aceite dos documentos da vistoria.
 * O estado de assinatura vem exclusivamente dos dados persistidos pelo backend.
 * Este módulo nunca marca um documento como assinado por ter sido enviado.
 */
(function () {
  const roleRoutes = {
    analyst: { responsibility: 'analysis', parts: 'analysis' },
    supervision: { responsibility: 'supervision', parts: 'supervision' },
    admin: { responsibility: 'admin', parts: 'admin' }
  };
  const existingActions = {
    analyst: { responsibility: 'analysis-send-responsibility-term', parts: 'analysis-send-parts-delay-term', dossierDownload: null },
    supervision: { dossier: 'send-webauthn-token', responsibility: 'supervision-send-responsibility-term', parts: 'supervision-send-parts-delay-term', dossierDownload: 'download-webauthn-dossier' },
    admin: { dossier: 'admin-send-webauthn-token', responsibility: 'admin-send-responsibility-term', parts: 'admin-send-parts-delay-term', dossierDownload: 'admin-download-webauthn-dossier' }
  };
  function stateLabel(accepted, dateString) {
    if (!accepted) return 'Pendente';
    if (!dateString) return 'Termo assinado digitalmente';
    const when = new Date(dateString);
    const formatted = Number.isFinite(when.getTime()) ? when.toLocaleString('pt-BR') : '';
    return formatted ? `Termo assinado digitalmente em ${formatted}` : 'Termo assinado digitalmente';
  }
  function elt(tag, className, label) {
    const node = document.createElement(tag);
    if (className) node.className = className;
    if (label != null) node.textContent = label;
    return node;
  }
  function updateStatus(root, name, accepted, acceptedAt, sentAt) {
    const row = root.querySelector(`[data-doc-kind="${name}"]`);
    if (!row) return;
    const badge = row.querySelector('.nh-doc-status');
    badge.textContent = stateLabel(accepted, acceptedAt);
    badge.dataset.signed = String(Boolean(accepted));
    const hint = row.querySelector('.nh-doc-description');
    if (accepted) hint.textContent = 'Aceite digital registrado. Reenvio bloqueado; documento permanece disponível.';
    else hint.textContent = sentAt ? 'Enviado ao associado. Aguardando aceite digital.' : 'Ainda não possui aceite digital confirmado.';
    const send = row.querySelector('[data-doc-action="send"]');
    if (send) { send.disabled = Boolean(accepted) || send.dataset.ineligible === 'true'; send.textContent = accepted ? 'Assinado' : 'Enviar para aceite'; }
  }
  function sendThroughExisting(buttonId, id) {
    const button = document.getElementById(buttonId);
    if (!button || button.disabled || (button.dataset.inspectionId && button.dataset.inspectionId !== id)) return false;
    button.click();
    return true;
  }
  function render({ rootId, item, role, api, sendDossier, downloadDossier }) {
    const root = document.getElementById(rootId);
    if (!root || !item?.id || !roleRoutes[role]) return;
    root.dataset.inspectionId = item.id;
    const actions = existingActions[role];
    root.replaceChildren();
    const heading = elt('strong', '', 'Termos e contratos · envio e aceite digital');
    const description = elt('p', 'nh-doc-center-note', 'Cada documento tem seu próprio aceite. O envio não equivale à assinatura.');
    root.append(heading, description);
    const all = elt('div', 'nh-doc-grid'); root.append(all);
    const eligible = item.status === 'APPROVED' && Boolean(item.registrationCompletedAt) && !item.contractChangePending && Boolean(item.publicUrl);
    const docs = [
      { kind: 'dossier', name: 'Contrato e dossiê da vistoria', accepted: Boolean(item.digitalAcceptedAt), acceptedAt: item.digitalAcceptedAt,
        applicable: item.requestType === 'NEW_INSPECTION', canSend: eligible, canDownload: Boolean(item.digitalAcceptedAt) },
      { kind: 'responsibility', name: 'Termo de responsabilidade', accepted: false, canSend: true },
      { kind: 'parts', name: 'Termo de ciência de peças', accepted: false, canSend: true }
    ];
    if (item.contractChangePending) docs.push({ kind: 'change', name: 'Confirmação de alteração contratual', accepted: false, canSend: Boolean(item.contractChangeWhatsappUrl || item.contractChangeConfirmationUrl), special: true });
    docs.forEach(doc => {
      const row = elt('div', 'nh-doc-row'); row.dataset.docKind = doc.kind;
      const texts = elt('div', 'nh-doc-texts');
      const title = elt('strong', '', doc.name);
      const badge = elt('span', 'nh-doc-status', doc.applicable === false ? 'Não aplicável' : (doc.special ? 'Pendente' : stateLabel(doc.accepted, doc.acceptedAt)));
      badge.dataset.signed = String(doc.accepted);
      const help = elt('small', 'nh-doc-description', doc.applicable === false ? 'Este tipo de vistoria não possui contrato de adesão novo.' : doc.accepted ? 'Aceite digital registrado no sistema.' : doc.special ? 'Aguardando a confirmação do novo valor pelo associado.' : 'Ainda não possui aceite digital confirmado.');
      texts.append(title, badge, help);
      const controls = elt('div', 'nh-doc-actions');
      const send = elt('button', 'outline', doc.special ? 'Enviar confirmação' : doc.accepted ? 'Assinado' : 'Enviar para aceite');
      send.type = 'button'; send.dataset.docAction = 'send';
      send.disabled = doc.applicable === false || !doc.canSend || doc.accepted;
      send.dataset.ineligible = String(!doc.canSend || doc.applicable === false);
      send.addEventListener('click', () => {
        if (root.dataset.inspectionId !== item.id) return;
        if (doc.kind === 'dossier') {
          if (role === 'analyst') sendDossier?.(item);
          else sendThroughExisting(actions.dossier, item.id);
        } else if (doc.kind === 'change') {
          const link = item.contractChangeWhatsappUrl || item.contractChangeConfirmationUrl;
          if (link) window.open(link, '_blank', 'noopener,noreferrer');
        } else {
          if (!sendThroughExisting(actions[doc.kind], item.id)) {
            const err = root.querySelector('.nh-doc-center-note');
            if (err) err.textContent = 'Aguarde carregar o documento e tente novamente.';
          }
        }
      });
      controls.append(send);
      if (doc.kind === 'dossier') {
        const down = elt('button', 'outline', 'Baixar dossiê'); down.type = 'button'; down.disabled = !doc.canDownload;
        down.addEventListener('click', () => {
          if (root.dataset.inspectionId !== item.id) return;
          if (role === 'analyst') downloadDossier?.(item, down);
          else sendThroughExisting(actions.dossierDownload, item.id);
        });
        controls.append(down);
      }
      row.append(texts, controls); all.append(row);
    });
    // Termos independentes continuam consultáveis também em outras modalidades de vistoria.
    const path = roleRoutes[role];
    const safeId = encodeURIComponent(item.id);
    const syncOne = async (kind, url) => {
      try {
        const info = await api(url);
        if (root.dataset.inspectionId !== item.id) return;
        updateStatus(root, kind, Boolean(info?.accepted), info?.acceptedAt, info?.sentAt);
      } catch (error) {
        if (root.dataset.inspectionId !== item.id) return;
        const row = root.querySelector(`[data-doc-kind="${kind}"]`);
        if (row) row.querySelector('.nh-doc-description').textContent = 'Não foi possível confirmar o status no servidor. Atualize e tente novamente.';
      }
    };
    syncOne('responsibility', `/api/${path.responsibility}/inspections/${safeId}/responsibility-term`);
    syncOne('parts', `/api/${path.parts}/inspections/${safeId}/parts-delay-term`);
  }
  window.NHInspectionDocuments = { render, stateLabel };
})();
