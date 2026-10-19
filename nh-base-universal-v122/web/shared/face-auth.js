(() => {
  const backend = path => window.NH_API?.backend?.(path) || path;

  function errorMessage(body, fallback) {
    return body?.message || body?.error || body?.detail?.message || (typeof body?.detail === 'string' ? body.detail : '') || fallback;
  }

  async function jsonFetch(path, options = {}) {
    const response = await fetch(backend(path), { cache: 'no-store', ...options });
    const type = response.headers.get('content-type') || '';
    const body = type.includes('application/json') ? await response.json().catch(() => null) : await response.text().catch(() => '');
    if (!response.ok) {
      const error = new Error(errorMessage(body, `Não foi possível concluir a validação (${response.status}).`));
      error.status = response.status;
      throw error;
    }
    return body;
  }

  function ensureStyles() {
    if (document.getElementById('nh-face-auth-style')) return;
    const style = document.createElement('style');
    style.id = 'nh-face-auth-style';
    style.textContent = `
      .nh-face-auth-backdrop{position:fixed;inset:0;z-index:100000;background:rgba(4,9,42,.82);backdrop-filter:blur(6px);display:grid;place-items:center;padding:18px}
      .nh-face-auth-card{width:min(520px,100%);max-height:96vh;overflow:auto;background:#fff;border-radius:22px;padding:20px;box-shadow:0 28px 80px rgba(0,0,0,.35);font-family:inherit;color:#111827}
      .nh-face-auth-head{display:flex;align-items:center;gap:12px;margin-bottom:12px}.nh-face-auth-logo{width:46px;height:46px;object-fit:contain}
      .nh-face-auth-head strong{display:block;color:#070c40;font-size:1.12rem}.nh-face-auth-head span{display:block;color:#64748b;font-size:.86rem;margin-top:2px}
      .nh-face-auth-copy{margin:8px 0 14px;line-height:1.48;color:#334155}.nh-face-auth-video-wrap{position:relative;background:#070c40;border-radius:18px;overflow:hidden;aspect-ratio:4/3}
      .nh-face-auth-video{width:100%;height:100%;object-fit:cover;transform:scaleX(-1);display:block}.nh-face-auth-guide{position:absolute;inset:10% 22%;border:3px solid rgba(255,255,255,.86);border-radius:48% 48% 46% 46%;box-shadow:0 0 0 999px rgba(4,9,42,.12);pointer-events:none}
      .nh-face-auth-status{min-height:24px;margin:12px 0 4px;padding:10px 12px;border-radius:12px;background:#f1f5f9;color:#334155;font-size:.92rem}.nh-face-auth-status.error{background:#fff1f2;color:#9f1239}.nh-face-auth-status.ok{background:#ecfdf5;color:#065f46}
      .nh-face-auth-actions{display:flex;gap:10px;justify-content:flex-end;margin-top:14px;flex-wrap:wrap}.nh-face-auth-actions button{border:0;border-radius:12px;padding:11px 16px;font:inherit;font-weight:700;cursor:pointer}
      .nh-face-auth-cancel{background:#e2e8f0;color:#0f172a}.nh-face-auth-confirm{background:#070c40;color:#fff;min-width:180px}.nh-face-auth-actions button:disabled{opacity:.58;cursor:wait}
      .nh-face-auth-note{font-size:.78rem;color:#64748b;margin-top:10px;line-height:1.4}
    `;
    document.head.appendChild(style);
  }

  function stopStream(stream) {
    try { stream?.getTracks?.().forEach(track => track.stop()); } catch (_) {}
  }

  function b64ToBytes(value) {
    const normalized = String(value || '').replace(/-/g, '+').replace(/_/g, '/');
    const padded = normalized + '='.repeat((4 - normalized.length % 4) % 4);
    const binary = atob(padded);
    return Uint8Array.from(binary, char => char.charCodeAt(0));
  }

  function bytesToB64(value) {
    const bytes = new Uint8Array(value);
    let binary = '';
    for (const byte of bytes) binary += String.fromCharCode(byte);
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
  }

  async function validatePasskey(loginData, username) {
    if (!window.PublicKeyCredential || !navigator.credentials) {
      throw new Error('Este navegador não oferece passkey/WebAuthn. Use Chrome, Edge ou Safari em um aparelho com biometria, PIN ou bloqueio seguro.');
    }
    if (!window.isSecureContext) {
      throw new Error('A biometria/passkey exige uma conexão HTTPS segura.');
    }

    if (!loginData.passkeyEnrolled) {
      const options = loginData.passkeyRegistration;
      if (!options?.challenge || !options?.rpId || !options?.userId) throw new Error('O servidor não iniciou o cadastro da passkey.');
      const created = await navigator.credentials.create({ publicKey: {
        challenge: b64ToBytes(options.challenge),
        rp: { id: options.rpId, name: options.rpName || 'Empresa Cliente' },
        user: { id: b64ToBytes(options.userId), name: options.userName || username, displayName: options.userDisplayName || username },
        pubKeyCredParams: [{ type: 'public-key', alg: -7 }, { type: 'public-key', alg: -257 }],
        authenticatorSelection: { authenticatorAttachment: 'platform', residentKey: 'preferred', requireResidentKey: false, userVerification: 'required' },
        timeout: Number(options.timeoutMs || 120000),
        attestation: 'none',
        excludeCredentials: Array.isArray(options.excludeCredentialIds) ? options.excludeCredentialIds.map(id => ({ type: 'public-key', id: b64ToBytes(id) })) : []
      }});
      if (!created) throw new Error('A biometria/passkey não foi cadastrada.');
      const attestationResponse = created.response;
      const authenticatorData = typeof attestationResponse.getAuthenticatorData === 'function'
        ? attestationResponse.getAuthenticatorData()
        : null;
      const publicKey = typeof attestationResponse.getPublicKey === 'function'
        ? attestationResponse.getPublicKey()
        : null;
      const publicKeyAlgorithm = typeof attestationResponse.getPublicKeyAlgorithm === 'function'
        ? attestationResponse.getPublicKeyAlgorithm()
        : null;
      return jsonFetch('/api/auth/passkey/register/finish', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          username, rawId: bytesToB64(created.rawId), type: created.type,
          clientDataJSON: bytesToB64(attestationResponse.clientDataJSON),
          attestationObject: bytesToB64(attestationResponse.attestationObject),
          authenticatorData: authenticatorData ? bytesToB64(authenticatorData) : null,
          publicKey: publicKey ? bytesToB64(publicKey) : null,
          publicKeyAlgorithm: Number.isFinite(Number(publicKeyAlgorithm)) ? Number(publicKeyAlgorithm) : null
        })
      });
    }

    const options = loginData.passkeyAssertion;
    const credentialIds = Array.isArray(options?.credentialIds) ? options.credentialIds : (options?.credentialId ? [options.credentialId] : []);
    if (!options?.challenge || !options?.rpId || credentialIds.length === 0) throw new Error('O servidor não iniciou a confirmação da passkey.');
    const assertion = await navigator.credentials.get({ publicKey: {
      challenge: b64ToBytes(options.challenge),
      rpId: options.rpId,
      allowCredentials: credentialIds.map(id => ({ type: 'public-key', id: b64ToBytes(id) })),
      userVerification: 'required',
      timeout: Number(options.timeoutMs || 120000)
    }});
    if (!assertion) throw new Error('A biometria/passkey não confirmou o usuário.');
    return jsonFetch('/api/auth/passkey/assertion/finish', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        username, rawId: bytesToB64(assertion.rawId), type: assertion.type,
        clientDataJSON: bytesToB64(assertion.response.clientDataJSON),
        authenticatorData: bytesToB64(assertion.response.authenticatorData),
        signature: bytesToB64(assertion.response.signature)
      })
    });
  }

  function capture(video) {
    return new Promise((resolve, reject) => {
      const sourceW = video.videoWidth || 640;
      const sourceH = video.videoHeight || 480;
      if (!sourceW || !sourceH) return reject(new Error('A câmera ainda não está pronta. Aguarde um instante e tente novamente.'));
      const width = Math.min(720, sourceW);
      const height = Math.max(1, Math.round(sourceH * (width / sourceW)));
      const canvas = document.createElement('canvas');
      canvas.width = width;
      canvas.height = height;
      const ctx = canvas.getContext('2d', { alpha: false });
      ctx.translate(width, 0);
      ctx.scale(-1, 1);
      ctx.drawImage(video, 0, 0, width, height);
      canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error('Não foi possível capturar a imagem da câmera.')), 'image/jpeg', 0.86);
    });
  }

  async function validateFace(loginData) {
    ensureStyles();
    let stream;
    let enrolled = Boolean(loginData.faceEnrolled);
    const root = document.createElement('div');
    root.className = 'nh-face-auth-backdrop';
    root.innerHTML = `
      <div class="nh-face-auth-card" role="dialog" aria-modal="true" aria-label="Validação facial">
        <div class="nh-face-auth-head"><img class="nh-face-auth-logo" src="/assets/identidade-pendente.svg" alt=""><div><strong>Reconhecimento facial do Administrador</strong><span>Confirmação frontal de identidade</span></div></div>
        <p class="nh-face-auth-copy"></p>
        <div class="nh-face-auth-video-wrap"><video class="nh-face-auth-video" playsinline muted autoplay></video><div class="nh-face-auth-guide"></div></div>
        <div class="nh-face-auth-status">Abrindo a câmera frontal…</div>
        <p class="nh-face-auth-note">A câmera frontal é usada somente para confirmar o Administrador. Olhe diretamente para a câmera; não é necessário virar o rosto, olhar para os lados, para cima ou para baixo.</p>
        <div class="nh-face-auth-actions"><button type="button" class="nh-face-auth-cancel">Cancelar</button><button type="button" class="nh-face-auth-confirm" disabled>Continuar</button></div>
      </div>`;
    document.body.appendChild(root);
    const video = root.querySelector('video');
    const copy = root.querySelector('.nh-face-auth-copy');
    const status = root.querySelector('.nh-face-auth-status');
    const cancel = root.querySelector('.nh-face-auth-cancel');
    const confirm = root.querySelector('.nh-face-auth-confirm');

    const refreshCopy = () => {
      copy.innerHTML = enrolled
        ? '<strong>Senha confirmada.</strong> Olhe de frente para a câmera para liberar o acesso administrativo.'
        : '<strong>Cadastro facial do Administrador.</strong> Olhe de frente para a câmera. Uma única captura frontal será usada como referência.';
      confirm.textContent = enrolled ? 'Validar e entrar' : 'Cadastrar rosto e entrar';
    };
    refreshCopy();

    return await new Promise(async (resolve, reject) => {
      let finished = false;
      const close = (error, value) => {
        if (finished) return;
        finished = true;
        stopStream(stream);
        root.remove();
        if (error) reject(error); else resolve(value);
      };
      cancel.addEventListener('click', () => close(new Error('Validação facial cancelada.')));
      try {
        if (!navigator.mediaDevices?.getUserMedia) throw new Error('Este navegador não disponibiliza câmera segura para validação facial.');
        stream = await navigator.mediaDevices.getUserMedia({ audio: false, video: { facingMode: 'user', width: { ideal: 720 }, height: { ideal: 540 } } });
        video.srcObject = stream;
        await video.play();
        status.className = 'nh-face-auth-status ok';
        status.textContent = 'Câmera pronta. Olhe de frente e centralize o rosto dentro da marcação.';
        confirm.disabled = false;
      } catch (error) {
        status.className = 'nh-face-auth-status error';
        status.textContent = 'Não foi possível acessar a câmera. Libere a permissão da câmera para entrar nesta conta.';
        confirm.disabled = true;
        return;
      }

      confirm.addEventListener('click', async () => {
        confirm.disabled = true;
        cancel.disabled = true;
        status.className = 'nh-face-auth-status';
        status.textContent = enrolled ? 'Confirmando identidade facial…' : 'Cadastrando referência facial…';
        try {
          const blob = await capture(video);
          const form = new FormData();
          form.set('challenge', loginData.faceChallenge);
          form.set('image', blob, 'face.jpg');
          const path = enrolled ? '/api/auth/face/verify' : '/api/auth/face/register';
          const result = await jsonFetch(path, { method: 'POST', body: form });
          status.className = 'nh-face-auth-status ok';
          status.textContent = enrolled ? 'Identidade confirmada.' : 'Rosto cadastrado e identidade confirmada.';
          setTimeout(() => close(null, result), 180);
        } catch (error) {
          if (error.status === 409 && enrolled) {
            enrolled = false;
            refreshCopy();
            status.className = 'nh-face-auth-status';
            status.textContent = 'A referência facial precisa ser cadastrada novamente. Posicione o titular diante da câmera e continue.';
          } else {
            status.className = 'nh-face-auth-status error';
            status.textContent = error.message || 'Não foi possível confirmar o rosto.';
          }
          confirm.disabled = false;
          cancel.disabled = false;
        }
      });
    });
  }

  async function login(username, password) {
    const body = await jsonFetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username, password })
    });
    // v83.19: facial removida do login. Somente o ADMIN confirma com passkey;
    // todos os demais perfis recebem o acesso logo após usuário e senha.
    if (body?.passkeyRequired) return validatePasskey(body, username);
    return body;
  }

  window.NH_FACE_AUTH = Object.freeze({ login });
})();
