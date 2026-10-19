const $ = (id) => document.getElementById(id);
const token = new URLSearchParams(location.search).get('token');

const VEHICLE_PROFILES = {
  MOTORCYCLE: {
    title: 'Moto ou veículo com menos de 4 rodas',
    photos: [
      { label: 'Selfie mostrando a placa da moto', guide: '/assets/inspection-guides/moto-01-selfie-placa.webp', facingMode: 'user' },
      { label: 'Frente', guide: '/assets/inspection-guides/moto-02-frente.webp' },
      { label: 'Lateral esquerda', guide: '/assets/inspection-guides/moto-03-lateral-esquerda.webp' },
      { label: 'Traseira', guide: '/assets/inspection-guides/moto-04-traseira.webp' },
      { label: 'Lateral direita', guide: '/assets/inspection-guides/moto-05-lateral-direita.webp' },
      { label: 'Chassi', guide: '/assets/inspection-guides/moto-06-chassi.webp' },
      { label: 'Odômetro', guide: '/assets/inspection-guides/moto-07-odometro.webp' }
    ],
    videoGuide: '/assets/guia-vistoria-moto.svg',
    videoInstruction: 'Com o veículo ligado, inicie a gravação mostrando o chassi legível. Fale seu nome completo, o dia, o mês e o ano. Mostre os 4 lados da motocicleta detalhadamente, dando um giro de 360° em torno do veículo. Finalize mostrando o odômetro com o KM total. O vídeo final da vistoria terá no máximo 1 minuto e 30 segundos.'
  },
  FOUR_WHEELS_OR_MORE: {
    title: 'Carro, utilitário ou veículo com 4 rodas ou mais',
    photos: [
      { label: 'Selfie na frente do carro', guide: '/assets/inspection-guides/carro-01-selfie-frente.webp', facingMode: 'user' },
      { label: 'Frente do carro', guide: '/assets/inspection-guides/carro-02-frente.webp' },
      { label: 'Frente do carro com motor e placa', guide: '/assets/inspection-guides/carro-03-motor-placa.webp' },
      { label: 'Para-brisa', guide: '/assets/inspection-guides/carro-04-parabrisa.webp' },
      { label: 'Caixa de roda dianteira — lado direito', guide: '/assets/inspection-guides/carro-05-roda-dianteira-direita.webp' },
      { label: 'Lateral direita', guide: '/assets/inspection-guides/carro-06-lateral-direita.webp' },
      { label: 'Caixa de roda traseira — lado direito', guide: '/assets/inspection-guides/carro-07-roda-traseira-direita.webp' },
      { label: 'Traseira do veículo', guide: '/assets/inspection-guides/carro-08-traseira.webp' },
      { label: 'Mala aberta', guide: '/assets/inspection-guides/carro-09-mala-aberta.webp' },
      { label: 'Caixa de roda traseira — lado esquerdo', guide: '/assets/inspection-guides/carro-10-roda-traseira-esquerda.webp' },
      { label: 'Lateral esquerda', guide: '/assets/inspection-guides/carro-11-lateral-esquerda.webp' },
      { label: 'Caixa de roda dianteira — lado esquerdo', guide: '/assets/inspection-guides/carro-12-roda-dianteira-esquerda.webp' },
      { label: 'Odômetro mostrando o KM total', guide: '/assets/inspection-guides/carro-13-odometro.webp' },
      { label: 'Foto interna mostrando o painel completo', guide: '/assets/inspection-guides/carro-14-painel-interno.webp' },
      { label: 'Foto do chassi', guide: '/assets/inspection-guides/carro-15-chassi.webp' }
    ],
    videoGuide: '/assets/guia-vistoria-carro.svg',
    videoInstruction: 'Com o veículo ligado, inicie a gravação mostrando o chassi legível. Fale seu nome completo, o dia, o mês e o ano. Mostre os 4 lados do veículo detalhadamente, dando um giro de 360° em torno do veículo. Finalize abrindo a porta do motorista e mostrando o odômetro com o KM total. O vídeo final da vistoria terá no máximo 1 minuto e 30 segundos.'
  }
};

const allowedVideoTypes = new Set([
  'video/mp4',
  'video/quicktime',
  'video/webm',
  'video/3gpp',
  'video/x-m4v'
]);

const VIDEO_MAX_DURATION_SECONDS = 90;
const VIDEO_AUTO_STOP_SECONDS = 90;
// Mantemos o perfil atual de qualidade. O vídeo não possui limite mínimo/máximo de MB;
// a única trava de gravação é a duração operacional de 90 segundos.
const VIDEO_TARGET_VIDEO_BITRATE = 1_280_000;
const VIDEO_TARGET_AUDIO_BITRATE = 32_000;
const RECORDING_START_GRACE_MS = 4000;
const RECORDING_MUTE_CONFIRM_MS = 2500;
const RECORDING_FRAME_START_GRACE_MS = 7000;
const RECORDING_FRAME_STALL_MS = 3500;
const IS_IOS_WEBKIT = /iP(?:hone|ad|od)/i.test(navigator.userAgent || '')
  || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
// V15: em iPhone/iPad não usamos MediaRecorder/WebRTC para a gravação final.
// O WebKit já apresentou falhas em que o áudio continua e a trilha de vídeo
// para de receber quadros. O capture nativo usa o gravador do próprio iOS.
const USE_NATIVE_IOS_VIDEO_CAPTURE = IS_IOS_WEBKIT;
// V17: em celulares abrimos a câmera nativa também para as fotos. Além de ocupar
// a tela inteira do aparelho (igual ao gravador nativo do vídeo), isso entrega a
// resolução original da câmera e deixa flash/foco/exposição sob controle do sistema.
const IS_ANDROID = /Android/i.test(navigator.userAgent || '');
const USE_NATIVE_MOBILE_PHOTO_CAPTURE = IS_IOS_WEBKIT || IS_ANDROID;
const PHOTO_MAX_BYTES = 15 * 1024 * 1024;

// V11: no iOS o MediaRecorder ligado diretamente à track da câmera pode manter o
// áudio por 90 s e parar de produzir quadros de vídeo no meio da gravação. Para
// desacoplar o encoder dessa track, gravamos um canvas alimentado pela prévia.
const IOS_RECORD_CANVAS_WIDTH = 960;
const IOS_RECORD_CANVAS_HEIGHT = 540;
const IOS_RECORD_CANVAS_FPS = 10;

let request = null;
let inspectionProfile = VEHICLE_PROFILES.FOUR_WHEELS_OR_MORE;
let labels = [];
let photoFiles = [];
let photoPreviewUrls = [];
let pendingGuideAction = null;
let videoFile = null;
let videoPreviewUrl = null;
let activeStream = null;
let mediaRecorder = null;
let recordedChunks = [];
let recordedVideoBytes = 0;
let videoDurationSeconds = null;
let currentPhotoIndex = null;
let pendingNativePhotoIndex = null;
let captureMode = null;
let selfieMirrorCorrection = false;
let activeFacingMode = 'environment';
let recordingTimer = null;
let recordingHardStopTimer = null;
let recordingElapsedMs = 0;
let recordingActiveSince = null;
let recordingStopRequested = false;
let recordingInterrupted = false;
let recordingMustRestart = false;
let recordingInterruptionReason = '';
let recordingWakeLock = null;
let recordingStartInProgress = false;
let recordingStartedAt = 0;
let recordingMuteTimer = null;
let recordingFrameWatchdogTimer = null;
let recordingFrameCallbackId = null;
let recordingLastVideoFrameAt = 0;
let recordingLastPreviewTime = -1;
let recordingLastFrameMediaTime = -1;
let recordingLastPresentedFrames = -1;
let recordingLastDecodedFrames = -1;
let recordingCameraRecoveryInProgress = false;
let recordingCameraRecoveryCount = 0;
let recordingOutputStream = null;
let recordingOutputOwnedTracks = [];
let recordingCanvas = null;
let recordingCanvasContext = null;
let recordingCanvasTrack = null;
let recordingCanvasTimer = null;
let recordingCanvasFrameCount = 0;
let recordingCanvasLastDrawAt = 0;
let torchSupported = false;
let torchEnabled = false;
let torchMode = 'auto';
let torchMonitorTimer = null;
let torchMonitorBusy = false;
let torchDarkSamples = 0;
let torchBrightSamples = 0;
let focusModesSupported = [];
let focusPointSupported = false;
let manualFocusSupported = false;
let focusDistanceCapability = null;
let focusBusy = false;
let focusReticleTimer = null;
let manualFocusTimer = null;
const TORCH_AUTO_DARK_THRESHOLD = 72;
const TORCH_AUTO_BRIGHT_THRESHOLD = 108;
const TORCH_AUTO_INTERVAL_MS = 650;
const TORCH_AUTO_DARK_SAMPLES = 2;
const TORCH_AUTO_BRIGHT_SAMPLES = 3;
let discardRecording = false;
let signatureHasInk = false;
let vehicleDocumentFile = null;
let identityDocumentFrontFile = null;
let identityDocumentBackFile = null;
let signatureDrawing = false;
let signatureLastPoint = null;

const DRAFT_DB_NAME = 'nh-retrato-drafts';
const DRAFT_STORE_NAME = 'inspection-drafts';
const DRAFT_MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000;
// Arquivos de vídeo nativos do iPhone podem ter dezenas/centenas de MB. Não
// duplicamos vídeos grandes no IndexedDB: eles permanecem no File original
// enquanto a página estiver aberta e são enviados em blocos de 1 MB.
const DRAFT_MAX_VIDEO_BYTES = 24 * 1024 * 1024;
let draftDatabasePromise = null;
let draftSaveTimer = null;
let restoredSignatureBlob = null;
let hasRestoredDraft = false;
let draftSaveQueue = Promise.resolve();
const draftBinaryCache = new WeakMap();

const UPLOAD_CHUNK_BYTES = 1 * 1024 * 1024;
const UPLOAD_MAX_ATTEMPTS = 6;
const UPLOAD_RETRY_BASE_MS = 2000;

function friendlyNetworkMessage(error, fallback) {
  const raw = String(error?.message || '').trim();
  if (!navigator.onLine) {
    return 'A internet está indisponível. Seus arquivos continuam salvos neste aparelho para tentar novamente.';
  }
  if ([502, 503, 504].includes(Number(error?.status))) {
    return 'O servidor ficou temporariamente indisponível durante o envio. As partes já confirmadas continuam salvas. Aguarde alguns segundos e toque em “Continuar envio”.';
  }
  if (!raw || /load failed|failed to fetch|networkerror|network request failed/i.test(raw)) {
    return fallback;
  }
  return raw;
}

function retryDelayMilliseconds(error, attempt) {
  const status = Number(error?.status || 0);
  if ([502, 503, 504].includes(status) || error?.name === 'AbortError') {
    return Math.min(30000, 4000 * attempt);
  }
  return UPLOAD_RETRY_BASE_MS * attempt;
}

function openDraftDatabase() {
  if (!('indexedDB' in window)) {
    return Promise.reject(new Error('Armazenamento local não disponível.'));
  }
  if (draftDatabasePromise) return draftDatabasePromise;

  draftDatabasePromise = new Promise((resolve, reject) => {
    const requestDb = indexedDB.open(DRAFT_DB_NAME, 1);
    requestDb.onupgradeneeded = () => {
      const database = requestDb.result;
      if (!database.objectStoreNames.contains(DRAFT_STORE_NAME)) {
        database.createObjectStore(DRAFT_STORE_NAME, { keyPath: 'token' });
      }
    };
    requestDb.onsuccess = () => resolve(requestDb.result);
    requestDb.onerror = () => reject(requestDb.error || new Error('Não foi possível abrir o armazenamento local.'));
    requestDb.onblocked = () => reject(new Error('O armazenamento local está bloqueado por outra aba.'));
  });
  return draftDatabasePromise;
}

async function runDraftTransaction(mode, operation) {
  const database = await openDraftDatabase();
  return new Promise((resolve, reject) => {
    const transaction = database.transaction(DRAFT_STORE_NAME, mode);
    const store = transaction.objectStore(DRAFT_STORE_NAME);
    let result;
    try {
      result = operation(store);
    } catch (error) {
      reject(error);
      return;
    }
    transaction.oncomplete = () => resolve(result?.result);
    transaction.onerror = () => reject(transaction.error || result?.error || new Error('Falha ao salvar o rascunho.'));
    transaction.onabort = () => reject(transaction.error || new Error('O salvamento do rascunho foi cancelado.'));
  });
}

function fileReaderArrayBuffer(blob) {
  return new Promise((resolve, reject) => {
    if (typeof FileReader === 'undefined') {
      reject(new Error('FileReader indisponível.'));
      return;
    }
    const reader = new FileReader();
    reader.onload = () => resolve(reader.result);
    reader.onerror = () => reject(reader.error || new Error('Falha ao ler o arquivo local.'));
    reader.onabort = () => reject(new Error('A leitura do arquivo foi interrompida.'));
    reader.readAsArrayBuffer(blob);
  });
}

function validateLocalBuffer(buffer, expectedSize) {
  if (!(buffer instanceof ArrayBuffer)) return null;
  if (Number(expectedSize) > 0 && buffer.byteLength !== Number(expectedSize)) return null;
  if (Number(expectedSize) > 0 && buffer.byteLength <= 0) return null;
  return buffer;
}

async function readBlobArrayBufferReliable(blob) {
  if (!blob) throw new Error('Arquivo local indisponível.');
  const expectedSize = Number(blob.size || 0);
  const cached = draftBinaryCache.get(blob);
  const validCached = validateLocalBuffer(cached, expectedSize);
  if (validCached) return validCached;

  const readers = [];
  if (typeof blob.arrayBuffer === 'function') {
    readers.push(async () => blob.arrayBuffer());
  }
  readers.push(async () => fileReaderArrayBuffer(blob));
  readers.push(async () => {
    const objectUrl = URL.createObjectURL(blob);
    try {
      const response = await fetch(objectUrl, { cache: 'no-store' });
      if (!response.ok) throw new Error('Falha ao abrir a cópia local.');
      return await response.arrayBuffer();
    } finally {
      URL.revokeObjectURL(objectUrl);
    }
  });

  let lastError = null;
  for (const read of readers) {
    try {
      const buffer = validateLocalBuffer(await read(), expectedSize);
      if (buffer) {
        draftBinaryCache.set(blob, buffer);
        return buffer;
      }
      lastError = new Error('A leitura local retornou uma quantidade incompleta de bytes.');
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError || new Error('Não foi possível ler o arquivo local.');
}

async function serializedFile(file) {
  if (!file) return null;
  const base = {
    name: file.name || 'arquivo',
    type: file.type || 'application/octet-stream',
    lastModified: file.lastModified || Date.now(),
    size: Number(file.size || 0)
  };

  // Não copie vídeos grandes inteiros para RAM/IndexedDB. Isso era especialmente
  // perigoso no iPhone quando o vídeo passou a não possuir limite de MB.
  if (/^video\//i.test(base.type) && base.size > DRAFT_MAX_VIDEO_BYTES) {
    return { ...base, volatileOnly: true };
  }

  try {
    // Para arquivos pequenos mantemos bytes persistentes, evitando o problema do
    // WebKit em que alguns Blobs restaurados perdem o backing stream.
    const bytes = await readBlobArrayBufferReliable(file);
    return { ...base, bytes };
  } catch (_) {
    return { ...base, blob: file };
  }
}

function restoredFile(entry, fallbackName, fallbackType) {
  if (!entry?.bytes && !entry?.blob) return null;
  const source = entry.bytes || entry.blob;
  const type = entry.type || entry.blob?.type || fallbackType;
  try {
    const file = new File([source], entry.name || fallbackName, {
      type,
      lastModified: entry.lastModified || Date.now()
    });
    if (entry.bytes instanceof ArrayBuffer) {
      draftBinaryCache.set(file, entry.bytes);
    }
    return file;
  } catch (_) {
    const blob = entry.blob || new Blob([entry.bytes], { type });
    blob.name = entry.name || fallbackName;
    if (entry.bytes instanceof ArrayBuffer) {
      draftBinaryCache.set(blob, entry.bytes);
    }
    return blob;
  }
}

async function readDraft() {
  if (!token) return null;
  try {
    const database = await openDraftDatabase();
    return await new Promise((resolve, reject) => {
      const transaction = database.transaction(DRAFT_STORE_NAME, 'readonly');
      const operation = transaction.objectStore(DRAFT_STORE_NAME).get(token);
      operation.onsuccess = () => resolve(operation.result || null);
      operation.onerror = () => reject(operation.error || new Error('Não foi possível ler o rascunho.'));
    });
  } catch (_) {
    return null;
  }
}

async function removeDraft() {
  if (draftSaveTimer) {
    window.clearTimeout(draftSaveTimer);
    draftSaveTimer = null;
  }
  if (!token || !('indexedDB' in window)) return;
  try {
    await runDraftTransaction('readwrite', store => store.delete(token));
  } catch (_) {
    // A limpeza local não pode impedir a conclusão da vistoria.
  }
  hasRestoredDraft = false;
  restoredSignatureBlob = null;
  $('draft-panel').hidden = true;
}

function updateDraftPanel(text, kind = 'ok') {
  const panel = $('draft-panel');
  const status = $('draft-status');
  panel.hidden = false;
  panel.dataset.kind = kind;
  status.textContent = text;
}

async function requestPersistentStorage() {
  try {
    if (navigator.storage?.persist) {
      await navigator.storage.persist();
    }
  } catch (_) {
    // Nem todos os navegadores permitem solicitar persistência.
  }
}

function hasPreexistingDamageSelected() {
  return document.querySelector('input[name="preexisting-damage"]:checked')?.value === 'true';
}

function syncPreexistingDamageUi() {
  const present = hasPreexistingDamageSelected();
  const wrap = $('preexisting-damage-details-wrap');
  if (wrap) wrap.hidden = !present;
  const details = $('preexisting-damage-details');
  if (details) details.required = present;
  updateCaptureSummary();
}

function applyPreexistingDamageState(hasDamage, details = '') {
  const value = hasDamage ? 'true' : 'false';
  const radio = document.querySelector(`input[name="preexisting-damage"][value="${value}"]`);
  if (radio) radio.checked = true;
  if ($('preexisting-damage-details')) $('preexisting-damage-details').value = details || '';
  syncPreexistingDamageUi();
}

function saveDraftNow(reason = '') {
  if (!request || !token || ['COMPLETED', 'APPROVED', 'REJECTED'].includes(request.status)) {
    return Promise.resolve();
  }
  draftSaveQueue = draftSaveQueue
    .catch(() => undefined)
    .then(() => persistDraftSnapshot(reason));
  return draftSaveQueue;
}

async function persistDraftSnapshot(reason = '') {
  if (!request || ['COMPLETED', 'APPROVED', 'REJECTED'].includes(request.status)) return;
  if (!('indexedDB' in window)) {
    updateDraftPanel('Este navegador não permite manter uma cópia local. Não feche a página antes do envio.', 'warning');
    return;
  }

  try {
    const signatureBlob = signatureHasInk ? await exportSignatureBlob() : null;
    const [photos, video, vehicleDocument, identityDocumentFront, identityDocumentBack] = await Promise.all([
      Promise.all(photoFiles.map(serializedFile)),
      serializedFile(videoFile),
      serializedFile(vehicleDocumentFile),
      serializedFile(identityDocumentFrontFile),
      serializedFile(identityDocumentBackFile)
    ]);
    const snapshot = {
      token,
      requestType: request.requestType,
      vehicleType: request.vehicleType,
      labels: [...labels],
      photos,
      video,
      videoDurationSeconds: Number.isFinite(videoDurationSeconds) && videoDurationSeconds > 0 ? videoDurationSeconds : null,
      vehicleDocument,
      identityDocumentFront,
      identityDocumentBack,
      residenceAddress: $('residence-address').value.trim(),
      residenceFields: window.NHResidenceAddress?.values() || null,
      preexistingDamage: hasPreexistingDamageSelected(),
      preexistingDamageDetails: $('preexisting-damage-details')?.value.trim() || '',
      signature: signatureBlob,
      updatedAt: Date.now()
    };
    await runDraftTransaction('readwrite', store => store.put(snapshot));
    hasRestoredDraft = true;
    const time = new Date(snapshot.updatedAt).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' });
    updateDraftPanel(reason || `Rascunho salvo às ${time}. Se o envio falhar, basta tentar novamente.`, 'ok');
  } catch (error) {
    const quota = error?.name === 'QuotaExceededError' || /quota|storage/i.test(String(error?.message || ''));
    updateDraftPanel(
      quota
        ? 'O aparelho não possui espaço suficiente para guardar todos os arquivos. Mantenha esta página aberta até concluir o envio.'
        : 'Não foi possível atualizar a cópia local. Mantenha esta página aberta até concluir o envio.',
      'warning'
    );
  }
}

function scheduleDraftSave(delay = 500, reason = '') {
  if (draftSaveTimer) window.clearTimeout(draftSaveTimer);
  draftSaveTimer = window.setTimeout(() => {
    draftSaveTimer = null;
    saveDraftNow(reason);
  }, delay);
}

async function restoreDraftFromCache() {
  const draft = await readDraft();
  if (!draft) return;
  if (!draft.updatedAt || Date.now() - draft.updatedAt > DRAFT_MAX_AGE_MS) {
    await removeDraft();
    return;
  }
  if (draft.requestType !== request.requestType || draft.vehicleType !== request.vehicleType) {
    await removeDraft();
    return;
  }

  photoFiles = labels.map((label, index) => restoredFile(
    draft.photos?.[index],
    `${String(index + 1).padStart(2, '0')}-${slugify(label)}.jpg`,
    'image/jpeg'
  ));
  videoFile = restoredFile(draft.video, 'video-vistoria.webm', 'video/webm');
  const restoredVideoDuration = Number(draft.videoDurationSeconds);
  videoDurationSeconds = Number.isFinite(restoredVideoDuration) && restoredVideoDuration > 0
    ? restoredVideoDuration
    : null;
  vehicleDocumentFile = restoredFile(draft.vehicleDocument, 'crlv-veiculo.pdf', 'application/pdf');
  identityDocumentFrontFile = restoredFile(draft.identityDocumentFront || draft.identityDocument, 'rg-cnh-frente.pdf', 'application/pdf');
  identityDocumentBackFile = restoredFile(draft.identityDocumentBack, 'rg-cnh-verso.pdf', 'application/pdf');
  $('residence-address').value = request?.residenceAddress || draft.residenceAddress || '';
  if (!request?.residenceAddress) window.NHResidenceAddress?.restore(draft.residenceFields, draft.residenceAddress);
  const serverHasPreexistingDamage = Boolean(request?.preexistingDamage);
  applyPreexistingDamageState(
    serverHasPreexistingDamage || Boolean(draft.preexistingDamage),
    serverHasPreexistingDamage
      ? (request?.preexistingDamageDetails || '')
      : (draft.preexistingDamageDetails || '')
  );
  restoredSignatureBlob = draft.signature || null;
  hasRestoredDraft = photoFiles.some(Boolean)
    || Boolean(videoFile)
    || Boolean(vehicleDocumentFile)
    || Boolean(identityDocumentFrontFile)
    || Boolean(identityDocumentBackFile)
    || Boolean(restoredSignatureBlob)
    || Boolean(draft.residenceAddress)
    || Boolean(draft.residenceFields?.cep)
    || Boolean(draft.preexistingDamage)
    || Boolean(draft.preexistingDamageDetails);

  discardLocalCopiesAlreadyOnServer();
  hasRestoredDraft = photoFiles.some(Boolean)
    || Boolean(videoFile)
    || Boolean(vehicleDocumentFile)
    || Boolean(identityDocumentFrontFile)
    || Boolean(identityDocumentBackFile)
    || Boolean(restoredSignatureBlob)
    || Boolean((!request?.residenceAddress) && $('residence-address').value.trim())
    || Boolean((!request?.preexistingDamage) && hasPreexistingDamageSelected())
    || Boolean((!request?.preexistingDamageDetails) && $('preexisting-damage-details')?.value.trim());

  if (hasRestoredDraft) {
    $('start').textContent = 'Continuar vistoria salva →';
    updateDraftPanel(`Rascunho recuperado deste aparelho. Última atualização: ${new Date(draft.updatedAt).toLocaleString('pt-BR')}.`, 'ok');
    msg('Encontramos as fotos e o vídeo salvos neste aparelho. Você pode continuar sem repetir o que já registrou.', 'success');
  }
}

async function applyRestoredDraftToUi() {
  photoFiles.forEach((file, index) => {
    if (!file) return;
    if (photoPreviewUrls[index]) URL.revokeObjectURL(photoPreviewUrls[index]);
    photoPreviewUrls[index] = URL.createObjectURL(file);
    const image = $(`photo-preview-${index}`);
    if (image) {
      image.src = photoPreviewUrls[index];
      image.hidden = false;
    }
    const status = $(`photo-status-${index}`);
    if (status) status.textContent = 'Foto recuperada do rascunho deste aparelho.';
    const button = document.querySelector(`[data-photo-index="${index}"]`);
    if (button) {
      button.textContent = 'Refazer foto';
      button.classList.add('captured');
    }
  });

  if (videoFile) {
    if (videoPreviewUrl) URL.revokeObjectURL(videoPreviewUrl);
    videoPreviewUrl = URL.createObjectURL(videoFile);
    $('video-preview').src = videoPreviewUrl;
    $('video-preview').hidden = false;
    $('video-status').textContent = Number.isFinite(videoDurationSeconds)
      ? `Vídeo recuperado do rascunho (${Math.floor(videoDurationSeconds)}s · ${formatBytes(videoFile.size)}).`
      : `Vídeo recuperado do rascunho (${formatBytes(videoFile.size)}).`;
    $('record-video').textContent = 'Gravar novamente';
    $('record-video').classList.add('captured');
  }

  if (vehicleDocumentFile) {
    updateDocumentStatus('vehicle-document', vehicleDocumentFile, true);
  }
  if (identityDocumentFrontFile) {
    updateDocumentStatus('identity-document-front', identityDocumentFrontFile, true);
  }
  if (identityDocumentBackFile) {
    updateDocumentStatus('identity-document-back', identityDocumentBackFile, true);
  }
  if (restoredSignatureBlob) {
    await drawSignatureBlob(restoredSignatureBlob);
  }
  updateCaptureSummary();
}

async function drawSignatureBlob(blob) {
  const url = URL.createObjectURL(blob);
  try {
    const image = new Image();
    await new Promise((resolve, reject) => {
      image.onload = resolve;
      image.onerror = reject;
      image.src = url;
    });
    signatureContext.clearRect(0, 0, signatureCanvas.width, signatureCanvas.height);
    signatureContext.drawImage(image, 0, 0, signatureCanvas.width, signatureCanvas.height);
    signatureHasInk = true;
    signatureCanvas.classList.add('has-signature');
    $('signature-status').textContent = 'Assinatura recuperada do rascunho.';
    $('signature-status').classList.add('ok');
  } catch (_) {
    restoredSignatureBlob = null;
  } finally {
    URL.revokeObjectURL(url);
  }
}

function databaseCompletionConfirmed(body) {
  if (!body || !['COMPLETED', 'APPROVED', 'REJECTED'].includes(body.status)) return false;
  if (!Array.isArray(body.assets)) return false;

  const assetTypes = body.assets.filter(asset => asset.available).map(asset => asset.type);
  if (!assetTypes.includes('VIDEO')) return false;
  if (body.requestType !== 'NEW_INSPECTION') return true;

  const expectedPhotos = (VEHICLE_PROFILES[body.vehicleType] || VEHICLE_PROFILES.FOUR_WHEELS_OR_MORE).photos.length;
  const confirmedPhotos = assetTypes.filter(type => type === 'PHOTO').length;
  const confirmedIdentityDocuments = assetTypes.filter(type => type === 'IDENTITY_DOCUMENT').length;
  return confirmedPhotos >= expectedPhotos
    && assetTypes.includes('SIGNATURE')
    && assetTypes.includes('VEHICLE_DOCUMENT')
    && confirmedIdentityDocuments >= 2;
}

async function checkIfServerCompleted() {
  try {
    const response = await fetch(
      window.NH_API?.backend(`/api/public/inspections/${encodeURIComponent(token)}`) || `/api/public/inspections/${encodeURIComponent(token)}`,
      { cache: 'no-store' }
    );
    if (!response.ok) return false;
    const body = await response.json();
    request = body;
    if (databaseCompletionConfirmed(body)) {
      showComplete(body);
      return true;
    }
  } catch (_) {
    // O rascunho local permanece disponível para uma nova tentativa.
  }
  return false;
}

function configureInspectionProfile(vehicleType) {
  inspectionProfile = VEHICLE_PROFILES[vehicleType] || VEHICLE_PROFILES.FOUR_WHEELS_OR_MORE;
  labels = inspectionProfile.photos.map((photo) => photo.label);
  photoFiles = new Array(labels.length).fill(null);
  photoPreviewUrls = new Array(labels.length).fill(null);
  vehicleDocumentFile = null;
  identityDocumentFrontFile = null;
  identityDocumentBackFile = null;

  $('vehicle-guide-title').textContent = inspectionProfile.title;
  $('vehicle-guide-count').textContent = `${labels.length} fotos obrigatórias + 1 vídeo`;
  $('video-title').textContent = `${labels.length + 1}. Vídeo da vistoria *`;
  $('video-card-instruction').textContent = inspectionProfile.videoInstruction;
}

function msg(text, type = 'error') {
  const element = $('message');
  element.className = `message ${type}`;
  element.textContent = text;
}

function clearMessage() {
  const element = $('message');
  element.className = '';
  element.textContent = '';
}

function webAuthnApi(path) {
  const relative = `/api/public/inspections/${encodeURIComponent(token)}/digital-acceptance${path}`;
  return window.NH_API?.backend(relative) || relative;
}

function isIOSDevice() {
  const ua = navigator.userAgent || '';
  return /iPad|iPhone|iPod/i.test(ua) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
}

function isSafariOnIOS() {
  if (!isIOSDevice()) return false;
  const ua = navigator.userAgent || '';
  const knownNonSafari = /CriOS|FxiOS|EdgiOS|OPiOS|GSA|FBAN|FBAV|Instagram|WhatsApp|Line\//i;
  return /Safari/i.test(ua) && !knownNonSafari.test(ua);
}

function isLikelyEmbeddedIOSBrowser() {
  if (!isIOSDevice()) return false;
  const ua = navigator.userAgent || '';
  return !isSafariOnIOS() || /WhatsApp|FBAN|FBAV|Instagram|GSA/i.test(ua);
}

function showIOSWebAuthnHelp(reason = '') {
  const box = $('ios-webauthn-help');
  const text = $('ios-webauthn-help-text');
  if (!box) return;
  box.hidden = false;
  if (text && reason) text.textContent = reason;
}

function hideIOSWebAuthnHelp() {
  const box = $('ios-webauthn-help');
  if (box) box.hidden = true;
}

function base64UrlToBytes(value) {
  const normalized = String(value || '').replace(/-/g, '+').replace(/_/g, '/');
  const padded = normalized + '='.repeat((4 - (normalized.length % 4)) % 4);
  const binary = atob(padded);
  return Uint8Array.from(binary, (char) => char.charCodeAt(0));
}

function bytesToBase64Url(value) {
  const bytes = value instanceof ArrayBuffer
    ? new Uint8Array(value)
    : new Uint8Array(value?.buffer || value || []);
  let binary = '';
  for (let index = 0; index < bytes.byteLength; index += 1) binary += String.fromCharCode(bytes[index]);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

function digitalAcceptanceDeviceId() {
  const key = 'nh_acceptance_device_id_v1';
  try {
    let value = localStorage.getItem(key);
    if (!value) {
      const raw = window.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(16).slice(2)}-${Math.random().toString(16).slice(2)}`;
      value = `NHDEV-${String(raw).replace(/[^a-zA-Z0-9]/g, '').slice(0, 48).toUpperCase()}`;
      localStorage.setItem(key, value);
    }
    return value;
  } catch (_) {
    const raw = window.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(16).slice(2)}`;
    return `NHDEV-${String(raw).replace(/[^a-zA-Z0-9]/g, '').slice(0, 48).toUpperCase()}`;
  }
}

async function requiredAcceptanceGeolocation() {
  if (!navigator.geolocation) {
    throw new Error('Este aparelho não disponibiliza GPS/localização. Ative a localização para concluir o aceite.');
  }
  return new Promise((resolve, reject) => {
    navigator.geolocation.getCurrentPosition(
      (position) => resolve({
        latitude: Number(position.coords.latitude),
        longitude: Number(position.coords.longitude),
        accuracyMeters: Number(position.coords.accuracy)
      }),
      (error) => {
        const denied = Number(error?.code) === 1;
        reject(new Error(denied
          ? 'Permita o acesso à localização deste aparelho para registrar latitude e longitude junto ao aceite.'
          : 'Não foi possível obter o GPS deste aparelho. Ative a localização e tente novamente.'));
      },
      { enableHighAccuracy: true, timeout: 12000, maximumAge: 0 }
    );
  });
}

async function collectDigitalAcceptanceDeviceMetadata() {
  let platformAuthenticatorAvailable = false;
  if (window.PublicKeyCredential?.isUserVerifyingPlatformAuthenticatorAvailable) {
    try {
      platformAuthenticatorAvailable = await PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable();
    } catch (_) {
      platformAuthenticatorAvailable = false;
    }
  }
  const geo = await requiredAcceptanceGeolocation();
  return {
    deviceId: digitalAcceptanceDeviceId(),
    userAgent: navigator.userAgent || '',
    platform: navigator.userAgentData?.platform || navigator.platform || '',
    vendor: navigator.vendor || '',
    language: navigator.language || '',
    languages: Array.isArray(navigator.languages) ? navigator.languages.join(',') : '',
    timezone: Intl.DateTimeFormat().resolvedOptions().timeZone || '',
    screenWidth: Number(screen?.width || 0),
    screenHeight: Number(screen?.height || 0),
    colorDepth: Number(screen?.colorDepth || 0),
    pixelRatio: Number(window.devicePixelRatio || 1),
    touchPoints: Number(navigator.maxTouchPoints || 0),
    hardwareConcurrency: Number(navigator.hardwareConcurrency || 0),
    deviceMemory: navigator.deviceMemory == null ? null : Number(navigator.deviceMemory),
    cookieEnabled: Boolean(navigator.cookieEnabled),
    online: Boolean(navigator.onLine),
    webdriver: Boolean(navigator.webdriver),
    webauthnAvailable: Boolean(window.PublicKeyCredential && navigator.credentials),
    platformAuthenticatorAvailable,
    currentUrl: location.href,
    referrer: document.referrer || '',
    latitude: geo.latitude ?? null,
    longitude: geo.longitude ?? null,
    accuracyMeters: geo.accuracyMeters ?? null,
    capturedAt: new Date().toISOString()
  };
}

async function digitalAcceptancePost(path, body) {
  const response = await fetch(webAuthnApi(path), {
    method: 'POST',
    headers: body == null ? {} : { 'Content-Type': 'application/json' },
    body: body == null ? undefined : JSON.stringify(body)
  });
  const data = await response.json().catch(() => null);
  if (!response.ok) throw new Error(data?.message || 'Não foi possível concluir o aceite digital.');
  return data;
}

function renderDigitalResponsibilityTerm(data) {
  const present = Boolean(data?.preexistingDamage && data?.preexistingDamageDetails);
  const box = $('digital-responsibility-term');
  const evidence = $('digital-term-evidence');
  if (box) box.hidden = !present;
  if (evidence) evidence.hidden = !present;
  if (present && $('digital-responsibility-details')) {
    $('digital-responsibility-details').textContent = data.preexistingDamageDetails;
  }
}

function showDigitalAcceptance(data) {
  $('guideline-card').hidden = true;
  $('upload-card').hidden = true;
  $('complete').hidden = true;
  $('digital-acceptance').hidden = false;
  $('title').textContent = 'Vistoria aprovada pela Supervisão';
  $('subtitle').textContent = `${data.associateName} · ${vehiclePlateLabel(data.plate)}`;
  renderDigitalResponsibilityTerm(data);
  $('digital-acceptance-status').textContent = data.digitalAcceptance?.eligible === false
    ? 'O prazo de 7 dias para este aceite terminou. Solicite um novo link à NH.'
    : 'Aguardando confirmação do associado.';
  $('confirm-digital-acceptance').hidden = data.digitalAcceptance?.eligible === false;
  $('digital-acceptance-details').hidden = true;
}

function showDigitalAcceptanceComplete(data) {
  $('guideline-card').hidden = true;
  $('upload-card').hidden = true;
  $('complete').hidden = true;
  $('digital-acceptance').hidden = false;
  $('title').textContent = 'Aceite digital confirmado';
  $('subtitle').textContent = `${data.associateName} · ${vehiclePlateLabel(data.plate)}`;
  renderDigitalResponsibilityTerm(data);
  const button = $('confirm-digital-acceptance');
  button.hidden = true;
  const status = data.digitalAcceptance || {};
  const details = $('digital-acceptance-details');
  details.hidden = false;
  details.innerHTML = `<strong>✓ Confirmação criptográfica concluída</strong><br>
    Data: ${status.acceptedAt ? new Date(status.acceptedAt).toLocaleString('pt-BR') : 'confirmada'}<br>
    Verificação do usuário: ${status.userVerified ? 'confirmada pelo aparelho' : 'registrada'}<br>
    Prova: <code>${status.proofHash || 'registrada no dossiê'}</code>`;
  $('digital-acceptance-status').textContent = 'Aceite digital confirmado. O dossiê será atualizado com as evidências do WebAuthn.';
}

async function completeWebAuthnAssertion(assertionOptions, status) {
  status.textContent = 'Confirme no aparelho com Face ID, Touch ID, PIN ou código de bloqueio...';
  const assertion = await navigator.credentials.get({
    publicKey: {
      challenge: base64UrlToBytes(assertionOptions.challenge),
      rpId: assertionOptions.rpId,
      allowCredentials: [{
        type: 'public-key',
        id: base64UrlToBytes(assertionOptions.credentialId)
      }],
      userVerification: 'required',
      timeout: Number(assertionOptions.timeoutMs || 120000)
    }
  });
  if (!assertion) throw new Error('O aparelho não confirmou a assinatura WebAuthn.');

  const proof = {
    id: assertion.id,
    rawId: bytesToBase64Url(assertion.rawId),
    type: assertion.type,
    clientDataJSON: bytesToBase64Url(assertion.response.clientDataJSON),
    authenticatorData: bytesToBase64Url(assertion.response.authenticatorData),
    signature: bytesToBase64Url(assertion.response.signature),
    userHandle: assertion.response.userHandle ? bytesToBase64Url(assertion.response.userHandle) : null
  };
  try {
    return await digitalAcceptancePost('/assertion-finish', proof);
  } catch (error) {
    // A conexão pode cair DEPOIS que a assinatura foi persistida.
    // Nesse caso, recupere o status já confirmado e não solicite outra biometria.
    try {
      const url = window.NH_API?.backend(`/api/public/inspections/${encodeURIComponent(token)}/digital-acceptance`)
        || `/api/public/inspections/${encodeURIComponent(token)}/digital-acceptance`;
      const response = await fetch(url, { cache: 'no-store' });
      const result = response.ok ? await response.json() : null;
      if (result?.accepted) return result;
    } catch (_) {
      // A falha original continua sendo informada quando não há prova persistida.
    }
    throw error;
  }
}

async function existingWebAuthnAssertionOptions() {
  try {
    return await digitalAcceptancePost('/assertion-options', null);
  } catch (error) {
    if (/Primeiro confirme|credencial segura/i.test(String(error?.message || ''))) return null;
    throw error;
  }
}

async function performDigitalAcceptance() {
  const button = $('confirm-digital-acceptance');
  const status = $('digital-acceptance-status');
  if (!window.PublicKeyCredential || !navigator.credentials) {
    throw new Error('Este navegador não oferece WebAuthn. Abra o link diretamente no Safari, Chrome ou Edge do aparelho do associado.');
  }

  // iOS 26/iPhone 17 apresenta incompatibilidades conhecidas com WebAuthn em
  // navegadores de terceiros e navegadores internos (inclusive links abertos
  // dentro de apps). No Safari o sistema consegue acionar corretamente o
  // autenticador/passkey local e solicitar Face ID/Touch ID/código do aparelho.
  if (isLikelyEmbeddedIOSBrowser()) {
    const reason = 'Este link está aberto dentro de um app ou navegador que pode bloquear a verificação segura no iPhone. Toque em Compartilhar/Abrir no navegador e abra este mesmo endereço no Safari; depois toque novamente em Confirmar.';
    showIOSWebAuthnHelp(reason);
    status.textContent = 'No iPhone, abra o aceite no Safari para continuar com Face ID/Touch ID/código do aparelho.';
    throw new Error('Para concluir no iPhone, abra este link diretamente no Safari.');
  }

  button.disabled = true;
  hideIOSWebAuthnHelp();
  status.textContent = 'Coletando dados técnicos do aparelho e preparando a confirmação segura...';
  try {
    const device = await collectDigitalAcceptanceDeviceMetadata();

    if (isIOSDevice() && device.platformAuthenticatorAvailable === false) {
      showIOSWebAuthnHelp('O iPhone não disponibilizou o autenticador local. Abra no Safari e confirme se Senhas/Chaves-senha e o Preenchimento Automático estão habilitados nos Ajustes. A NH não recebe a senha nem o e-mail do gerenciador de senhas.');
      throw new Error('O autenticador seguro do iPhone não está disponível neste navegador/configuração.');
    }

    // Se a primeira tentativa já criou uma credencial, retomamos diretamente a
    // assinatura em vez de criar outra passkey. Isso é especialmente importante
    // no iPhone quando o usuário saiu do navegador entre as duas confirmações.
    let assertionOptions = await existingWebAuthnAssertionOptions();

    if (!assertionOptions) {
      const registration = await digitalAcceptancePost('/registration-options', device);
      status.textContent = 'Confirme a criação da chave segura usando Face ID, Touch ID, PIN ou código do aparelho...';

      const selection = {
        residentKey: isIOSDevice() ? 'preferred' : 'discouraged',
        requireResidentKey: false,
        userVerification: 'required'
      };
      // Em iOS deixamos o Safari escolher o provedor compatível do próprio
      // sistema. Forçar "platform" em navegadores recentes pode acionar telas de
      // gerenciador de chaves incompatíveis com WebViews de terceiros.
      if (!isIOSDevice()) selection.authenticatorAttachment = 'platform';

      const created = await navigator.credentials.create({
        publicKey: {
          challenge: base64UrlToBytes(registration.challenge),
          rp: { id: registration.rpId, name: registration.rpName },
          user: {
            id: base64UrlToBytes(registration.userId),
            name: registration.userName,
            displayName: registration.userDisplayName
          },
          pubKeyCredParams: [
            { type: 'public-key', alg: -7 },
            { type: 'public-key', alg: -257 }
          ],
          authenticatorSelection: selection,
          timeout: Number(registration.timeoutMs || 120000),
          attestation: 'none'
        }
      });
      if (!created) throw new Error('O aparelho não criou a credencial segura para este aceite.');

      assertionOptions = await digitalAcceptancePost('/registration-finish', {
        id: created.id,
        rawId: bytesToBase64Url(created.rawId),
        type: created.type,
        clientDataJSON: bytesToBase64Url(created.response.clientDataJSON),
        attestationObject: bytesToBase64Url(created.response.attestationObject),
        device
      });
    }

    const result = await completeWebAuthnAssertion(assertionOptions, status);
    request.digitalAcceptance = result;
    showDigitalAcceptanceComplete(request);
  } catch (error) {
    const name = String(error?.name || '');
    const detail = String(error?.message || '');
    if (isIOSDevice() && /NotAllowedError|SecurityError|UnknownError|InvalidStateError/.test(name)) {
      showIOSWebAuthnHelp('O iPhone não conseguiu concluir a chave-senha neste navegador. Abra o link diretamente no Safari e verifique em Ajustes se Senhas/Chaves-senha e Preenchimento Automático estão habilitados. O código do iPhone é validado somente pelo próprio aparelho e nunca é enviado para a NH.');
      throw new Error('O iPhone não concluiu a verificação segura. Abra o link no Safari e tente novamente.');
    }
    if (name === 'NotAllowedError') {
      throw new Error('A confirmação foi cancelada ou excedeu o tempo. Toque no botão e tente novamente.');
    }
    if (/passkey|chave-senha|autenticador/i.test(detail) && isIOSDevice()) {
      showIOSWebAuthnHelp();
    }
    throw error;
  } finally {
    button.disabled = false;
  }
}

async function load() {
  $('connection-actions').hidden = true;
  clearMessage();

  if (!token) {
    msg('Link de vistoria não informado. Solicite um novo link ao consultor.');
    return;
  }

  try {
    const response = await fetch(
      window.NH_API?.backend(`/api/public/inspections/${encodeURIComponent(token)}`) || `/api/public/inspections/${encodeURIComponent(token)}`,
      { cache: 'no-store' }
    );
    const body = await response.json().catch(() => null);

    if (!response.ok) {
      throw new Error(body?.message || 'Link de vistoria inválido ou indisponível.');
    }

    request = body;
    configureInspectionProfile(body.vehicleType);

    if (body.status === 'APPROVED') {
      if (body.digitalAcceptance?.accepted) showDigitalAcceptanceComplete(body);
      else showDigitalAcceptance(body);
      return;
    }

    const pendingSlots = pendingRequiredSlots();
    const selective = isSelectiveResubmission();
    $('vehicle-guide-count').textContent = selective
      ? `${pendingSlots.length} pendência${pendingSlots.length === 1 ? '' : 's'} para refazer · os demais arquivos foram mantidos`
      : (body.requestType === 'NEW_INSPECTION'
        ? `${labels.length} fotos obrigatórias + 1 vídeo`
        : '1 vídeo obrigatório para atualização de boleto');

    if (databaseCompletionConfirmed(body)) {
      showComplete(body);
      return;
    }
    if (['COMPLETED', 'APPROVED', 'REJECTED'].includes(body.status)) {
      msg('O servidor ainda não confirmou todos os arquivos no banco de dados. Aguarde a conclusão do envio ou solicite a reabertura da vistoria.');
      return;
    }
    if (body.status === 'CANCELLED') {
      msg('Esta solicitação de vistoria foi cancelada. Fale com o seu consultor para receber um novo link.');
      return;
    }
    if (body.status === 'EXPIRED') {
      msg('Este link de vistoria expirou. Fale com o seu consultor para receber um novo link.');
      return;
    }

    $('title').textContent = selective
      ? 'Refazer arquivos pendentes'
      : (body.requestType === 'NEW_INSPECTION'
        ? 'Nova vistoria do veículo'
        : 'Atualização de boleto');
    const contractedPlanText = body.requestType === 'BILL_UPDATE' && body.contractedPlan
      ? ` · Plano atual: ${body.contractedPlan}`
      : '';
    $('subtitle').textContent = `Associado: ${body.associateName} · ${vehiclePlateLabel(body.plate)}${contractedPlanText}`;
    $('guideline-card').hidden = false;
    await restoreDraftFromCache();
    if (selective) {
      msg('Os arquivos já aceitos continuam salvos. Este link pedirá somente o que foi rejeitado, excluído ou ainda está faltando.', 'success');
      $('start').textContent = 'Refazer somente as pendências →';
    }
  } catch (error) {
    const errorMessage = friendlyNetworkMessage(
      error,
      'Não foi possível carregar os dados da vistoria agora. Verifique a internet e toque em “Tentar carregar novamente”.'
    );
    if (/vistoria\/cotação vencida|precisa ser refeita/i.test(errorMessage)) {
      window.alert('Vistoria/cotação vencida, precisa ser refeita.');
    }
    msg(errorMessage);
    $('connection-actions').hidden = false;
  }
}

$('start').addEventListener('click', async () => {
  clearMessage();
  await requestPersistentStorage();
  $('guideline-card').hidden = true;
  $('upload-card').hidden = false;

  const fullInspection = request.requestType === 'NEW_INSPECTION';
  const selective = isSelectiveResubmission();
  const orders = requiredAssetOrders();
  const pending = pendingRequiredSlots();

  $('upload-heading').textContent = selective
    ? 'Refazer arquivos pendentes'
    : (fullInspection ? `Vistoria de ${inspectionProfile.title.toLowerCase()}` : 'Vídeo para atualização de boleto');
  $('upload-note').textContent = selective
    ? `Somente ${pending.length} ${pending.length === 1 ? 'item está pendente' : 'itens estão pendentes'}. Tudo que já foi aceito permanece salvo e não precisa ser refeito.`
    : (fullInspection
      ? `Registre as ${labels.length} fotos na ordem indicada. Antes de cada abertura da câmera, a imagem ilustrativa correspondente será exibida.`
      : 'Antes da gravação, você verá as diretrizes específicas para este tipo de veículo.');

  if ($('residence-address')) {
    $('residence-address').value = request.residenceAddress || $('residence-address').value || '';
    $('residence-address').required = false; // Os campos estruturados fazem a validação.
    window.NHResidenceAddress?.required(fullInspection && !request.residenceAddress);
  }
  if (fullInspection && !hasRestoredDraft) {
    applyPreexistingDamageState(Boolean(request.preexistingDamage), request.preexistingDamageDetails || '');
  }

  const pendingRegistration = fullInspection && (
    !request.residenceAddress
    || !serverHasAsset('SIGNATURE', orders.signature)
    || !serverHasAsset('VEHICLE_DOCUMENT', orders.vehicleDocument)
    || !serverHasAsset('IDENTITY_DOCUMENT', orders.identityFront)
    || !serverHasAsset('IDENTITY_DOCUMENT', orders.identityBack)
  );
  $('registration-fields').hidden = !pendingRegistration;
  $('residence-address-field').hidden = !fullInspection || Boolean(request.residenceAddress);
  $('preexisting-damage-box').hidden = !fullInspection;
  $('vehicle-document-card').hidden = !fullInspection || serverHasAsset('VEHICLE_DOCUMENT', orders.vehicleDocument);
  $('identity-document-front-card').hidden = !fullInspection || serverHasAsset('IDENTITY_DOCUMENT', orders.identityFront);
  $('identity-document-back-card').hidden = !fullInspection || serverHasAsset('IDENTITY_DOCUMENT', orders.identityBack);
  $('signature-block').hidden = !fullInspection || serverHasAsset('SIGNATURE', orders.signature);

  $('video-capture-card').hidden = serverHasAsset('VIDEO', orders.video);
  $('video-title').textContent = fullInspection
    ? `${labels.length + 1}. Vídeo da vistoria *`
    : 'Vídeo para atualização de boleto *';

  if (fullInspection) {
    renderPhotoCaptureCards();
    if (!serverHasAsset('SIGNATURE', orders.signature)) initializeSignaturePad();
  } else {
    $('photos').innerHTML = '';
  }

  $('submit-inspection').textContent = selective ? 'Enviar arquivos pendentes' : 'Enviar vistoria com segurança';

  if (hasRestoredDraft) {
    await applyRestoredDraftToUi();
  } else {
    updateCaptureSummary();
  }

  window.scrollTo({ top: 0, behavior: 'smooth' });
});

function renderPhotoCaptureCards() {
  $('photos').innerHTML = labels.map((label, index) => {
    if (serverHasAsset('PHOTO', index + 1)) return '';
    return `
    <article class="upload-item camera-upload-item">
      <strong>${index + 1}. ${escapeHtml(label)}</strong>
      <small>Ao tocar em “Abrir câmera”, você verá primeiro a imagem ilustrativa desta etapa.</small>
      <img id="photo-preview-${index}" class="capture-preview" alt="Prévia de ${escapeHtml(label)}" hidden>
      <p id="photo-status-${index}" class="capture-status">Foto ainda não registrada.</p>
      <button class="outline camera-action" type="button" data-photo-index="${index}">
        Abrir câmera
      </button>
    </article>
  `;
  }).join('');

  document.querySelectorAll('[data-photo-index]').forEach((button) => {
    button.addEventListener('click', () => showPhotoGuide(Number(button.dataset.photoIndex)));
  });
}

function showPhotoGuide(index) {
  const photo = inspectionProfile.photos[index];
  pendingGuideAction = { type: 'photo', index };
  $('capture-guide-eyebrow').textContent = `FOTO ${index + 1} DE ${labels.length}`;
  $('capture-guide-title').textContent = photo.label;
  $('capture-guide-warning').textContent = 'Esta foto deve seguir as diretrizes da imagem ilustrativa.';
  $('capture-guide-image').src = photo.guide;
  $('capture-guide-image').alt = `Imagem ilustrativa: ${photo.label}`;
  $('capture-guide-image').hidden = false;
  const normalizedLabel = photo.label.toLowerCase();
  const discountPercent = Number(request?.discountPercent || 0);
  const requiresRearPerforation = request?.rearWindowBranding && request.rearWindowBranding !== 'NOT_APPLICABLE';
  const rearWindowCondition = normalizedLabel.includes('traseira')
    && inspectionProfile === VEHICLE_PROFILES.FOUR_WHEELS_OR_MORE
    && discountPercent > 0
    && requiresRearPerforation
      ? ` Mostre claramente o perfurado do vigia traseiro. Essa condição será conferida pela equipe de análise para validar o desconto de ${discountPercent}%.`
      : '';

  $('capture-guide-instruction').textContent = normalizedLabel.includes('selfie')
    ? 'Enquadre o associado e o veículo como no exemplo. Quando houver placa, ela precisa ficar legível. Para veículo 0 km sem placa, mostre claramente a dianteira.'
    : 'Observe o ângulo, a distância e a área do veículo mostrada no exemplo. Tire a foto com boa iluminação e sem cortar a parte solicitada.' + rearWindowCondition;
  $('capture-guide-continue').textContent = USE_NATIVE_MOBILE_PHOTO_CAPTURE
    ? 'Entendi, abrir câmera em tela cheia'
    : 'Entendi, abrir câmera';
  showGuidePage();
}

function showVideoGuide() {
  pendingGuideAction = { type: 'video' };
  $('capture-guide-eyebrow').textContent = request?.requestType === 'NEW_INSPECTION'
    ? `VÍDEO ${labels.length + 1}`
    : 'VÍDEO OBRIGATÓRIO';
  $('capture-guide-title').textContent = 'Vídeo de conclusão da vistoria';
  $('capture-guide-warning').textContent = 'Este vídeo deve seguir todas as diretrizes abaixo.';
  $('capture-guide-image').src = inspectionProfile.videoGuide;
  $('capture-guide-image').alt = `Guia de vídeo para ${inspectionProfile.title.toLowerCase()}`;
  $('capture-guide-image').hidden = false;
  $('capture-guide-instruction').textContent = USE_NATIVE_IOS_VIDEO_CAPTURE
    ? `${inspectionProfile.videoInstruction} Ao aproximar a câmera do chassi, toque diretamente sobre a numeração para o iPhone/iPad ajustar o foco. O sistema abrirá o gravador nativo para evitar congelamentos. A página não consegue interromper a câmera nativa exatamente em 1:30; se você gravar além desse tempo, o servidor preservará automaticamente somente os primeiros 1 minuto e 30 segundos.`
    : `${inspectionProfile.videoInstruction} Ao aproximar a câmera do chassi, toque diretamente sobre a numeração para ajustar e fixar o foco. Se o aparelho oferecer foco manual, use o controle perto/longe que aparecerá abaixo da imagem. Neste navegador, a gravação será encerrada automaticamente ao atingir 1 minuto e 30 segundos.`;
  $('capture-guide-continue').textContent = USE_NATIVE_IOS_VIDEO_CAPTURE
    ? 'Entendi, abrir gravador do iPhone'
    : 'Entendi, abrir câmera para gravar';
  showGuidePage();
}

function showGuidePage() {
  $('upload-card').hidden = true;
  $('capture-guide-card').hidden = false;
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

function hideGuidePage({ restoreUpload = false } = {}) {
  $('capture-guide-card').hidden = true;
  if (restoreUpload) {
    $('upload-card').hidden = false;
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }
  pendingGuideAction = null;
}

$('capture-guide-back').addEventListener('click', () => hideGuidePage({ restoreUpload: true }));
$('capture-guide-continue').addEventListener('click', async () => {
  const action = pendingGuideAction;

  // O clique em “Entendi” é o gesto direto do usuário que libera a câmera no mobile.
  $('capture-guide-card').hidden = true;
  $('upload-card').hidden = false;
  pendingGuideAction = null;

  if (!action) return;
  if (action.type === 'photo') {
    if (USE_NATIVE_MOBILE_PHOTO_CAPTURE) {
      // O clique continua sendo um gesto direto do usuário, permitindo que iOS/Android
      // abram a câmera nativa realmente em tela cheia.
      openNativeMobilePhotoCapture(action.index);
    } else {
      await openPhotoCamera(action.index);
    }
  } else if (USE_NATIVE_IOS_VIDEO_CAPTURE) {
    // Este click ainda está dentro do gesto direto do usuário, requisito do iOS
    // para abrir o gravador nativo. Assim evitamos MediaRecorder/canvas no WebKit.
    openNativeIosVideoCapture();
  } else {
    await openVideoCamera();
  }
});

function openNativeMobilePhotoCapture(index) {
  clearMessage();
  const input = $('native-photo-input');
  const photo = inspectionProfile.photos[index];
  if (!input || !photo) {
    handleCameraError(new Error('A câmera nativa não está disponível nesta página.'));
    return;
  }

  pendingNativePhotoIndex = index;
  input.value = '';
  input.setAttribute('accept', 'image/*');
  input.setAttribute('capture', photo.facingMode === 'user' ? 'user' : 'environment');
  input.click();
}

async function reencodeNativePhotoAsJpeg(file, index) {
  const { image, objectUrl } = await loadImageFromBlob(file);
  try {
    const sourceWidth = image.naturalWidth || image.width;
    const sourceHeight = image.naturalHeight || image.height;
    if (!sourceWidth || !sourceHeight) {
      throw new Error('A foto capturada não possui dimensões válidas.');
    }

    // Mantém bastante detalhe para chassi/placa, mas evita fotos de 30–50 MB
    // produzidas por alguns aparelhos em resolução total.
    const maxDimension = 3072;
    const scale = Math.min(1, maxDimension / Math.max(sourceWidth, sourceHeight));
    const width = Math.max(1, Math.round(sourceWidth * scale));
    const height = Math.max(1, Math.round(sourceHeight * scale));
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext('2d');
    if (!context) throw new Error('Não foi possível preparar a foto capturada.');
    context.drawImage(image, 0, 0, width, height);

    let blob = null;
    for (const quality of [0.95, 0.92, 0.88, 0.84]) {
      blob = await new Promise((resolve, reject) => {
        canvas.toBlob(
          value => value ? resolve(value) : reject(new Error('Não foi possível converter a foto para JPG.')),
          'image/jpeg',
          quality
        );
      });
      if (blob.size <= PHOTO_MAX_BYTES) break;
    }

    if (!blob || blob.size > PHOTO_MAX_BYTES) {
      throw new Error('A foto ficou maior que 15 MB mesmo após a otimização. Tire a foto novamente.');
    }

    const name = `${String(index + 1).padStart(2, '0')}-${slugify(labels[index])}.jpg`;
    return new File([blob], name, {
      type: 'image/jpeg',
      lastModified: Date.now()
    });
  } finally {
    URL.revokeObjectURL(objectUrl);
  }
}

async function normalizeNativePhotoFile(file, index) {
  if (!file || !Number.isFinite(Number(file.size)) || Number(file.size) <= 0) {
    throw new Error('A câmera não retornou uma foto válida.');
  }

  const originalType = String(file.type || '').toLowerCase().split(';', 1)[0].trim();
  const supportedTypes = new Set(['image/jpeg', 'image/png', 'image/webp']);
  const currentLabel = labels[index] || `foto-${index + 1}`;

  // Se a câmera já devolveu um formato aceito pelo backend e dentro de 15 MB,
  // preservamos o arquivo original para não perder nenhum detalhe por recompressão.
  if (supportedTypes.has(originalType) && file.size <= PHOTO_MAX_BYTES) {
    const extension = originalType === 'image/png' ? 'png' : (originalType === 'image/webp' ? 'webp' : 'jpg');
    return new File([file], `${String(index + 1).padStart(2, '0')}-${slugify(currentLabel)}.${extension}`, {
      type: originalType,
      lastModified: file.lastModified || Date.now()
    });
  }

  // HEIC/HEIF ou uma imagem muito grande é convertida no próprio aparelho para
  // JPEG de alta qualidade, mantendo resolução suficiente para detalhes de vistoria.
  return reencodeNativePhotoAsJpeg(file, index);
}

async function acceptNativeMobilePhoto(file, index) {
  const normalizedFile = await normalizeNativePhotoFile(file, index);
  photoFiles[index] = normalizedFile;

  if (photoPreviewUrls[index]) URL.revokeObjectURL(photoPreviewUrls[index]);
  photoPreviewUrls[index] = URL.createObjectURL(normalizedFile);

  const image = $(`photo-preview-${index}`);
  if (image) {
    image.src = photoPreviewUrls[index];
    image.hidden = false;
  }

  const status = $(`photo-status-${index}`);
  if (status) {
    status.textContent = `Foto registrada em tela cheia (${formatBytes(normalizedFile.size)}).`;
  }

  const button = document.querySelector(`[data-photo-index="${index}"]`);
  if (button) {
    button.textContent = 'Refazer foto';
    button.classList.add('captured');
  }

  updateCaptureSummary();
  scheduleDraftSave(0, `Foto ${index + 1} salva neste aparelho.`);
}

$('native-photo-input')?.addEventListener('change', async (event) => {
  const index = pendingNativePhotoIndex;
  const file = event.target?.files?.[0] || null;
  pendingNativePhotoIndex = null;
  if (!file || index === null || index < 0 || index >= labels.length) {
    event.target.value = '';
    return;
  }

  try {
    await acceptNativeMobilePhoto(file, index);
  } catch (error) {
    msg(error?.message || 'Não foi possível usar a foto capturada.');
    window.scrollTo({ top: 0, behavior: 'smooth' });
  } finally {
    event.target.value = '';
  }
});

async function openPhotoCamera(index) {
  currentPhotoIndex = index;
  captureMode = 'photo';
  discardRecording = false;
  clearMessage();

  try {
    const photo = inspectionProfile.photos[index];
    const isSelfie = photo.label.toLowerCase().includes('selfie');
    selfieMirrorCorrection = isSelfie;
    await openCamera({ audio: false, facingMode: photo.facingMode || 'environment' });
    applySelfieOrientation();
    $('camera-title').textContent = `${index + 1}. ${labels[index]}`;
    $('camera-instruction').textContent = isSelfie
      ? 'Reproduza o enquadramento mostrado no guia. Confira se a placa aparece com as letras na orientação correta. Use “Inverter imagem” se necessário.'
      : 'Reproduza o ângulo mostrado no guia e toque em “Capturar foto”.';
    $('toggle-selfie-mirror').hidden = !isSelfie;
    $('capture-photo').hidden = false;
    $('start-recording').hidden = true;
    $('stop-recording').hidden = true;
    $('resume-recording').hidden = true;
    configureTorchControl();
    configureFocusControl();
    $('recording-paused-banner').hidden = true;
    $('recording-indicator').hidden = true;
  } catch (error) {
    handleCameraError(error);
  }
}

function openNativeIosVideoCapture() {
  clearMessage();
  const input = $('ios-native-video-input');
  if (!input) {
    handleCameraError(new Error('O gravador nativo do iPhone não está disponível nesta página.'));
    return;
  }
  input.value = '';
  input.click();
}

async function acceptNativeIosVideo(file) {
  if (!file) return;
  const originalType = String(file.type || '').split(';')[0].toLowerCase();
  const extension = String(file.name || '').split('.').pop().toLowerCase();
  const inferredType = ({ mov: 'video/quicktime', mp4: 'video/mp4', m4v: 'video/x-m4v', webm: 'video/webm', '3gp': 'video/3gpp' })[extension] || '';
  const normalizedType = originalType || inferredType;
  if (!normalizedType || !normalizedType.startsWith('video/')) {
    throw new Error('O iPhone não informou um formato de vídeo compatível. Grave novamente usando a câmera do aparelho.');
  }
  if (!allowedVideoTypes.has(normalizedType)) {
    throw new Error('O vídeo deve estar em MP4, MOV, M4V, WebM ou 3GP.');
  }
  if (normalizedType !== originalType) {
    file = new File([file], file.name || `video-vistoria.${extension || 'mov'}`, {
      type: normalizedType,
      lastModified: file.lastModified || Date.now()
    });
  }

  let duration = null;
  try {
    const measured = await readVideoDurationSeconds(file);
    duration = Number.isFinite(measured) && measured > 0 ? measured : null;
  } catch (error) {
    // A câmera nativa do iOS pode devolver MOV/HEVC cuja metadata não fica
    // imediatamente legível pelo elemento <video>. O backend usa ffprobe como
    // fonte definitiva, então não descartamos uma gravação válida só por isso.
    console.warn('Não foi possível ler a duração do vídeo nativo no navegador; o servidor fará a validação.', error);
  }

  videoDurationSeconds = duration;
  videoFile = file;
  if (videoPreviewUrl) URL.revokeObjectURL(videoPreviewUrl);
  videoPreviewUrl = URL.createObjectURL(file);
  const preview = $('video-preview');
  preview.src = videoPreviewUrl;
  preview.hidden = false;
  const durationText = Number.isFinite(duration)
    ? `${Math.floor(duration)}s · ${formatBytes(file.size)}`
    : formatBytes(file.size);
  const trimNotice = Number.isFinite(duration) && duration > VIDEO_MAX_DURATION_SECONDS + 0.5
    ? ' O gravador nativo passou de 1:30; no envio serão preservados automaticamente somente os primeiros 1min30s.'
    : '';
  $('video-status').textContent = `Vídeo gravado pela câmera nativa do iPhone (${durationText}).${trimNotice}`;
  $('record-video').textContent = 'Gravar novamente';
  $('record-video').classList.add('captured');
  updateCaptureSummary();

  if (file.size > DRAFT_MAX_VIDEO_BYTES) {
    updateDraftPanel('Vídeo pronto. Como ele é grande, mantenha esta página aberta até concluir o envio; as fotos e documentos continuam salvos no rascunho.', 'warning');
  } else {
    scheduleDraftSave(0, 'Vídeo salvo neste aparelho para continuar o envio.');
  }
}

$('ios-native-video-input')?.addEventListener('change', async (event) => {
  const file = event.target?.files?.[0] || null;
  if (!file) return;
  try {
    await acceptNativeIosVideo(file);
  } catch (error) {
    videoFile = null;
    videoDurationSeconds = null;
    msg(error?.message || 'Não foi possível usar o vídeo gravado.');
    window.scrollTo({ top: 0, behavior: 'smooth' });
  } finally {
    event.target.value = '';
  }
});

async function openVideoCamera() {
  currentPhotoIndex = null;
  captureMode = 'video';
  discardRecording = false;
  clearMessage();

  try {
    selfieMirrorCorrection = false;
    await openCamera({ audio: true, facingMode: 'environment' });
    applySelfieOrientation();
    $('toggle-selfie-mirror').hidden = true;
    $('camera-title').textContent = request?.requestType === 'NEW_INSPECTION'
      ? `${labels.length + 1}. Vídeo da vistoria`
      : 'Vídeo para atualização de boleto';
    $('camera-instruction').textContent = `${inspectionProfile.videoInstruction} Para o chassi, aproxime a câmera e toque diretamente sobre a numeração para ajustar o foco.`;
    $('capture-photo').hidden = true;
    $('start-recording').hidden = false;
    $('stop-recording').hidden = true;
    $('resume-recording').hidden = true;
    $('recording-paused-banner').hidden = true;
    $('recording-indicator').hidden = true;
    configureTorchControl();
    configureFocusControl();
  } catch (error) {
    handleCameraError(error);
  }
}

function buildCameraVideoConstraints(audio, facingMode = 'environment') {
  return audio
    ? {
        // A câmera continua em 960x540 para manter chassi/placa legíveis. No iOS a
        // gravação final usa um canvas estável e o watchdog pode reabrir somente a
        // track de vídeo caso o WebKit pare de entregar quadros.
        facingMode: { ideal: facingMode },
        width: IS_IOS_WEBKIT ? { ideal: 960, max: 960 } : { ideal: 960, max: 1280 },
        height: IS_IOS_WEBKIT ? { ideal: 540, max: 540 } : { ideal: 540, max: 720 },
        aspectRatio: { ideal: 16 / 9 },
        frameRate: IS_IOS_WEBKIT ? { ideal: 12, max: 15 } : { ideal: 15, max: 18 }
      }
    : {
        facingMode: { ideal: facingMode },
        width: { ideal: 1920 },
        height: { ideal: 1080 }
      };
}

async function openCamera({ audio, facingMode = 'environment' }) {
  activeFacingMode = facingMode;
  if (!window.isSecureContext || !navigator.mediaDevices?.getUserMedia) {
    throw new Error('A câmera exige um navegador atualizado e uma conexão HTTPS. Em testes locais, use localhost.');
  }

  stopCameraStream();

  const constraints = {
    video: buildCameraVideoConstraints(audio, facingMode),
    audio: audio
      ? { echoCancellation: true, noiseSuppression: true, channelCount: { ideal: 1 } }
      : false
  };

  activeStream = await navigator.mediaDevices.getUserMedia(constraints);

  if (audio) {
    await optimizeVideoTrackForInspection(activeStream?.getVideoTracks?.()[0]);
  }

  const cameraPreview = $('camera-preview');
  cameraPreview.srcObject = activeStream;
  cameraPreview.muted = true;
  await cameraPreview.play();

  if (captureMode === 'video') {
    attachVideoTrackInterruptionListeners();
  }

  const cameraModal = $('camera-modal');
  cameraModal.classList.toggle('photo-fullscreen', captureMode === 'photo');
  cameraModal.hidden = false;
  document.body.classList.add('camera-open');
}

async function optimizeVideoTrackForInspection(track) {
  if (!track || typeof track.getCapabilities !== 'function' || typeof track.applyConstraints !== 'function') {
    return;
  }

  try {
    const capabilities = track.getCapabilities() || {};
    const advanced = {};

    // Em aparelhos que expõem esses recursos, mantemos foco e exposição contínuos.
    // Isso melhora principalmente a leitura de chassi, placa e odômetro sem alterar
    // bitrate nem tamanho do arquivo.
    if (Array.isArray(capabilities.focusMode) && capabilities.focusMode.includes('continuous')) {
      advanced.focusMode = 'continuous';
    }
    if (Array.isArray(capabilities.exposureMode) && capabilities.exposureMode.includes('continuous')) {
      advanced.exposureMode = 'continuous';
    }
    if (Array.isArray(capabilities.whiteBalanceMode) && capabilities.whiteBalanceMode.includes('continuous')) {
      advanced.whiteBalanceMode = 'continuous';
    }

    if (Object.keys(advanced).length) {
      await track.applyConstraints({ advanced: [advanced] });
    }
  } catch (_error) {
    // Alguns WebKit anunciam capabilities que não aceitam applyConstraints.
    // Mantemos a gravação com os parâmetros originais nesses casos.
  }
}

function handleCameraError(error) {
  closeCameraModal();

  let message = error?.message || 'Não foi possível abrir a câmera.';

  if (error?.name === 'NotAllowedError' || error?.name === 'PermissionDeniedError') {
    message = 'O acesso à câmera foi bloqueado. Permita o uso da câmera nas configurações do navegador e tente novamente.';
  } else if (error?.name === 'NotFoundError' || error?.name === 'DevicesNotFoundError') {
    message = 'Nenhuma câmera compatível foi encontrada neste aparelho.';
  } else if (error?.name === 'NotReadableError' || error?.name === 'TrackStartError') {
    message = 'A câmera está sendo usada por outro aplicativo. Feche o outro aplicativo e tente novamente.';
  }

  msg(message);
  window.scrollTo({ top: 0, behavior: 'smooth' });
}

function applySelfieOrientation() {
  const frame = $('camera-preview')?.closest('.camera-live-frame');
  const shouldCorrect = activeFacingMode === 'user' && selfieMirrorCorrection;
  frame?.classList.toggle('selfie-orientation-corrected', shouldCorrect);
  const toggle = $('toggle-selfie-mirror');
  if (toggle) {
    toggle.textContent = shouldCorrect ? 'Restaurar orientação' : 'Inverter imagem';
    toggle.setAttribute('aria-pressed', String(shouldCorrect));
  }
}

$('toggle-selfie-mirror').addEventListener('click', () => {
  selfieMirrorCorrection = !selfieMirrorCorrection;
  applySelfieOrientation();
});

$('capture-photo').addEventListener('click', capturePhoto);

async function capturePhoto() {
  if (currentPhotoIndex === null || !activeStream) {
    return;
  }

  await prepareTorchForCapture();

  const preview = $('camera-preview');
  const canvas = $('photo-canvas');
  const width = preview.videoWidth || 1280;
  const height = preview.videoHeight || 720;

  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext('2d');
  const correctSelfie = activeFacingMode === 'user' && selfieMirrorCorrection;
  if (correctSelfie) {
    context.save();
    context.translate(width, 0);
    context.scale(-1, 1);
    context.drawImage(preview, 0, 0, width, height);
    context.restore();
  } else {
    context.drawImage(preview, 0, 0, width, height);
  }

  const blob = await new Promise((resolve, reject) => {
    canvas.toBlob(
      (value) => value ? resolve(value) : reject(new Error('Não foi possível processar a foto.')),
      'image/jpeg',
      0.9
    );
  });

  const index = currentPhotoIndex;
  const fileName = `${String(index + 1).padStart(2, '0')}-${slugify(labels[index])}.jpg`;
  photoFiles[index] = new File([blob], fileName, {
    type: 'image/jpeg',
    lastModified: Date.now()
  });

  if (photoPreviewUrls[index]) {
    URL.revokeObjectURL(photoPreviewUrls[index]);
  }
  photoPreviewUrls[index] = URL.createObjectURL(blob);

  const image = $(`photo-preview-${index}`);
  image.src = photoPreviewUrls[index];
  image.hidden = false;
  $(`photo-status-${index}`).textContent = 'Foto registrada pela câmera.';

  const button = document.querySelector(`[data-photo-index="${index}"]`);
  button.textContent = 'Refazer foto';
  button.classList.add('captured');

  closeCameraModal();
  updateCaptureSummary();
  scheduleDraftSave(0, `Foto ${index + 1} salva neste aparelho.`);
}

$('record-video').addEventListener('click', showVideoGuide);
$('start-recording').addEventListener('click', startVideoRecording);
$('stop-recording').addEventListener('click', stopVideoRecording);
$('resume-recording').addEventListener('click', resumeVideoRecording);
$('toggle-flash').addEventListener('click', cycleCameraFlashMode);
$('camera-preview').addEventListener('pointerup', (event) => {
  if (event.pointerType === 'mouse' && event.button !== 0) return;
  void focusCameraAtPoint(event);
});
$('manual-focus-range')?.addEventListener('input', (event) => {
  if (manualFocusTimer) window.clearTimeout(manualFocusTimer);
  const value = event.target?.value;
  manualFocusTimer = window.setTimeout(() => {
    manualFocusTimer = null;
    void applyManualFocusDistance(value);
  }, 90);
});
$('focus-near')?.addEventListener('click', () => void focusCameraNear());
$('reset-focus')?.addEventListener('click', () => void resetCameraAutoFocus());
$('cancel-camera').addEventListener('click', cancelCameraCapture);
$('close-camera').addEventListener('click', cancelCameraCapture);

async function waitForVideoTrackReadyForRecording(track) {
  if (!track || track.readyState !== 'live') {
    throw new Error('A câmera não está disponível para iniciar a gravação.');
  }

  // applyConstraints() do flash e a própria negociação da câmera podem deixar a
  // track temporariamente muted no iPhone. Iniciar o MediaRecorder nesse exato
  // momento é uma das causas de gravação que começa e encerra logo em seguida.
  if (!IS_IOS_WEBKIT) return;

  await wait(350);
  if (!track.muted) return;

  await Promise.race([
    new Promise((resolve) => track.addEventListener('unmute', resolve, { once: true })),
    wait(2200)
  ]);

  if (track.readyState !== 'live') {
    throw new Error('A câmera foi encerrada antes do início da gravação. Abra a câmera novamente e tente de novo.');
  }

  // Se o WebKit ainda reportar muted, não derrubamos a gravação automaticamente.
  // O evento ended e o próprio MediaRecorder continuam sendo as fontes confiáveis
  // para detectar uma interrupção real.
}


function stopStableRecordingPipeline() {
  if (recordingCanvasTimer) {
    window.clearInterval(recordingCanvasTimer);
    recordingCanvasTimer = null;
  }

  recordingOutputOwnedTracks.forEach((track) => {
    try { track.stop(); } catch (_error) { /* track já pode estar encerrada */ }
  });

  recordingOutputOwnedTracks = [];
  recordingOutputStream = null;
  recordingCanvasTrack = null;
  recordingCanvasContext = null;
  recordingCanvas = null;
  recordingCanvasFrameCount = 0;
  recordingCanvasLastDrawAt = 0;
}

function drawPreviewIntoRecordingCanvas(preview) {
  if (!recordingCanvas || !recordingCanvasContext || !preview) return false;
  if (preview.readyState < 2 || !preview.videoWidth || !preview.videoHeight) return false;

  const targetWidth = recordingCanvas.width;
  const targetHeight = recordingCanvas.height;
  const sourceWidth = preview.videoWidth;
  const sourceHeight = preview.videoHeight;

  const sourceAspect = sourceWidth / sourceHeight;
  const targetAspect = targetWidth / targetHeight;

  let sx = 0;
  let sy = 0;
  let sw = sourceWidth;
  let sh = sourceHeight;

  // Crop central "cover" sem deformar a imagem. Assim chassi/placa não ficam
  // esticados quando a câmera entrega uma proporção levemente diferente de 16:9.
  if (sourceAspect > targetAspect) {
    sw = Math.round(sourceHeight * targetAspect);
    sx = Math.max(0, Math.round((sourceWidth - sw) / 2));
  } else if (sourceAspect < targetAspect) {
    sh = Math.round(sourceWidth / targetAspect);
    sy = Math.max(0, Math.round((sourceHeight - sh) / 2));
  }

  recordingCanvasContext.drawImage(
    preview,
    sx, sy, sw, sh,
    0, 0, targetWidth, targetHeight
  );

  recordingCanvasFrameCount += 1;
  recordingCanvasLastDrawAt = performance.now();

  // Alguns CanvasCaptureMediaStreamTrack aceitam requestFrame(). Quando disponível,
  // pedimos explicitamente o quadro recém-desenhado para evitar o encoder "dormir".
  try {
    recordingCanvasTrack?.requestFrame?.();
  } catch (_error) {
    // captureStream(frameRate) já entrega os quadros nos WebKit sem requestFrame.
  }

  return true;
}

function drawRecordingRecoverySlate() {
  if (!recordingCanvas || !recordingCanvasContext) return false;
  const ctx = recordingCanvasContext;
  const width = recordingCanvas.width;
  const height = recordingCanvas.height;
  const phase = Math.floor(Date.now() / 350) % 4;
  const dots = '.'.repeat(phase);

  ctx.save();
  ctx.fillStyle = '#070c40';
  ctx.fillRect(0, 0, width, height);
  ctx.fillStyle = '#f4f700';
  ctx.font = '700 34px system-ui, -apple-system, sans-serif';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText(`Recuperando câmera${dots}`, width / 2, (height / 2) - 14);
  ctx.fillStyle = '#ffffff';
  ctx.font = '500 21px system-ui, -apple-system, sans-serif';
  ctx.fillText('A gravação continua automaticamente.', width / 2, (height / 2) + 34);
  ctx.restore();

  recordingCanvasFrameCount += 1;
  recordingCanvasLastDrawAt = performance.now();
  try { recordingCanvasTrack?.requestFrame?.(); } catch (_error) { /* captureStream já envia quadros */ }
  return true;
}

function createStableRecordingStream() {
  stopStableRecordingPipeline();

  // Fora do iOS mantemos o caminho nativo. No iPhone/iPad usamos o canvas para
  // impedir o bug em que o áudio continua e o vídeo para de gerar quadros.
  if (!IS_IOS_WEBKIT) {
    recordingOutputStream = activeStream;
    return activeStream;
  }

  const preview = $('camera-preview');
  const canvas = document.createElement('canvas');
  if (typeof canvas.captureStream !== 'function') {
    // Fallback iOS: grava somente a track de vídeo. Assim, se o encoder de vídeo
    // falhar, não existe uma track de áudio mantendo artificialmente o arquivo até 90 s.
    const videoOnlyTrack = activeStream?.getVideoTracks?.()[0];
    recordingOutputStream = videoOnlyTrack ? new MediaStream([videoOnlyTrack]) : activeStream;
    return recordingOutputStream;
  }

  canvas.width = IOS_RECORD_CANVAS_WIDTH;
  canvas.height = IOS_RECORD_CANVAS_HEIGHT;

  const context = canvas.getContext('2d', {
    alpha: false,
    desynchronized: true
  });

  if (!context) {
    const videoOnlyTrack = activeStream?.getVideoTracks?.()[0];
    recordingOutputStream = videoOnlyTrack ? new MediaStream([videoOnlyTrack]) : activeStream;
    return recordingOutputStream;
  }

  recordingCanvas = canvas;
  recordingCanvasContext = context;

  // Desenha o primeiro quadro antes de criar o stream para o encoder não iniciar vazio.
  drawPreviewIntoRecordingCanvas(preview);

  const canvasStream = canvas.captureStream(IOS_RECORD_CANVAS_FPS);
  const canvasVideoTrack = canvasStream.getVideoTracks()[0];
  if (!canvasVideoTrack) {
    stopStableRecordingPipeline();
    const videoOnlyTrack = activeStream?.getVideoTracks?.()[0];
    recordingOutputStream = videoOnlyTrack ? new MediaStream([videoOnlyTrack]) : activeStream;
    return recordingOutputStream;
  }

  recordingCanvasTrack = canvasVideoTrack;
  recordingOutputOwnedTracks.push(canvasVideoTrack);

  const recorderTracks = [canvasVideoTrack];

  // Mantém áudio, mas usa clone quando possível para que encerrar o stream de gravação
  // não derrube a track original da câmera.
  const sourceAudioTrack = activeStream?.getAudioTracks?.()[0];
  if (sourceAudioTrack) {
    let recorderAudioTrack = sourceAudioTrack;
    try {
      recorderAudioTrack = typeof sourceAudioTrack.clone === 'function'
        ? sourceAudioTrack.clone()
        : sourceAudioTrack;
    } catch (_error) {
      recorderAudioTrack = sourceAudioTrack;
    }

    recorderTracks.push(recorderAudioTrack);
    if (recorderAudioTrack !== sourceAudioTrack) {
      recordingOutputOwnedTracks.push(recorderAudioTrack);
    }
  }

  recordingOutputStream = new MediaStream(recorderTracks);

  const frameIntervalMs = Math.round(1000 / IOS_RECORD_CANVAS_FPS);
  recordingCanvasTimer = window.setInterval(() => {
    if (!mediaRecorder || mediaRecorder.state !== 'recording' || discardRecording || recordingStopRequested) {
      return;
    }

    if (recordingCameraRecoveryInProgress) {
      drawRecordingRecoverySlate();
    } else {
      drawPreviewIntoRecordingCanvas(preview);
    }
  }, frameIntervalMs);

  return recordingOutputStream;
}

async function startVideoRecording() {
  if (!activeStream || recordingStartInProgress) {
    return;
  }

  if (mediaRecorder && mediaRecorder.state !== 'inactive') {
    return;
  }

  if (typeof MediaRecorder === 'undefined') {
    handleCameraError(new Error('Este navegador não oferece gravação de vídeo pela câmera. Use Chrome, Edge ou Safari atualizado.'));
    return;
  }

  const startButton = $('start-recording');
  recordingStartInProgress = true;
  startButton.disabled = true;
  startButton.textContent = 'Iniciando gravação...';

  // No iPhone, alterações de constraints/torch durante um MediaRecorder ativo podem
  // provocar um mute transitório da track e encerrar/pausar a gravação. Ajustamos o
  // flash antes do start e congelamos o monitor automático até o fim do vídeo.
  stopTorchAutoMonitor();

  try {
    await prepareTorchForCapture();

    const videoTrack = activeStream?.getVideoTracks?.()[0];
    if (!videoTrack || videoTrack.readyState !== 'live') {
      throw new Error('A câmera foi interrompida antes do início da gravação. Abra a câmera novamente e tente de novo.');
    }

    await waitForVideoTrackReadyForRecording(videoTrack);

    // Fora do iOS, sinaliza ao encoder que detalhes são mais importantes que
    // movimento. No WebKit/iPhone evitamos contentHint durante a abertura da
    // câmera: ele pode reconfigurar a track e provocar mute/stop logo no início.
    if (!IS_IOS_WEBKIT) {
      try {
        if ('contentHint' in videoTrack) {
          videoTrack.contentHint = 'detail';
        }
      } catch (_error) {
        // Navegadores que não aceitam a dica continuam com o perfil normal.
      }
    }

    const mimeType = selectRecordingMimeType();
    const options = {
      // O bitrate é uma dica de qualidade ao encoder. Não existe mais limite de MB para vídeo;
      // mantemos somente o encerramento automático em 90 segundos.
      videoBitsPerSecond: VIDEO_TARGET_VIDEO_BITRATE,
      audioBitsPerSecond: VIDEO_TARGET_AUDIO_BITRATE
    };

    if (mimeType) {
      options.mimeType = mimeType;
    }

    recordedChunks = [];
    recordedVideoBytes = 0;
    videoDurationSeconds = null;
    discardRecording = false;
    recordingStopRequested = false;
    recordingInterrupted = false;
    recordingMustRestart = false;
    recordingInterruptionReason = '';
    recordingCameraRecoveryInProgress = false;
    recordingCameraRecoveryCount = 0;
    clearRecordingMuteTimer();
    resetRecordingClock();
    const recorderStream = createStableRecordingStream();
    if (!recorderStream?.getVideoTracks?.().length) {
      throw new Error('Não foi possível preparar o stream de vídeo para gravação.');
    }

    mediaRecorder = new MediaRecorder(recorderStream, options);

    mediaRecorder.addEventListener('dataavailable', (event) => {
      if (event.data && event.data.size > 0) {
        recordedChunks.push(event.data);
        recordedVideoBytes += event.data.size;
      }
    });

    mediaRecorder.addEventListener('pause', () => {
      pauseRecordingClock();
      if (!discardRecording && !recordingStopRequested) {
        // Pause inesperado no WebKit é tratado como interrupção real. Em vez de
        // concatenar/retomar um MP4 potencialmente corrompido, reiniciamos do zero.
        recordingMustRestart = true;
        showRecordingInterrupted('O aparelho interrompeu a gravação. Toque em “Retomar gravação”; o vídeo será reiniciado do início para garantir um arquivo íntegro.');
        window.setTimeout(() => {
          if (mediaRecorder?.state === 'paused' && !recordingStopRequested && !discardRecording) {
            try { mediaRecorder.stop(); } catch (_error) { /* o stop pode já estar em andamento */ }
          }
        }, 0);
      }
    });

    mediaRecorder.addEventListener('resume', () => {
      resumeRecordingClock();
      clearRecordingInterruptedUi();
      startRecordingTimerLoop();
      startRecordingFrameWatchdog();
      void requestRecordingWakeLock();
    });

    mediaRecorder.addEventListener('error', (event) => {
      if (discardRecording || recordingStopRequested) return;
      recordingMustRestart = true;
      const detail = event?.error?.message || 'A gravação foi interrompida pelo navegador.';
      showRecordingInterrupted(`${detail} Toque em “Retomar gravação” para reiniciar o vídeo do início.`);
    });

    const recorderInstance = mediaRecorder;
    mediaRecorder.addEventListener('stop', () => handleVideoRecorderStopped(recorderInstance), { once: true });
    // Em WebKit/iPhone, gravar MP4 com timeslice pode gerar um contêiner que
    // o próprio elemento <video> não consegue reler depois. Como a duração é limitada a
    // 90 s, usamos um único bloco final, emitido com segurança no stop.
    mediaRecorder.start();
    recordingStartedAt = Date.now();

    if (mediaRecorder.state !== 'recording') {
      throw new Error('O navegador não conseguiu manter a gravação ativa. Tente novamente.');
    }

    resumeRecordingClock();
    startButton.hidden = true;
    $('resume-recording').hidden = true;
    $('stop-recording').hidden = false;
    $('stop-recording').disabled = false;
    $('stop-recording').textContent = 'Finalizar vídeo';
    $('cancel-camera').textContent = 'Cancelar gravação';
    $('recording-paused-banner').hidden = true;
    $('recording-indicator').hidden = false;
    const flashButton = $('toggle-flash');
    if (flashButton) flashButton.disabled = true;
    updateRecordingTimer();
    startRecordingTimerLoop();
    startRecordingFrameWatchdog();
    void requestRecordingWakeLock();
  } catch (error) {
    recordingStartedAt = 0;
    stopStableRecordingPipeline();
    handleCameraError(error);
  } finally {
    recordingStartInProgress = false;
    if (!startButton.hidden) {
      startButton.disabled = false;
      startButton.textContent = 'Iniciar gravação';
    }
  }
}

function stopVideoRecording() {
  if (mediaRecorder?.state !== 'recording') {
    return;
  }

  recordingStopRequested = true;
  mediaRecorder.stop();
}

function cancelCameraCapture() {
  if (mediaRecorder && (mediaRecorder.state === 'recording' || mediaRecorder.state === 'paused')) {
    discardRecording = true;
    recordingStopRequested = true;
    mediaRecorder.stop();
    return;
  }

  closeCameraModal();
}

function handleVideoRecorderStopped(stoppedRecorder) {
  if (stoppedRecorder !== mediaRecorder) {
    return;
  }

  pauseRecordingClock();
  stopRecordingTimerLoop();
  stopRecordingFrameWatchdog();
  void releaseRecordingWakeLock();

  if (discardRecording || recordingStopRequested) {
    finishVideoRecording();
    return;
  }

  // Alguns aparelhos encerram a câmera ao bloquear a tela. Nesse caso não é seguro
  // concatenar dois contêineres MP4/WebM no navegador. Mantemos a tela de retomada,
  // mas reiniciamos a gravação do zero se o MediaRecorder tiver sido encerrado.
  recordingInterrupted = true;
  recordingMustRestart = true;
  mediaRecorder = null;
  showRecordingInterrupted('O aparelho encerrou a câmera enquanto a tela estava inativa. Toque em “Retomar gravação”; para garantir um vídeo íntegro, a gravação será reiniciada do início.');
}

function finishVideoRecording() {
  const measuredDurationSeconds = getRecordedDurationSeconds();
  const renderedCanvasFrames = recordingCanvasFrameCount;
  stopRecordingTimerLoop();
  void releaseRecordingWakeLock();

  if (discardRecording) {
    recordedChunks = [];
    recordedVideoBytes = 0;
    videoDurationSeconds = null;
    mediaRecorder = null;
    resetRecordingClock();
    stopStableRecordingPipeline();
    closeCameraModal();
    return;
  }

  if (measuredDurationSeconds > VIDEO_MAX_DURATION_SECONDS + 0.5) {
    mediaRecorder = null;
    recordedChunks = [];
    recordedVideoBytes = 0;
    videoDurationSeconds = null;
    resetRecordingClock();
    handleCameraError(new Error('O vídeo deve ter no máximo 1 minuto e 30 segundos. Grave novamente.'));
    return;
  }

  // No caminho estabilizado do iOS esperamos pelo menos alguns desenhos por segundo.
  // Isso não mede o contêiner, mas evita aceitar gravação em que a thread de renderização
  // deixou de alimentar o canvas durante uma parte relevante do procedimento.
  if (IS_IOS_WEBKIT && measuredDurationSeconds >= 8) {
    const minimumExpectedCanvasFrames = Math.max(20, Math.floor(measuredDurationSeconds * 4));
    if (renderedCanvasFrames > 0 && renderedCanvasFrames < minimumExpectedCanvasFrames) {
      mediaRecorder = null;
      recordedChunks = [];
      recordedVideoBytes = 0;
      videoDurationSeconds = null;
      resetRecordingClock();
      stopStableRecordingPipeline();
      handleCameraError(new Error('A câmera não conseguiu manter quadros suficientes durante a gravação. Grave novamente sem sair da tela.'));
      return;
    }
  }

  const chunkType = String(recordedChunks.find((chunk) => chunk?.type)?.type || '').split(';')[0];
  const recorderType = (mediaRecorder?.mimeType || '').split(';')[0];
  const selectedType = selectRecordingMimeType().split(';')[0];
  const mimeType = chunkType || recorderType || selectedType || 'video/webm';

  if (!allowedVideoTypes.has(mimeType)) {
    mediaRecorder = null;
    recordedChunks = [];
    recordedVideoBytes = 0;
    videoDurationSeconds = null;
    resetRecordingClock();
    handleCameraError(new Error(`O formato de vídeo gerado (${mimeType}) não é compatível. Use Chrome, Edge ou Safari atualizado.`));
    return;
  }

  if (!recordedChunks.length) {
    mediaRecorder = null;
    recordedVideoBytes = 0;
    videoDurationSeconds = null;
    resetRecordingClock();
    handleCameraError(new Error('A câmera não entregou os dados do vídeo. Grave novamente sem sair da tela durante a gravação.'));
    return;
  }

  const blob = new Blob(recordedChunks, { type: mimeType });
  const extension = videoExtension(mimeType);
  const plate = slugify(request?.plate || 'veiculo');

  videoDurationSeconds = measuredDurationSeconds;
  videoFile = new File([blob], `video-vistoria-${plate}.${extension}`, {
    type: mimeType,
    lastModified: Date.now()
  });

  if (videoPreviewUrl) {
    URL.revokeObjectURL(videoPreviewUrl);
  }
  videoPreviewUrl = URL.createObjectURL(blob);

  $('video-preview').src = videoPreviewUrl;
  $('video-preview').hidden = false;
  $('video-status').textContent = `Vídeo gravado com duração válida (${Math.floor(measuredDurationSeconds)}s · ${formatBytes(blob.size)}).`;
  $('record-video').textContent = 'Gravar novamente';
  $('record-video').classList.add('captured');

  mediaRecorder = null;
  recordedChunks = [];
  recordedVideoBytes = 0;
  resetRecordingClock();
  closeCameraModal();
  updateCaptureSummary();
  scheduleDraftSave(0, 'Vídeo com duração mínima confirmada salvo neste aparelho para uma nova tentativa de envio.');
}

function getRecordedDurationMilliseconds() {
  const activeSegment = recordingActiveSince ? Math.max(0, Date.now() - recordingActiveSince) : 0;
  return Math.max(0, recordingElapsedMs + activeSegment);
}

function getRecordedDurationSeconds() {
  return getRecordedDurationMilliseconds() / 1000;
}

function resumeRecordingClock() {
  if (!recordingActiveSince) {
    recordingActiveSince = Date.now();
  }
}

function pauseRecordingClock() {
  if (recordingActiveSince) {
    recordingElapsedMs += Math.max(0, Date.now() - recordingActiveSince);
    recordingActiveSince = null;
  }
}

function resetRecordingClock() {
  stopRecordingTimerLoop();
  clearRecordingMuteTimer();
  stopRecordingFrameWatchdog();
  recordingElapsedMs = 0;
  recordingActiveSince = null;
  recordingStartedAt = 0;
  recordingCameraRecoveryInProgress = false;
  recordingCameraRecoveryCount = 0;
}

function startRecordingTimerLoop() {
  stopRecordingTimerLoop();
  recordingTimer = window.setInterval(updateRecordingTimer, 250);
  scheduleRecordingHardStop();
}

function scheduleRecordingHardStop() {
  if (recordingHardStopTimer) {
    window.clearTimeout(recordingHardStopTimer);
    recordingHardStopTimer = null;
  }
  if (!mediaRecorder || mediaRecorder.state !== 'recording') return;

  const remainingMs = Math.max(0, (VIDEO_AUTO_STOP_SECONDS * 1000) - getRecordedDurationMilliseconds());
  recordingHardStopTimer = window.setTimeout(() => {
    recordingHardStopTimer = null;
    if (mediaRecorder?.state === 'recording' && getRecordedDurationSeconds() >= VIDEO_AUTO_STOP_SECONDS - 0.1) {
      recordingStopRequested = true;
      $('recording-time').textContent = '01:30';
      $('stop-recording').disabled = true;
      $('stop-recording').textContent = 'Finalizando vídeo...';
      try { mediaRecorder.stop(); } catch (_error) { /* o evento stop pode já estar em andamento */ }
    }
  }, remainingMs + 25);
}

function stopRecordingTimerLoop() {
  if (recordingTimer) {
    window.clearInterval(recordingTimer);
    recordingTimer = null;
  }
  if (recordingHardStopTimer) {
    window.clearTimeout(recordingHardStopTimer);
    recordingHardStopTimer = null;
  }
}

function stopRecordingFrameWatchdog() {
  if (recordingFrameWatchdogTimer) {
    window.clearInterval(recordingFrameWatchdogTimer);
    recordingFrameWatchdogTimer = null;
  }

  const preview = $('camera-preview');
  if (recordingFrameCallbackId !== null && typeof preview?.cancelVideoFrameCallback === 'function') {
    try { preview.cancelVideoFrameCallback(recordingFrameCallbackId); } catch (_error) { /* callback já pode ter disparado */ }
  }

  recordingFrameCallbackId = null;
  recordingLastVideoFrameAt = 0;
  recordingLastPreviewTime = -1;
  recordingLastFrameMediaTime = -1;
  recordingLastPresentedFrames = -1;
  recordingLastDecodedFrames = -1;
}

function decodedFrameCount(preview) {
  if (!preview) return -1;
  try {
    const direct = Number(preview.webkitDecodedFrameCount);
    if (Number.isFinite(direct) && direct >= 0) return direct;
  } catch (_error) { /* propriedade não disponível */ }
  try {
    const quality = preview.getVideoPlaybackQuality?.();
    const total = Number(quality?.totalVideoFrames);
    if (Number.isFinite(total) && total >= 0) return total;
  } catch (_error) { /* API não disponível */ }
  return -1;
}

function armRecordingVideoFrameCallback(preview) {
  if (!preview || typeof preview.requestVideoFrameCallback !== 'function') return;
  if (!mediaRecorder || mediaRecorder.state !== 'recording') return;

  recordingFrameCallbackId = preview.requestVideoFrameCallback((_now, metadata = {}) => {
    recordingFrameCallbackId = null;

    const mediaTime = Number(metadata.mediaTime);
    const presentedFrames = Number(metadata.presentedFrames);
    let progressed = false;

    if (Number.isFinite(presentedFrames)) {
      if (recordingLastPresentedFrames < 0 || presentedFrames > recordingLastPresentedFrames) {
        progressed = true;
      }
      recordingLastPresentedFrames = Math.max(recordingLastPresentedFrames, presentedFrames);
    }

    if (Number.isFinite(mediaTime)) {
      if (recordingLastFrameMediaTime < 0 || mediaTime > recordingLastFrameMediaTime + 0.001) {
        progressed = true;
      }
      recordingLastFrameMediaTime = Math.max(recordingLastFrameMediaTime, mediaTime);
    }

    // Implementações antigas podem chamar o callback sem metadata. Nesse caso o
    // próprio callback continua sendo a melhor indicação de um quadro novo.
    if (!Number.isFinite(presentedFrames) && !Number.isFinite(mediaTime)) {
      progressed = true;
    }

    if (progressed) {
      recordingLastVideoFrameAt = performance.now();
    }

    if (mediaRecorder?.state === 'recording' && !discardRecording && !recordingStopRequested) {
      armRecordingVideoFrameCallback(preview);
    }
  });
}

async function recoverFrozenCameraDuringRecording() {
  if (recordingCameraRecoveryInProgress || captureMode !== 'video' || discardRecording || recordingStopRequested) return;
  if (!mediaRecorder || mediaRecorder.state !== 'recording') return;

  // Fora do WebKit não trocamos a track no meio da gravação, porque o fluxo nativo
  // normalmente encerra corretamente quando a câmera falha.
  if (!IS_IOS_WEBKIT) {
    interruptVideoRecordingAndRequireRestart(
      'A câmera deixou de fornecer imagens. Toque em “Retomar gravação” para reiniciar o vídeo sem trecho congelado.'
    );
    return;
  }

  recordingCameraRecoveryInProgress = true;
  recordingCameraRecoveryCount += 1;
  stopRecordingFrameWatchdog();
  clearRecordingMuteTimer();
  stopTorchAutoMonitor();
  drawRecordingRecoverySlate();

  const preview = $('camera-preview');
  const oldStream = activeStream;
  const oldVideoTrack = oldStream?.getVideoTracks?.()[0] || null;
  const liveAudioTracks = (oldStream?.getAudioTracks?.() || []).filter(track => track.readyState === 'live');
  let replacementStream = null;

  try {
    // O flag de recuperação impede o listener de "ended" de encerrar o recorder.
    if (oldVideoTrack) {
      try { oldVideoTrack.stop(); } catch (_error) { /* track já encerrada */ }
    }

    // Dá um pequeno intervalo para o WebKit liberar fisicamente a câmera antes de
    // solicitar uma nova track. O MediaRecorder continua gravando o canvas/áudio.
    await wait(180);

    replacementStream = await navigator.mediaDevices.getUserMedia({
      video: buildCameraVideoConstraints(true, activeFacingMode || 'environment'),
      audio: false
    });

    const replacementTrack = replacementStream.getVideoTracks()[0];
    if (!replacementTrack || replacementTrack.readyState !== 'live') {
      throw new Error('O aparelho não devolveu uma nova imagem da câmera.');
    }

    await optimizeVideoTrackForInspection(replacementTrack);

    const nextStream = new MediaStream([replacementTrack, ...liveAudioTracks]);
    activeStream = nextStream;
    preview.srcObject = nextStream;
    preview.muted = true;
    await preview.play();
    await waitForVideoTrackReadyForRecording(replacementTrack);
    attachVideoTrackInterruptionListeners();
    configureFocusControl();

    recordingLastVideoFrameAt = performance.now();
    recordingLastPreviewTime = Number.isFinite(preview.currentTime) ? preview.currentTime : -1;
    recordingLastFrameMediaTime = -1;
    recordingLastPresentedFrames = -1;
    recordingLastDecodedFrames = decodedFrameCount(preview);
    recordingCameraRecoveryInProgress = false;
    startRecordingFrameWatchdog();

    const status = $('flash-status');
    if (status) {
      status.hidden = false;
      status.textContent = 'Câmera recuperada automaticamente. Continue a vistoria normalmente.';
    }
  } catch (error) {
    replacementStream?.getTracks?.().forEach(track => {
      try { track.stop(); } catch (_error) { /* já encerrada */ }
    });
    recordingCameraRecoveryInProgress = false;
    interruptVideoRecordingAndRequireRestart(
      `${error?.message || 'A câmera não conseguiu se recuperar automaticamente.'} Toque em “Retomar gravação” para reiniciar o vídeo sem salvar imagem congelada.`
    );
  }
}

function startRecordingFrameWatchdog() {
  stopRecordingFrameWatchdog();

  const preview = $('camera-preview');
  if (!preview || !mediaRecorder || mediaRecorder.state !== 'recording') return;

  recordingLastVideoFrameAt = performance.now();
  recordingLastPreviewTime = Number.isFinite(preview.currentTime) ? preview.currentTime : -1;
  recordingLastDecodedFrames = decodedFrameCount(preview);
  armRecordingVideoFrameCallback(preview);

  recordingFrameWatchdogTimer = window.setInterval(() => {
    if (!mediaRecorder || mediaRecorder.state !== 'recording' || discardRecording || recordingStopRequested) return;
    if (recordingCameraRecoveryInProgress || document.visibilityState === 'hidden') return;

    const now = performance.now();
    const sinceStart = recordingStartedAt ? Math.max(0, Date.now() - recordingStartedAt) : 0;
    if (sinceStart < RECORDING_FRAME_START_GRACE_MS) return;

    // Preferimos contadores reais de quadros decodificados. currentTime sozinho não é
    // suficiente no WebKit: ele pode continuar avançando mesmo exibindo o mesmo quadro.
    const decoded = decodedFrameCount(preview);
    if (decoded >= 0) {
      if (recordingLastDecodedFrames < 0 || decoded > recordingLastDecodedFrames) {
        recordingLastDecodedFrames = decoded;
        recordingLastVideoFrameAt = now;
      }
    } else if (typeof preview.requestVideoFrameCallback !== 'function') {
      const currentPreviewTime = Number.isFinite(preview.currentTime) ? preview.currentTime : -1;
      if (currentPreviewTime >= 0 && currentPreviewTime > recordingLastPreviewTime + 0.02) {
        recordingLastPreviewTime = currentPreviewTime;
        recordingLastVideoFrameAt = now;
      }
    }

    if (!recordingLastVideoFrameAt || (now - recordingLastVideoFrameAt) < RECORDING_FRAME_STALL_MS) return;

    // A gravação iOS usa o canvas, portanto podemos trocar somente a câmera por uma
    // nova track sem parar o arquivo. Enquanto a câmera reabre, o canvas mostra uma
    // tela animada de recuperação — nunca deixamos dezenas de segundos congelados.
    void recoverFrozenCameraDuringRecording();
  }, 500);
}

function showRecordingInterrupted(message) {
  recordingInterrupted = true;
  recordingInterruptionReason = message || 'A gravação foi pausada.';
  stopRecordingTimerLoop();
  stopRecordingFrameWatchdog();
  $('recording-indicator').hidden = true;
  $('stop-recording').hidden = true;
  $('resume-recording').hidden = false;
  $('recording-paused-banner').hidden = false;
  $('recording-paused-text').textContent = recordingInterruptionReason;
  void releaseRecordingWakeLock();
}

function clearRecordingInterruptedUi() {
  recordingInterrupted = false;
  recordingMustRestart = false;
  recordingInterruptionReason = '';
  $('resume-recording').hidden = true;
  $('recording-paused-banner').hidden = true;
  $('recording-indicator').hidden = false;
  $('stop-recording').hidden = false;
}

function clearRecordingMuteTimer() {
  if (recordingMuteTimer) {
    window.clearTimeout(recordingMuteTimer);
    recordingMuteTimer = null;
  }
}

function interruptVideoRecordingAndRequireRestart(reason) {
  if (captureMode !== 'video' || discardRecording || recordingStopRequested || !mediaRecorder) {
    return;
  }

  clearRecordingMuteTimer();
  pauseRecordingClock();
  recordingMustRestart = true;
  showRecordingInterrupted(reason || 'A câmera foi interrompida. Toque em “Retomar gravação” para reiniciar o vídeo.');

  // Não usamos MediaRecorder.pause() no iPhone. O pause/resume do WebKit pode
  // deixar o MP4 vazio ou inválido. Encerramos o contêiner atual e descartamos esse
  // trecho; ao retomar, uma gravação nova começa do zero.
  if (mediaRecorder.state === 'recording' || mediaRecorder.state === 'paused') {
    try { mediaRecorder.stop(); } catch (_error) { /* o stop pode já estar em andamento */ }
  }
}

function scheduleVideoTrackMuteCheck(track, reason) {
  clearRecordingMuteTimer();
  if (!track || !mediaRecorder || mediaRecorder.state !== 'recording') return;

  const sinceStart = recordingStartedAt ? Math.max(0, Date.now() - recordingStartedAt) : RECORDING_START_GRACE_MS;
  const startupGraceRemaining = Math.max(0, RECORDING_START_GRACE_MS - sinceStart);
  const delay = Math.max(RECORDING_MUTE_CONFIRM_MS, startupGraceRemaining);

  recordingMuteTimer = window.setTimeout(() => {
    recordingMuteTimer = null;
    const currentTrack = activeStream?.getVideoTracks?.()[0];
    if (currentTrack !== track || !track.muted || track.readyState !== 'live') return;
    if (!mediaRecorder || mediaRecorder.state !== 'recording' || discardRecording || recordingStopRequested) return;

    interruptVideoRecordingAndRequireRestart(reason);
  }, delay);
}

function pauseVideoRecordingForInterruption(reason) {
  const track = activeStream?.getVideoTracks?.()[0];
  scheduleVideoTrackMuteCheck(track, reason);
}

async function resumeVideoRecording() {
  if (!recordingInterrupted) return;
  if (document.visibilityState === 'hidden') return;

  clearMessage();

  try {
    const currentVideoTrack = activeStream?.getVideoTracks?.()[0];
    const cameraEnded = !currentVideoTrack || currentVideoTrack.readyState === 'ended';

    if (recordingMustRestart || !mediaRecorder || mediaRecorder.state === 'inactive' || cameraEnded) {
      const previousRecorder = mediaRecorder;

      if (previousRecorder && previousRecorder.state !== 'inactive') {
        discardRecording = true;
        recordingStopRequested = true;
        await new Promise((resolve) => {
          previousRecorder.addEventListener('stop', resolve, { once: true });
          try {
            previousRecorder.stop();
          } catch (_error) {
            resolve();
          }
        });
      } else {
        mediaRecorder = null;
        closeCameraModal();
      }

      recordedChunks = [];
      recordedVideoBytes = 0;
      videoDurationSeconds = null;
      resetRecordingClock();
      recordingStopRequested = false;
      discardRecording = false;
      recordingMustRestart = false;

      await openVideoCamera();
      msg('A câmera foi encerrada pelo aparelho. A gravação foi reiniciada do início para garantir um vídeo íntegro.', 'success');
      startVideoRecording();
      return;
    }

    const preview = $('camera-preview');
    if (preview?.paused) {
      await preview.play();
    }

    if (currentVideoTrack.muted) {
      showRecordingInterrupted('A câmera ainda está pausada pelo aparelho. Aguarde alguns segundos e toque novamente em “Retomar gravação”.');
      return;
    }

    if (mediaRecorder.state === 'paused') {
      mediaRecorder.resume();
    } else if (mediaRecorder.state === 'recording') {
      resumeRecordingClock();
      clearRecordingInterruptedUi();
      updateRecordingTimer();
      startRecordingTimerLoop();
      void requestRecordingWakeLock();
    }
  } catch (error) {
    showRecordingInterrupted('Não foi possível retomar automaticamente. Toque novamente em “Retomar gravação”.');
    msg(error?.message || 'Não foi possível retomar a câmera.');
  }
}

function attachVideoTrackInterruptionListeners() {
  const track = activeStream?.getVideoTracks?.()[0];
  if (!track) return;

  track.addEventListener('mute', () => {
    if (captureMode !== 'video' || !mediaRecorder || discardRecording || recordingStopRequested) return;

    // No iPhone/WebKit, `mute` isolado continua não sendo confiável. Em vez de
    // parar imediatamente, o watchdog V9 confirma se novos frames realmente deixaram
    // de chegar. Isso evita tanto o falso corte no início quanto o relógio avançando
    // sobre uma imagem congelada.
    if (IS_IOS_WEBKIT) {
      clearRecordingMuteTimer();
      return;
    }

    pauseVideoRecordingForInterruption('A câmera permaneceu interrompida pelo aparelho. Toque em “Retomar gravação” para reiniciar o vídeo.');
  });

  track.addEventListener('unmute', () => {
    // Um mute transitório não deve cortar uma gravação saudável.
    clearRecordingMuteTimer();
  });

  track.addEventListener('ended', () => {
    clearRecordingMuteTimer();
    if (recordingCameraRecoveryInProgress) return;
    if (captureMode === 'video' && mediaRecorder && !discardRecording && !recordingStopRequested) {
      pauseRecordingClock();
      recordingMustRestart = true;
      showRecordingInterrupted('A câmera foi encerrada pelo aparelho. Toque em “Retomar gravação” para reabrir a câmera e reiniciar o vídeo.');
      if (mediaRecorder.state === 'recording' || mediaRecorder.state === 'paused') {
        try { mediaRecorder.stop(); } catch (_error) { /* o stop pode já estar em andamento */ }
      }
    }
  });
}

function resetFocusControlUi() {
  focusModesSupported = [];
  focusPointSupported = false;
  manualFocusSupported = false;
  focusDistanceCapability = null;
  focusBusy = false;

  if (focusReticleTimer) {
    window.clearTimeout(focusReticleTimer);
    focusReticleTimer = null;
  }
  if (manualFocusTimer) {
    window.clearTimeout(manualFocusTimer);
    manualFocusTimer = null;
  }

  const frame = $('camera-preview')?.closest('.camera-live-frame');
  frame?.classList.remove('tap-focus-enabled');

  const reticle = $('camera-focus-reticle');
  if (reticle) {
    reticle.hidden = true;
    reticle.classList.remove('focus-ok', 'focus-error');
  }

  const hint = $('camera-focus-hint');
  if (hint) hint.hidden = true;

  const controls = $('camera-focus-controls');
  if (controls) controls.hidden = true;

  const manual = $('manual-focus-wrap');
  if (manual) manual.hidden = true;

  const nearButton = $('focus-near');
  if (nearButton) nearButton.hidden = true;

  const resetButton = $('reset-focus');
  if (resetButton) resetButton.hidden = true;
}

function cameraFocusTrack() {
  const track = activeStream?.getVideoTracks?.()[0];
  return track?.readyState === 'live' ? track : null;
}

function showFocusReticle(clientX, clientY, state = 'working') {
  const frame = $('camera-preview')?.closest('.camera-live-frame');
  const reticle = $('camera-focus-reticle');
  if (!frame || !reticle) return;

  const rect = frame.getBoundingClientRect();
  if (!rect.width || !rect.height) return;

  const x = Math.max(16, Math.min(rect.width - 16, clientX - rect.left));
  const y = Math.max(16, Math.min(rect.height - 16, clientY - rect.top));
  reticle.style.left = `${x}px`;
  reticle.style.top = `${y}px`;
  reticle.hidden = false;
  reticle.classList.toggle('focus-ok', state === 'ok');
  reticle.classList.toggle('focus-error', state === 'error');

  if (focusReticleTimer) window.clearTimeout(focusReticleTimer);
  focusReticleTimer = window.setTimeout(() => {
    reticle.hidden = true;
    reticle.classList.remove('focus-ok', 'focus-error');
    focusReticleTimer = null;
  }, state === 'working' ? 1600 : 1100);
}

function updateFocusHint(message, state = '') {
  const hint = $('camera-focus-hint');
  if (!hint) return;
  hint.hidden = false;
  hint.dataset.focusState = state;
  hint.textContent = message;
}

function configureFocusControl() {
  resetFocusControlUi();

  const track = cameraFocusTrack();
  const rearCameraCapture = activeFacingMode === 'environment' && ['photo', 'video'].includes(captureMode);
  if (!track || !rearCameraCapture || typeof track.getCapabilities !== 'function' || typeof track.applyConstraints !== 'function') {
    return;
  }

  let capabilities = {};
  try {
    capabilities = track.getCapabilities() || {};
  } catch (_error) {
    return;
  }

  focusModesSupported = Array.isArray(capabilities.focusMode) ? capabilities.focusMode : [];
  const supportedConstraints = navigator.mediaDevices?.getSupportedConstraints?.() || {};
  focusPointSupported = Boolean(supportedConstraints.pointsOfInterest);

  const distance = capabilities.focusDistance;
  manualFocusSupported = Boolean(
    focusModesSupported.includes('manual')
    && distance
    && Number.isFinite(Number(distance.min))
    && Number.isFinite(Number(distance.max))
    && Number(distance.max) > Number(distance.min)
  );
  focusDistanceCapability = manualFocusSupported ? distance : null;

  const canTapFocus = focusPointSupported || focusModesSupported.includes('single-shot');
  const canResetAuto = focusModesSupported.includes('continuous') || focusModesSupported.includes('single-shot');
  const hasAnyFocusControl = canTapFocus || manualFocusSupported || canResetAuto;
  if (!hasAnyFocusControl) return;

  const frame = $('camera-preview')?.closest('.camera-live-frame');
  if (canTapFocus) frame?.classList.add('tap-focus-enabled');

  const controls = $('camera-focus-controls');
  if (controls) controls.hidden = false;

  if (canTapFocus) {
    updateFocusHint('Toque na numeração do chassi para focar exatamente naquele ponto.');
  } else if (manualFocusSupported) {
    updateFocusHint('Use o controle de foco manual para deixar a numeração do chassi nítida.');
  } else {
    updateFocusHint('Foco automático da câmera ativo.');
  }

  const nearButton = $('focus-near');
  if (nearButton) nearButton.hidden = !(manualFocusSupported || canTapFocus);

  const resetButton = $('reset-focus');
  if (resetButton) resetButton.hidden = !canResetAuto;

  const manualWrap = $('manual-focus-wrap');
  const manualRange = $('manual-focus-range');
  if (manualWrap && manualRange && manualFocusSupported) {
    const min = Number(distance.min);
    const max = Number(distance.max);
    const step = Number(distance.step) > 0 ? Number(distance.step) : Math.max((max - min) / 100, 0.01);
    const settings = typeof track.getSettings === 'function' ? track.getSettings() : {};
    const current = Number(settings?.focusDistance);
    const value = Number.isFinite(current) ? Math.max(min, Math.min(max, current)) : (min + max) / 2;

    manualRange.min = String(min);
    manualRange.max = String(max);
    manualRange.step = String(step);
    manualRange.value = String(value);
    manualWrap.hidden = false;
  }
}

async function focusCameraAtPoint(event) {
  if (focusBusy || activeFacingMode !== 'environment' || !['photo', 'video'].includes(captureMode)) return;

  const preview = $('camera-preview');
  const track = cameraFocusTrack();
  if (!preview || !track || typeof track.applyConstraints !== 'function') return;

  const canSingleShot = focusModesSupported.includes('single-shot');
  if (!focusPointSupported && !canSingleShot) return;

  const rect = preview.getBoundingClientRect();
  if (!rect.width || !rect.height) return;

  const x = Math.max(0, Math.min(1, (event.clientX - rect.left) / rect.width));
  const y = Math.max(0, Math.min(1, (event.clientY - rect.top) / rect.height));
  showFocusReticle(event.clientX, event.clientY, 'working');
  updateFocusHint('Ajustando foco no ponto selecionado…', 'working');
  focusBusy = true;

  try {
    const preferred = {};
    if (canSingleShot) preferred.focusMode = 'single-shot';
    if (focusPointSupported) preferred.pointsOfInterest = [{ x, y }];

    try {
      await track.applyConstraints({ advanced: [preferred] });
    } catch (firstError) {
      // Alguns Androids anunciam pointsOfInterest, mas rejeitam a combinação.
      // Nesse caso ainda forçamos uma nova varredura de autofocus single-shot.
      if (!canSingleShot) throw firstError;
      await track.applyConstraints({ advanced: [{ focusMode: 'single-shot' }] });
    }

    showFocusReticle(event.clientX, event.clientY, 'ok');
    updateFocusHint('Foco ajustado. Se o chassi ainda não estiver nítido, toque novamente ou use o foco manual.', 'ok');
  } catch (_error) {
    showFocusReticle(event.clientX, event.clientY, 'error');
    updateFocusHint(manualFocusSupported
      ? 'O aparelho não aceitou o foco por toque. Use o controle de foco manual abaixo.'
      : 'Este aparelho não permite escolher o ponto de foco pelo navegador. Mantenha a câmera firme e aguarde o autofocus.', 'error');
  } finally {
    focusBusy = false;
  }
}

async function focusCameraNear() {
  if (focusBusy || activeFacingMode !== 'environment' || !['photo', 'video'].includes(captureMode)) return;
  const track = cameraFocusTrack();
  if (!track || typeof track.applyConstraints !== 'function') return;

  focusBusy = true;
  updateFocusHint('Ajustando a câmera para chassi, odômetro e etiquetas próximas…', 'working');
  try {
    if (manualFocusSupported && focusDistanceCapability) {
      // focusDistance é exposto em dioptrias na maioria dos Chromium/Android:
      // valores maiores representam distâncias de foco menores (objeto mais perto).
      const closeValue = Number(focusDistanceCapability.max);
      await track.applyConstraints({ advanced: [{ focusMode: 'manual', focusDistance: closeValue }] });
      const range = $('manual-focus-range');
      if (range) range.value = String(closeValue);
      updateFocusHint('Foco próximo ativado. Aponte para o chassi/odômetro e ajuste a distância do celular até os caracteres ficarem nítidos.', 'ok');
      return;
    }

    if (focusModesSupported.includes('single-shot')) {
      await track.applyConstraints({ advanced: [{ focusMode: 'single-shot' }] });
      updateFocusHint('Autofocus para curta distância executado. Toque diretamente sobre a numeração para refazer o foco.', 'ok');
      return;
    }

    updateFocusHint('Este aparelho não oferece foco manual pelo navegador. Toque na numeração; se ainda borrar, afaste alguns centímetros e aproxime devagar.', 'error');
  } catch (_error) {
    updateFocusHint('O aparelho recusou o foco próximo. Toque diretamente sobre o chassi/odômetro ou use o controle manual.', 'error');
  } finally {
    focusBusy = false;
  }
}

async function applyManualFocusDistance(value) {
  if (!manualFocusSupported || focusBusy) return;
  const track = cameraFocusTrack();
  if (!track || typeof track.applyConstraints !== 'function') return;

  const distance = Number(value);
  if (!Number.isFinite(distance)) return;
  focusBusy = true;
  try {
    await track.applyConstraints({ advanced: [{ focusMode: 'manual', focusDistance: distance }] });
    updateFocusHint('Foco manual ativo. Ajuste até os caracteres do chassi ficarem nítidos.', 'ok');
  } catch (_error) {
    updateFocusHint('Este aparelho recusou o ajuste manual. Toque sobre o chassi para tentar o foco automático.', 'error');
  } finally {
    focusBusy = false;
  }
}

async function resetCameraAutoFocus() {
  const track = cameraFocusTrack();
  if (!track || typeof track.applyConstraints !== 'function' || focusBusy) return;

  focusBusy = true;
  try {
    if (focusModesSupported.includes('continuous')) {
      await track.applyConstraints({ advanced: [{ focusMode: 'continuous' }] });
      updateFocusHint('Foco automático contínuo reativado. Toque na imagem quando quiser focar o chassi.', 'ok');
    } else if (focusModesSupported.includes('single-shot')) {
      await track.applyConstraints({ advanced: [{ focusMode: 'single-shot' }] });
      updateFocusHint('Autofocus executado novamente.', 'ok');
    }
  } catch (_error) {
    updateFocusHint('Não foi possível alterar o modo de foco neste aparelho.', 'error');
  } finally {
    focusBusy = false;
  }
}

function configureTorchControl() {
  stopTorchAutoMonitor();

  const button = $('toggle-flash');
  const status = $('flash-status');
  const track = activeStream?.getVideoTracks?.()[0];

  torchEnabled = false;
  torchSupported = false;
  torchMode = 'auto';
  torchDarkSamples = 0;
  torchBrightSamples = 0;

  const rearCameraCapture = activeFacingMode === 'environment' && ['photo', 'video'].includes(captureMode);
  if (!button || !track || !rearCameraCapture) {
    if (button) button.hidden = true;
    if (status) status.hidden = true;
    return;
  }

  try {
    const capabilities = typeof track.getCapabilities === 'function' ? track.getCapabilities() : null;
    torchSupported = Boolean(capabilities?.torch);
  } catch (_error) {
    torchSupported = false;
  }

  button.hidden = !torchSupported;
  button.disabled = false;
  if (status) status.hidden = !torchSupported;

  if (!torchSupported) {
    return;
  }

  updateTorchUi('Flash automático ativo. O sistema acende a luz quando detectar pouca iluminação.');
  startTorchAutoMonitor();
}

function updateTorchUi(statusMessage = '') {
  const button = $('toggle-flash');
  const status = $('flash-status');
  if (!button || !torchSupported) return;

  if (torchMode === 'auto') {
    button.textContent = torchEnabled ? 'Flash: Automático · ligado' : 'Flash: Automático';
    button.title = 'Modo automático: toque para manter o flash sempre ligado.';
  } else if (torchMode === 'on') {
    button.textContent = 'Flash: Ligado';
    button.title = 'Flash ligado manualmente. Toque para desligar.';
  } else {
    button.textContent = 'Flash: Desligado';
    button.title = 'Flash desligado manualmente. Toque para voltar ao automático.';
  }

  button.dataset.flashMode = torchMode;
  button.setAttribute('aria-pressed', String(torchEnabled));

  if (status) {
    status.hidden = false;
    status.dataset.flashState = torchEnabled ? 'on' : 'off';
    status.textContent = statusMessage || (
      torchMode === 'auto'
        ? (torchEnabled ? 'Pouca luz detectada · flash ligado automaticamente.' : 'Flash automático monitorando a iluminação.')
        : (torchEnabled ? 'Flash ligado manualmente.' : 'Flash desligado manualmente.')
    );
  }
}

async function setTorchState(nextState, { silent = false, reason = '' } = {}) {
  if (!torchSupported || torchMonitorBusy) return false;
  const track = activeStream?.getVideoTracks?.()[0];
  if (!track || track.readyState !== 'live') return false;
  if (torchEnabled === nextState) {
    if (reason) updateTorchUi(reason);
    return true;
  }

  const button = $('toggle-flash');
  torchMonitorBusy = true;
  if (button) button.disabled = true;

  try {
    await track.applyConstraints({ advanced: [{ torch: nextState }] });
    torchEnabled = nextState;
    updateTorchUi(reason);
    return true;
  } catch (_error) {
    torchSupported = false;
    torchEnabled = false;
    stopTorchAutoMonitor();
    if (button) button.hidden = true;
    const status = $('flash-status');
    if (status) status.hidden = true;
    if (!silent) {
      msg('O flash não pôde ser controlado neste aparelho ou navegador. A vistoria pode continuar normalmente.');
    }
    return false;
  } finally {
    torchMonitorBusy = false;
    if (button) button.disabled = false;
  }
}

function sampleCameraBrightness() {
  const preview = $('camera-preview');
  if (!preview || preview.readyState < 2 || !preview.videoWidth || !preview.videoHeight) return null;

  try {
    const width = 48;
    const height = Math.max(28, Math.round(width * preview.videoHeight / preview.videoWidth));
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext('2d', { willReadFrequently: true });
    if (!context) return null;
    context.drawImage(preview, 0, 0, width, height);
    const pixels = context.getImageData(0, 0, width, height).data;

    let luminance = 0;
    let samples = 0;
    for (let offset = 0; offset < pixels.length; offset += 16) {
      const red = pixels[offset];
      const green = pixels[offset + 1];
      const blue = pixels[offset + 2];
      luminance += (0.2126 * red) + (0.7152 * green) + (0.0722 * blue);
      samples += 1;
    }
    return samples ? luminance / samples : null;
  } catch (_error) {
    return null;
  }
}

async function evaluateAutomaticTorch({ immediate = false } = {}) {
  if (!torchSupported || torchMode !== 'auto' || torchMonitorBusy || activeFacingMode !== 'environment') return;
  const brightness = sampleCameraBrightness();
  if (brightness === null) return;

  if (brightness < TORCH_AUTO_DARK_THRESHOLD) {
    torchDarkSamples += 1;
    torchBrightSamples = 0;
    if (!torchEnabled && (immediate || torchDarkSamples >= TORCH_AUTO_DARK_SAMPLES)) {
      await setTorchState(true, {
        silent: true,
        reason: 'Pouca luz detectada · flash ligado automaticamente.'
      });
    } else if (!torchEnabled) {
      updateTorchUi('Pouca luz detectada · preparando o flash automático.');
    }
    return;
  }

  if (brightness > TORCH_AUTO_BRIGHT_THRESHOLD) {
    torchBrightSamples += 1;
    torchDarkSamples = 0;
    if (torchEnabled && (immediate ? brightness > TORCH_AUTO_BRIGHT_THRESHOLD + 12 : torchBrightSamples >= TORCH_AUTO_BRIGHT_SAMPLES)) {
      await setTorchState(false, {
        silent: true,
        reason: 'Iluminação suficiente · flash automático em espera.'
      });
    } else if (!torchEnabled) {
      updateTorchUi('Iluminação suficiente · flash automático em espera.');
    }
    return;
  }

  torchDarkSamples = 0;
  torchBrightSamples = 0;
}

function startTorchAutoMonitor() {
  stopTorchAutoMonitor();
  if (!torchSupported || torchMode !== 'auto') return;
  window.setTimeout(() => void evaluateAutomaticTorch({ immediate: true }), 250);
  torchMonitorTimer = window.setInterval(() => {
    void evaluateAutomaticTorch();
  }, TORCH_AUTO_INTERVAL_MS);
}

function stopTorchAutoMonitor() {
  if (torchMonitorTimer) {
    window.clearInterval(torchMonitorTimer);
    torchMonitorTimer = null;
  }
  torchDarkSamples = 0;
  torchBrightSamples = 0;
}

async function prepareTorchForCapture() {
  if (!torchSupported || torchMode !== 'auto') return;
  await evaluateAutomaticTorch({ immediate: true });
  if (torchEnabled) {
    // Dá um pequeno tempo para o sensor ajustar exposição/foco depois de acender a luz.
    await wait(220);
  }
}

async function cycleCameraFlashMode() {
  if (!torchSupported) return;

  if (torchMode === 'auto') {
    torchMode = 'on';
    stopTorchAutoMonitor();
    await setTorchState(true, { reason: 'Flash ligado manualmente.' });
    return;
  }

  if (torchMode === 'on') {
    torchMode = 'off';
    stopTorchAutoMonitor();
    await setTorchState(false, { reason: 'Flash desligado manualmente.' });
    return;
  }

  torchMode = 'auto';
  updateTorchUi('Flash automático ativo. O sistema acende a luz quando detectar pouca iluminação.');
  startTorchAutoMonitor();
}

async function requestRecordingWakeLock() {
  if (!('wakeLock' in navigator) || document.visibilityState === 'hidden' || !mediaRecorder || mediaRecorder.state !== 'recording') {
    return;
  }

  try {
    if (recordingWakeLock) return;
    recordingWakeLock = await navigator.wakeLock.request('screen');
    recordingWakeLock.addEventListener('release', () => {
      recordingWakeLock = null;
    }, { once: true });
  } catch (_error) {
    recordingWakeLock = null;
  }
}

async function releaseRecordingWakeLock() {
  const wakeLock = recordingWakeLock;
  recordingWakeLock = null;
  if (!wakeLock) return;
  try {
    await wakeLock.release();
  } catch (_error) {
    // O navegador pode liberar o wake lock automaticamente ao ocultar a página.
  }
}

function selectRecordingMimeType() {
  if (typeof MediaRecorder === 'undefined' || typeof MediaRecorder.isTypeSupported !== 'function') {
    return '';
  }

  const candidates = IS_IOS_WEBKIT
    ? [
        // No iOS, o tipo MP4 simples deixa o WebKit escolher o perfil H.264/AAC
        // nativo. Evitamos forçar a variante com codecs, que já apresentou regressões
        // de gravação/contêiner em versões do WebKit.
        'video/mp4',
        'video/mp4;codecs=h264,aac',
        'video/webm;codecs=vp8,opus',
        'video/webm'
      ]
    : [
        'video/mp4;codecs=h264,aac',
        'video/mp4',
        'video/webm;codecs=vp9,opus',
        'video/webm;codecs=vp8,opus',
        'video/webm'
      ];

  return candidates.find((type) => MediaRecorder.isTypeSupported(type)) || '';
}

function updateRecordingTimer() {
  const elapsed = getRecordedDurationSeconds();
  const elapsedSeconds = Math.min(VIDEO_AUTO_STOP_SECONDS, Math.floor(elapsed));
  const minutes = String(Math.floor(elapsedSeconds / 60)).padStart(2, '0');
  const seconds = String(elapsedSeconds % 60).padStart(2, '0');
  $('recording-time').textContent = `${minutes}:${seconds}`;

  const stopButton = $('stop-recording');
  stopButton.disabled = false;
  stopButton.textContent = elapsed >= VIDEO_AUTO_STOP_SECONDS ? 'Finalizando vídeo...' : 'Finalizar vídeo';

  if (elapsed >= VIDEO_AUTO_STOP_SECONDS && mediaRecorder?.state === 'recording') {
    recordingStopRequested = true;
    mediaRecorder.stop();
  }
}

function closeCameraModal() {
  resetRecordingClock();
  void releaseRecordingWakeLock();
  stopTorchAutoMonitor();
  resetFocusControlUi();
  stopStableRecordingPipeline();
  stopCameraStream();
  $('camera-modal').hidden = true;
  $('camera-modal').classList.remove('photo-fullscreen');
  $('capture-photo').hidden = true;
  $('start-recording').hidden = true;
  $('resume-recording').hidden = true;
  $('toggle-flash').hidden = true;
  $('toggle-flash').disabled = false;
  $('stop-recording').hidden = true;
  $('stop-recording').disabled = false;
  $('stop-recording').textContent = 'Parar e usar vídeo';
  $('recording-paused-banner').hidden = true;
  $('recording-indicator').hidden = true;
  $('cancel-camera').textContent = 'Cancelar';
  $('camera-preview').srcObject = null;
  torchEnabled = false;
  torchSupported = false;
  torchMode = 'auto';
  torchMonitorBusy = false;
  recordingInterrupted = false;
  recordingMustRestart = false;
  recordingStopRequested = false;
  recordingStartInProgress = false;
  $('start-recording').disabled = false;
  $('start-recording').textContent = 'Iniciar gravação';
  document.body.classList.remove('camera-open');
  captureMode = null;
  currentPhotoIndex = null;
}

function stopCameraStream() {
  clearRecordingMuteTimer();
  stopStableRecordingPipeline();
  if (activeStream) {
    activeStream.getTracks().forEach((track) => track.stop());
    activeStream = null;
  }
}

function updateCaptureSummary() {
  const fullInspection = request?.requestType === 'NEW_INSPECTION';
  const orders = requiredAssetOrders();
  const requiredPhotos = fullInspection ? labels.length : 0;
  const confirmedPhotos = fullInspection
    ? labels.reduce((total, _label, index) => total + (serverHasAsset('PHOTO', index + 1) || photoFiles[index] ? 1 : 0), 0)
    : 0;
  const videoReady = serverHasAsset('VIDEO', orders.video) || Boolean(videoFile);
  const videoStatus = videoReady ? 'vídeo confirmado' : 'vídeo pendente';
  const registrationStatus = requiredPhotos
    ? ` · endereço ${(request?.residenceAddress || $('residence-address').value.trim()) ? 'confirmado' : 'pendente'}`
      + ` · CRLV ${(serverHasAsset('VEHICLE_DOCUMENT', orders.vehicleDocument) || vehicleDocumentFile) ? 'confirmado' : 'pendente'}`
      + ` · RG/CNH frente ${(serverHasAsset('IDENTITY_DOCUMENT', orders.identityFront) || identityDocumentFrontFile) ? 'confirmada' : 'pendente'}`
      + ` · RG/CNH verso ${(serverHasAsset('IDENTITY_DOCUMENT', orders.identityBack) || identityDocumentBackFile) ? 'confirmado' : 'pendente'}`
      + ` · assinatura ${(serverHasAsset('SIGNATURE', orders.signature) || signatureHasInk) ? 'confirmada' : 'pendente'}`
      + ` · avaria pré-existente ${hasPreexistingDamageSelected() ? 'informada' : 'não informada'}`
    : '';
  $('capture-summary').textContent = requiredPhotos
    ? `${confirmedPhotos} de ${requiredPhotos} fotos confirmadas · ${videoStatus}${registrationStatus}`
    : videoStatus;
}

function serverHasAsset(assetType, sortOrder) {
  return Array.isArray(request?.assets) && request.assets.some((asset) =>
    asset?.type === assetType && asset?.available === true && Number(asset?.sortOrder) === Number(sortOrder)
  );
}

function prepareVideoReplacementAfterValidationFailure(error) {
  const text = String(error?.message || '');
  if (!/v[ií]deo|quadros?|imagem congelada|integridade das imagens/i.test(text)) return false;

  const orders = requiredAssetOrders();
  if (Array.isArray(request?.assets)) {
    request.assets = request.assets.map((asset) =>
      asset?.type === 'VIDEO' && Number(asset?.sortOrder) === Number(orders.video)
        ? { ...asset, available: false }
        : asset
    );
  }

  videoFile = null;
  videoDurationSeconds = null;
  if (videoPreviewUrl) {
    URL.revokeObjectURL(videoPreviewUrl);
    videoPreviewUrl = null;
  }
  const preview = $('video-preview');
  if (preview) {
    preview.removeAttribute('src');
    preview.load();
    preview.hidden = true;
  }
  $('video-capture-card').hidden = false;
  $('video-status').textContent = 'O vídeo anterior não passou na validação das imagens. Grave um novo vídeo para substituir somente este item.';
  $('record-video').textContent = 'Gravar vídeo novamente';
  $('record-video').classList.remove('captured');
  updateCaptureSummary();
  return true;
}

function requiredAssetOrders() {
  const fullInspection = request?.requestType === 'NEW_INSPECTION';
  const photoCount = labels.length;
  return {
    video: fullInspection ? photoCount + 1 : 1,
    signature: photoCount + 2,
    vehicleDocument: photoCount + 3,
    identityFront: photoCount + 4,
    identityBack: photoCount + 5
  };
}

function pendingRequiredSlots() {
  if (!request) return [];
  const fullInspection = request.requestType === 'NEW_INSPECTION';
  const orders = requiredAssetOrders();
  const pending = [];

  if (fullInspection) {
    labels.forEach((label, index) => {
      if (!serverHasAsset('PHOTO', index + 1)) pending.push({ type: 'PHOTO', order: index + 1, label });
    });
  }
  if (!serverHasAsset('VIDEO', orders.video)) pending.push({ type: 'VIDEO', order: orders.video, label: 'Vídeo da vistoria' });

  if (fullInspection) {
    if (!request.residenceAddress) pending.push({ type: 'ADDRESS', order: 0, label: 'Endereço de residência' });
    if (!serverHasAsset('SIGNATURE', orders.signature)) pending.push({ type: 'SIGNATURE', order: orders.signature, label: 'Assinatura do associado' });
    if (!serverHasAsset('VEHICLE_DOCUMENT', orders.vehicleDocument)) pending.push({ type: 'VEHICLE_DOCUMENT', order: orders.vehicleDocument, label: 'CRLV do veículo' });
    if (!serverHasAsset('IDENTITY_DOCUMENT', orders.identityFront)) pending.push({ type: 'IDENTITY_DOCUMENT', order: orders.identityFront, label: 'RG ou CNH — frente' });
    if (!serverHasAsset('IDENTITY_DOCUMENT', orders.identityBack)) pending.push({ type: 'IDENTITY_DOCUMENT', order: orders.identityBack, label: 'RG ou CNH — verso' });
  }
  return pending;
}

function isSelectiveResubmission() {
  if (!request || !Array.isArray(request.assets)) return false;
  const hasPreservedSourceAsset = request.assets.some(asset => asset?.available === true && asset?.type !== 'REPORT');
  return hasPreservedSourceAsset && pendingRequiredSlots().length > 0;
}

function discardLocalCopiesAlreadyOnServer() {
  if (!request) return;
  const orders = requiredAssetOrders();
  photoFiles = photoFiles.map((file, index) => serverHasAsset('PHOTO', index + 1) ? null : file);
  if (serverHasAsset('VIDEO', orders.video)) videoFile = null;
  if (serverHasAsset('VEHICLE_DOCUMENT', orders.vehicleDocument)) vehicleDocumentFile = null;
  if (serverHasAsset('IDENTITY_DOCUMENT', orders.identityFront)) identityDocumentFrontFile = null;
  if (serverHasAsset('IDENTITY_DOCUMENT', orders.identityBack)) identityDocumentBackFile = null;
  if (serverHasAsset('SIGNATURE', orders.signature)) {
    restoredSignatureBlob = null;
    signatureHasInk = false;
  }
  if (request.residenceAddress) $('residence-address').value = request.residenceAddress;
  if (request.preexistingDamage || request.preexistingDamageDetails) {
    applyPreexistingDamageState(Boolean(request.preexistingDamage), request.preexistingDamageDetails || '');
  }
}

function stableUploadId(file, assetType, sortOrder) {
  const modified = Number(file?.lastModified || 0);
  return `${assetType.toLowerCase()}-${sortOrder}-${Number(file?.size || 0)}-${modified}`
    .replace(/[^a-zA-Z0-9_-]/g, '-')
    .slice(0, 140);
}

function wait(milliseconds) {
  return new Promise((resolve) => window.setTimeout(resolve, milliseconds));
}

function showUploadProgress(label, sentBytes, totalBytes) {
  const panel = $('upload-progress');
  const progress = $('upload-progress-bar');
  const text = $('upload-progress-text');
  panel.hidden = false;
  const safeTotal = Math.max(1, totalBytes);
  const percentage = Math.min(100, Math.round((sentBytes / safeTotal) * 100));
  progress.max = 100;
  progress.value = percentage;
  text.textContent = `${label} · ${percentage}% (${formatBytes(sentBytes)} de ${formatBytes(totalBytes)})`;
}

function hideUploadProgress() {
  $('upload-progress').hidden = true;
  $('upload-progress-bar').value = 0;
  $('upload-progress-text').textContent = '';
}

async function parseApiResponse(response) {
  const body = await response.json().catch(() => null);
  if (!response.ok) {
    const error = new Error(body?.message || `Falha no envio (${response.status}).`);
    error.status = response.status;
    throw error;
  }
  return body;
}

function loadImageFromBlob(blob) {
  return new Promise((resolve, reject) => {
    const objectUrl = URL.createObjectURL(blob);
    const image = new Image();
    image.onload = () => resolve({ image, objectUrl });
    image.onerror = () => {
      URL.revokeObjectURL(objectUrl);
      reject(new Error('A imagem salva não pôde ser decodificada.'));
    };
    image.src = objectUrl;
  });
}

async function reencodeReadableImage(file, asset) {
  const { image, objectUrl } = await loadImageFromBlob(file);
  try {
    const width = image.naturalWidth || image.width;
    const height = image.naturalHeight || image.height;
    if (!width || !height) throw new Error('A imagem recuperada não possui dimensões válidas.');

    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const context = canvas.getContext('2d');
    if (!context) throw new Error('Não foi possível reconstruir a imagem salva.');
    context.drawImage(image, 0, 0, width, height);

    const sourceType = String(file?.type || '').toLowerCase();
    const outputType = sourceType === 'image/png' ? 'image/png' : 'image/jpeg';
    const blob = await new Promise((resolve, reject) => {
      canvas.toBlob(
        value => value ? resolve(value) : reject(new Error('Não foi possível gerar uma nova cópia da imagem.')),
        outputType,
        outputType === 'image/jpeg' ? 0.94 : undefined
      );
    });
    const originalName = file?.name || `${slugify(asset?.label || 'foto')}.jpg`;
    const baseName = originalName.replace(/\.[^.]+$/, '') || 'imagem-recuperada';
    const extension = outputType === 'image/png' ? 'png' : 'jpg';
    const rebuilt = new File([blob], `${baseName}.${extension}`, {
      type: outputType,
      lastModified: Date.now()
    });
    const bytes = await readBlobArrayBufferReliable(rebuilt);
    draftBinaryCache.set(rebuilt, bytes);
    return rebuilt;
  } finally {
    URL.revokeObjectURL(objectUrl);
  }
}

function replaceRecoveredAssetFile(asset, file) {
  asset.file = file;
  if (asset.assetType === 'PHOTO') {
    const index = Number(asset.sortOrder) - 1;
    if (index >= 0 && index < photoFiles.length) {
      photoFiles[index] = file;
      if (photoPreviewUrls[index]) URL.revokeObjectURL(photoPreviewUrls[index]);
      photoPreviewUrls[index] = URL.createObjectURL(file);
      const image = $(`photo-preview-${index}`);
      if (image) {
        image.src = photoPreviewUrls[index];
        image.hidden = false;
      }
      const status = $(`photo-status-${index}`);
      if (status) status.textContent = 'Foto antiga recuperada automaticamente neste aparelho.';
    }
  } else if (asset.assetType === 'VIDEO') {
    videoFile = file;
  } else if (asset.assetType === 'VEHICLE_DOCUMENT') {
    vehicleDocumentFile = file;
  } else if (asset.assetType === 'IDENTITY_DOCUMENT') {
    const orders = requiredAssetOrders();
    if (Number(asset.sortOrder) === Number(orders.identityFront)) identityDocumentFrontFile = file;
    if (Number(asset.sortOrder) === Number(orders.identityBack)) identityDocumentBackFile = file;
  }
}

async function prepareAssetForUpload(asset) {
  const file = asset?.file;
  if (!file || !Number.isFinite(Number(file.size)) || Number(file.size) <= 0) {
    throw new Error(`${asset?.label || 'Arquivo'} está vazio ou indisponível.`);
  }

  // V15: nunca materializamos um vídeo inteiro em ArrayBuffer. Validamos apenas
  // um pequeno trecho e cada parte de 1 MB será lida somente na hora do upload.
  const probeBytes = Math.min(Number(file.size), 64 * 1024);
  try {
    const probe = file.slice(0, probeBytes);
    const buffer = await readBlobArrayBufferReliable(probe);
    if (buffer.byteLength !== probeBytes) throw new Error('Leitura local incompleta.');
    return;
  } catch (originalError) {
    const isRecoverableImage = asset.assetType === 'PHOTO'
      || /^image\//i.test(String(file?.type || ''));
    if (!isRecoverableImage) {
      console.error('Arquivo local não pôde ser lido para upload', {
        assetType: asset.assetType,
        sortOrder: asset.sortOrder,
        size: file?.size,
        error: originalError
      });
      throw new Error(
        `${asset.label || 'Arquivo'} está salvo no aparelho, mas o navegador não consegue mais ler seus dados. `
        + 'Selecione ou grave este arquivo novamente e tente continuar o envio.'
      );
    }

    try {
      const rebuilt = await reencodeReadableImage(file, asset);
      replaceRecoveredAssetFile(asset, rebuilt);
      const rebuiltProbe = rebuilt.slice(0, Math.min(rebuilt.size, 64 * 1024));
      await readBlobArrayBufferReliable(rebuiltProbe);
      updateDraftPanel(`${asset.label} foi recuperado automaticamente do rascunho antigo.`, 'ok');
    } catch (recoveryError) {
      console.error('Falha ao recuperar imagem de rascunho antigo', { originalError, recoveryError });
      throw new Error(
        `${asset.label || 'A imagem'} está no rascunho, mas o iPhone não permite mais acessar nem reconstruir os dados dessa cópia. `
        + 'Selecione-a novamente ou refaça somente este item.'
      );
    }
  }
}

async function uploadChunkWithRetry(asset, uploadId, chunkIndex, totalChunks, chunkBuffer) {
  let lastError = null;

  for (let attempt = 1; attempt <= UPLOAD_MAX_ATTEMPTS; attempt += 1) {
    const params = new URLSearchParams({
      assetType: asset.assetType,
      sortOrder: String(asset.sortOrder),
      label: asset.label || '',
      uploadId,
      chunkIndex: String(chunkIndex),
      totalChunks: String(totalChunks),
      totalSize: String(asset.file.size),
      chunkSize: String(chunkBuffer.byteLength),
      contentType: asset.file.type || 'application/octet-stream'
    });
    if (asset.assetType === 'VIDEO' && Number.isFinite(asset.durationSeconds)) {
      params.set('videoDurationSeconds', String(asset.durationSeconds));
    }

    const uploadPath = `/api/public/inspections/${encodeURIComponent(token)}/upload-chunk-raw?${params.toString()}`;
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 5 * 60 * 1000);
    try {
      const response = await fetch(
        window.NH_API?.backend(uploadPath) || uploadPath,
        {
          method: 'POST',
          headers: {
            'Content-Type': 'application/octet-stream',
            'Cache-Control': 'no-store'
          },
          body: chunkBuffer.slice(0),
          signal: controller.signal
        }
      );
      return await parseApiResponse(response);
    } catch (error) {
      lastError = error;
      const retryable = !error?.status || error.status >= 500 || error.name === 'AbortError';
      if (!retryable || attempt === UPLOAD_MAX_ATTEMPTS) break;
      const delay = retryDelayMilliseconds(error, attempt);
      updateDraftPanel(
        `A conexão oscilou. Tentando novamente a parte ${chunkIndex + 1} de ${totalChunks} em instantes...`,
        'warning'
      );
      await wait(delay);
    } finally {
      window.clearTimeout(timeout);
    }
  }

  throw lastError || new Error('Não foi possível enviar uma parte do arquivo.');
}

async function fetchChunkStatus(asset, uploadId, totalChunks) {
  const params = new URLSearchParams({
    assetType: asset.assetType,
    sortOrder: String(asset.sortOrder),
    uploadId,
    totalChunks: String(totalChunks)
  });
  try {
    const response = await fetch(
      window.NH_API?.backend(`/api/public/inspections/${encodeURIComponent(token)}/upload-chunk-status?${params}`)
        || `/api/public/inspections/${encodeURIComponent(token)}/upload-chunk-status?${params}`,
      { cache: 'no-store' }
    );
    if (!response.ok) return { complete: false, receivedChunks: [] };
    const body = await response.json();
    if (body?.inspection) request = body.inspection;
    return body || { complete: false, receivedChunks: [] };
  } catch (_) {
    return { complete: false, receivedChunks: [] };
  }
}

async function uploadAssetInChunks(asset, progressState) {
  const replacingStoredVideo = asset.assetType === 'VIDEO'
    && Boolean(asset.file)
    && serverHasAsset(asset.assetType, asset.sortOrder);

  if (!replacingStoredVideo && serverHasAsset(asset.assetType, asset.sortOrder)) {
    progressState.sentBytes += asset.file.size;
    showUploadProgress(`${asset.label} já estava salvo`, progressState.sentBytes, progressState.totalBytes);
    return;
  }

  await prepareAssetForUpload(asset);

  const totalChunks = Math.max(1, Math.ceil(asset.file.size / UPLOAD_CHUNK_BYTES));
  const uploadId = stableUploadId(asset.file, asset.assetType, asset.sortOrder);
  const status = await fetchChunkStatus(asset, uploadId, totalChunks);
  if (status.complete || (!replacingStoredVideo && serverHasAsset(asset.assetType, asset.sortOrder))) {
    progressState.sentBytes += asset.file.size;
    showUploadProgress(`${asset.label} já estava salvo`, progressState.sentBytes, progressState.totalBytes);
    return;
  }
  const receivedChunks = new Set(Array.isArray(status.receivedChunks) ? status.receivedChunks.map(Number) : []);

  for (let chunkIndex = 0; chunkIndex < totalChunks; chunkIndex += 1) {
    const start = chunkIndex * UPLOAD_CHUNK_BYTES;
    const end = Math.min(asset.file.size, start + UPLOAD_CHUNK_BYTES);
    const expectedBytes = end - start;

    if (receivedChunks.has(chunkIndex)) {
      progressState.sentBytes += expectedBytes;
      showUploadProgress(
        `${asset.label}: parte ${chunkIndex + 1} já recebida`,
        progressState.sentBytes,
        progressState.totalBytes
      );
      continue;
    }

    showUploadProgress(
      `${asset.label}: enviando parte ${chunkIndex + 1} de ${totalChunks}`,
      progressState.sentBytes,
      progressState.totalBytes
    );

    // Lê apenas a parte atual. Isso mantém o uso de memória praticamente constante
    // mesmo quando o vídeo nativo possui centenas de MB.
    const chunkBlob = asset.file.slice(start, end);
    const chunkBuffer = await readBlobArrayBufferReliable(chunkBlob);
    if (chunkBuffer.byteLength !== expectedBytes) {
      throw new Error(`${asset.label}: a parte ${chunkIndex + 1} não pôde ser lida integralmente.`);
    }

    const body = await uploadChunkWithRetry(asset, uploadId, chunkIndex, totalChunks, chunkBuffer);
    if (body?.inspection) request = body.inspection;
    if (chunkIndex === totalChunks - 1 && body?.complete !== true) {
      throw new Error(`${asset.label} ainda não foi confirmado no banco de dados.`);
    }
    progressState.sentBytes += chunkBuffer.byteLength;
    showUploadProgress(asset.label, progressState.sentBytes, progressState.totalBytes);
  }

  const confirmedStatus = await fetchChunkStatus(asset, uploadId, totalChunks);
  if (confirmedStatus?.inspection) request = confirmedStatus.inspection;

  if (confirmedStatus?.complete !== true || !serverHasAsset(asset.assetType, asset.sortOrder)) {
    throw new Error(`${asset.label} chegou ao servidor, mas não foi gravado no banco de dados. Tente novamente.`);
  }
}

async function finalizeResumableUpload(residenceAddress, preexistingDamage, preexistingDamageDetails) {
  let lastError = null;
  for (let attempt = 1; attempt <= UPLOAD_MAX_ATTEMPTS; attempt += 1) {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 5 * 60 * 1000);
    try {
      const response = await fetch(
        window.NH_API?.backend(`/api/public/inspections/${encodeURIComponent(token)}/finalize-upload`)
          || `/api/public/inspections/${encodeURIComponent(token)}/finalize-upload`,
        {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ residenceAddress, preexistingDamage, preexistingDamageDetails }),
          signal: controller.signal
        }
      );
      return await parseApiResponse(response);
    } catch (error) {
      lastError = error;
      const completedDespiteFailure = await checkIfServerCompleted();
      if (completedDespiteFailure) return null;
      const retryable = !error?.status || error.status >= 500 || error.name === 'AbortError';
      if (!retryable || attempt === UPLOAD_MAX_ATTEMPTS) break;
      await wait(retryDelayMilliseconds(error, attempt));
    } finally {
      window.clearTimeout(timeout);
    }
  }
  throw lastError || new Error('Não foi possível finalizar a vistoria.');
}

async function readVideoDurationSeconds(file) {
  if (!file) return 0;
  const url = URL.createObjectURL(file);
  try {
    const duration = await new Promise((resolve, reject) => {
      const video = document.createElement('video');
      const timeout = window.setTimeout(() => reject(new Error('Não foi possível confirmar a duração do vídeo.')), 15000);
      video.preload = 'metadata';
      video.muted = true;
      video.playsInline = true;
      video.onloadedmetadata = () => {
        window.clearTimeout(timeout);
        const value = Number(video.duration);
        if (Number.isFinite(value) && value > 0) resolve(value);
        else reject(new Error('Não foi possível confirmar a duração do vídeo.'));
      };
      video.onerror = () => {
        window.clearTimeout(timeout);
        reject(new Error('Não foi possível ler o vídeo gravado.'));
      };
      video.src = url;
    });
    return duration;
  } finally {
    URL.revokeObjectURL(url);
  }
}

$('upload-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  clearMessage();

  const fullInspection = request.requestType === 'NEW_INSPECTION';
  const orders = requiredAssetOrders();
  const missingPhotoIndex = fullInspection
    ? photoFiles.findIndex((file, index) => !serverHasAsset('PHOTO', index + 1) && !file)
    : -1;

  if (missingPhotoIndex >= 0) {
    msg(`Registre a foto obrigatória: ${labels[missingPhotoIndex]}.`);
    document.querySelector(`[data-photo-index="${missingPhotoIndex}"]`)?.focus();
    return;
  }

  if (!serverHasAsset('VIDEO', orders.video) && !videoFile) {
    msg('Grave o vídeo da vistoria antes de enviar.');
    $('record-video').focus();
    return;
  }

  if (videoFile) {
    // Para vídeo recém-gravado, a duração já foi medida pelo relógio monotônico da
    // própria gravação. Reabrir o MP4 apenas para ler metadata cria falso erro no
    // Safari/iPhone, que em algumas versões não relê imediatamente o arquivo que acabou
    // de produzir. Só fazemos a leitura do arquivo como fallback para rascunhos antigos.
    if (!Number.isFinite(videoDurationSeconds) || videoDurationSeconds <= 0) {
      try {
        const measuredDuration = await readVideoDurationSeconds(videoFile);
        videoDurationSeconds = Number.isFinite(measuredDuration) && measuredDuration > 0
          ? measuredDuration
          : null;
      } catch (error) {
        if (!USE_NATIVE_IOS_VIDEO_CAPTURE) {
          msg(error?.message || 'Não foi possível confirmar a duração do vídeo. Grave novamente.');
          $('record-video').focus();
          return;
        }
        // No iPhone, ffprobe no backend é a fonte definitiva de duração.
        videoDurationSeconds = null;
      }
    }
    if (USE_NATIVE_IOS_VIDEO_CAPTURE
        && Number.isFinite(videoDurationSeconds)
        && videoDurationSeconds > VIDEO_MAX_DURATION_SECONDS + 0.5) {
      updateDraftPanel(
        `O vídeo possui ${Math.floor(videoDurationSeconds)} segundos. O envio continuará normalmente e o servidor manterá apenas os primeiros 1min30s.`,
        'warning'
      );
    } else if (!USE_NATIVE_IOS_VIDEO_CAPTURE
        && Number.isFinite(videoDurationSeconds)
        && videoDurationSeconds > VIDEO_MAX_DURATION_SECONDS + 0.5) {
      msg(`O vídeo deve ter no máximo 1 minuto e 30 segundos. O vídeo atual possui ${Math.floor(videoDurationSeconds)} segundos.`);
      $('record-video').focus();
      return;
    }
  }

  let residenceAddress = request.residenceAddress || '';
  if (fullInspection && !residenceAddress) {
    try {
      // O CEP deve existir, mas uma API pública indisponível não impede o cadastro
      // se o associado preencher e conferir os campos obrigatórios manualmente.
      residenceAddress = window.NHResidenceAddress.compose();
      $('residence-address').value = residenceAddress;
    } catch (err) {
      msg(err.message || 'Informe o CEP e o endereço completo do associado.');
      const requiredFields = ['residence-cep','residence-street','residence-number','residence-neighborhood','residence-city','residence-state'];
      requiredFields.map(id => $(id)).find(input => input && !input.value.trim())?.focus();
      return;
    }
  }

  const preexistingDamage = fullInspection && hasPreexistingDamageSelected();
  const preexistingDamageDetails = fullInspection ? ($('preexisting-damage-details')?.value.trim() || '') : '';
  if (preexistingDamage && !preexistingDamageDetails) {
    msg('Informe qual peça está com avaria pré-existente antes de concluir.');
    $('preexisting-damage-details')?.focus();
    return;
  }

  if (fullInspection && !serverHasAsset('VEHICLE_DOCUMENT', orders.vehicleDocument) && !vehicleDocumentFile) {
    msg('Envie o CRLV do veículo antes de concluir.');
    $('vehicle-document').focus();
    return;
  }

  if (fullInspection && !serverHasAsset('IDENTITY_DOCUMENT', orders.identityFront) && !identityDocumentFrontFile) {
    msg('Envie a frente do RG ou da CNH do associado antes de concluir.');
    $('identity-document-front').focus();
    return;
  }

  if (fullInspection && !serverHasAsset('IDENTITY_DOCUMENT', orders.identityBack) && !identityDocumentBackFile) {
    msg('Envie o verso do RG ou da CNH do associado antes de concluir.');
    $('identity-document-back').focus();
    return;
  }

  if (fullInspection && !serverHasAsset('SIGNATURE', orders.signature) && !signatureHasInk) {
    msg('Solicite que o associado assine com o dedo antes de concluir.');
    $('signature-pad').focus();
    return;
  }

  const button = event.submitter || $('submit-inspection');
  button.disabled = true;
  button.textContent = 'Salvando rascunho...';
  await saveDraftNow('Tudo salvo neste aparelho. Iniciando o envio por partes...');

  try {
    const assets = [];
    if (fullInspection) {
      photoFiles.forEach((file, index) => {
        if (!serverHasAsset('PHOTO', index + 1) && file) {
          assets.push({ assetType: 'PHOTO', sortOrder: index + 1, label: labels[index], file });
        }
      });
    }

    if (videoFile) {
      assets.push({
        assetType: 'VIDEO',
        sortOrder: orders.video,
        label: 'Vídeo da vistoria',
        file: videoFile,
        durationSeconds: videoDurationSeconds
      });
    }

    if (fullInspection) {
      if (!serverHasAsset('SIGNATURE', orders.signature)) {
        const signatureBlob = await exportSignatureBlob();
        assets.push({
          assetType: 'SIGNATURE',
          sortOrder: orders.signature,
          label: 'Assinatura do associado',
          file: new File([signatureBlob], 'assinatura-associado.png', {
            type: 'image/png',
            lastModified: Date.now()
          })
        });
      }
      if (!serverHasAsset('VEHICLE_DOCUMENT', orders.vehicleDocument) && vehicleDocumentFile) {
        assets.push({ assetType: 'VEHICLE_DOCUMENT', sortOrder: orders.vehicleDocument, label: 'CRLV do veículo', file: vehicleDocumentFile });
      }
      if (!serverHasAsset('IDENTITY_DOCUMENT', orders.identityFront) && identityDocumentFrontFile) {
        assets.push({ assetType: 'IDENTITY_DOCUMENT', sortOrder: orders.identityFront, label: 'RG ou CNH — frente', file: identityDocumentFrontFile });
      }
      if (!serverHasAsset('IDENTITY_DOCUMENT', orders.identityBack) && identityDocumentBackFile) {
        assets.push({ assetType: 'IDENTITY_DOCUMENT', sortOrder: orders.identityBack, label: 'RG ou CNH — verso', file: identityDocumentBackFile });
      }
    }

    button.textContent = 'Preparando envio...';
    showUploadProgress('Preparando envio seguro por partes', 0, 1);

    // Não materializamos todos os arquivos antecipadamente. Cada arquivo/parte é
    // validado imediatamente antes do envio para manter a memória estável no mobile.
    await saveDraftNow('Fotos e documentos locais atualizados antes do envio.');

    const progressState = {
      totalBytes: assets.reduce((total, asset) => total + asset.file.size, 0),
      sentBytes: 0
    };

    button.textContent = 'Enviando arquivos...';
    showUploadProgress('Preparando envio seguro', 0, progressState.totalBytes);

    for (const asset of assets) {
      await uploadAssetInChunks(asset, progressState);
    }

    button.textContent = 'Finalizando vistoria...';
    showUploadProgress('Gerando relatório e confirmando os arquivos no banco de dados', progressState.totalBytes, progressState.totalBytes);
    const body = await finalizeResumableUpload(residenceAddress, preexistingDamage, preexistingDamageDetails);
    const completed = body?.inspection;
    if (!databaseCompletionConfirmed(completed)) {
      throw new Error('O servidor ainda não confirmou todos os arquivos no banco de dados. Tente continuar o envio.');
    }
    showComplete(completed);
  } catch (error) {
    const completedDespiteFailure = await checkIfServerCompleted();
    if (completedDespiteFailure) return;

    const videoNeedsReplacement = prepareVideoReplacementAfterValidationFailure(error);
    await saveDraftNow(videoNeedsReplacement
      ? 'O vídeo não passou na validação. Os demais itens permanecem salvos; grave somente um novo vídeo.'
      : 'O envio foi interrompido. Os arquivos locais e as partes já confirmadas permanecem salvos.');
    msg(friendlyNetworkMessage(
      error,
      'A conexão interrompeu o envio. As partes já enviadas ficaram salvas no servidor e o restante continua neste aparelho. Toque em “Continuar envio”.'
    ));
    button.disabled = false;
    button.textContent = 'Continuar envio';
    updateDraftPanel(
      videoNeedsReplacement
        ? 'As fotos e documentos já confirmados permanecem salvos. Regrave somente o vídeo e toque em “Continuar envio”.'
        : 'As fotos já confirmadas não serão enviadas novamente. O sistema continuará do ponto em que parou.',
      'warning'
    );
    window.scrollTo({ top: 0, behavior: 'smooth' });
  }
});

function showComplete(data) {
  request = data;
  hideUploadProgress();
  void removeDraft();
  $('connection-actions').hidden = true;
  hideGuidePage();
  closeCameraModal();
  $('guideline-card').hidden = true;
  $('upload-card').hidden = true;
  $('complete').hidden = false;

  if (data.reportUrl) {
    $('report-link').href = data.reportUrl;
    $('report-link').hidden = false;
  } else {
    $('report-link').hidden = true;
  }

  $('whatsapp-notice').textContent = 'Todos os arquivos, documentos e o relatório foram armazenados com segurança e permanecem disponíveis para a equipe de análise junto à vistoria.';

  $('title').textContent = 'Vistoria concluída';
  $('subtitle').textContent = `${data.associateName} · ${vehiclePlateLabel(data.plate)}`;
  window.scrollTo({ top: 0, behavior: 'smooth' });
}


const DOCUMENT_MAX_BYTES = 15 * 1024 * 1024;
const FILE_MIME_BY_EXTENSION = {
  jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', webp: 'image/webp',
  mp4: 'video/mp4', mov: 'video/quicktime', webm: 'video/webm', '3gp': 'video/3gpp',
  pdf: 'application/pdf', doc: 'application/msword',
  docx: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  odt: 'application/vnd.oasis.opendocument.text', rtf: 'application/rtf', txt: 'text/plain'
};
const DOCUMENT_TYPES = new Set([
  'application/pdf',
  'application/msword',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'application/vnd.oasis.opendocument.text',
  'application/rtf',
  'text/rtf',
  'text/plain',
  'image/jpeg',
  'image/png',
  'image/webp'
]);

function normalizedDocumentType(file) {
  const type = String(file?.type || '').toLowerCase().split(';', 1)[0].trim();
  return type === 'image/jpg' ? 'image/jpeg' : type;
}

function validateDocumentFile(file, label) {
  if (!file) return null;
  const currentType = normalizedDocumentType(file);
  const inferredType = FILE_MIME_BY_EXTENSION[fileExtension(file)];
  const contentType = DOCUMENT_TYPES.has(currentType) ? currentType : inferredType;
  if (!contentType || !DOCUMENT_TYPES.has(contentType)) {
    throw new Error(`Envie ${label} em PDF, DOC, DOCX, ODT, RTF, TXT, JPG, PNG ou WebP.`);
  }
  if (file.size <= 0) {
    throw new Error(`${label} está vazio.`);
  }
  if (file.size > DOCUMENT_MAX_BYTES) {
    throw new Error(`${label} deve possuir no máximo 15 MB.`);
  }
  if (file.type === contentType) return file;
  return new File([file], file.name || 'documento', {
    type: contentType,
    lastModified: file.lastModified || Date.now()
  });
}

function updateDocumentStatus(inputId, file, restored = false) {
  const card = $(inputId)?.closest('.document-upload-card');
  const status = $(`${inputId}-status`);
  if (!card || !status) return;
  if (!file) {
    card.classList.remove('has-file');
    status.textContent = 'Documento pendente.';
    return;
  }
  card.classList.add('has-file');
  status.textContent = `${restored ? 'Documento recuperado' : 'Documento selecionado'}: ${file.name || 'arquivo'} (${formatBytes(file.size)}).`;
}

function handleDocumentSelection(inputId, label) {
  const input = $(inputId);
  input.addEventListener('change', () => {
    clearMessage();
    try {
      const file = validateDocumentFile(input.files?.[0] || null, label);
      if (inputId === 'vehicle-document') vehicleDocumentFile = file;
      else if (inputId === 'identity-document-front') identityDocumentFrontFile = file;
      else if (inputId === 'identity-document-back') identityDocumentBackFile = file;
      updateDocumentStatus(inputId, file);
      updateCaptureSummary();
      scheduleDraftSave(0, `${label} salvo neste aparelho.`);
    } catch (error) {
      input.value = '';
      if (inputId === 'vehicle-document') vehicleDocumentFile = null;
      else if (inputId === 'identity-document-front') identityDocumentFrontFile = null;
      else if (inputId === 'identity-document-back') identityDocumentBackFile = null;
      updateDocumentStatus(inputId, null);
      updateCaptureSummary();
      msg(error.message);
    }
  });
}

handleDocumentSelection('vehicle-document', 'o CRLV do veículo');
handleDocumentSelection('identity-document-front', 'a frente do RG ou da CNH do associado');
handleDocumentSelection('identity-document-back', 'o verso do RG ou da CNH do associado');

function fileExtension(file) {
  const match = String(file?.name || '').toLowerCase().match(/\.([a-z0-9]+)$/);
  return match?.[1] || '';
}

const signatureCanvas = $('signature-pad');
const signatureContext = signatureCanvas.getContext('2d');

function initializeSignaturePad() {
  signatureContext.lineCap = 'round';
  signatureContext.lineJoin = 'round';
  signatureContext.lineWidth = 4;
  signatureContext.strokeStyle = '#080f63';
}

function signaturePoint(event) {
  const rect = signatureCanvas.getBoundingClientRect();
  return {
    x: (event.clientX - rect.left) * (signatureCanvas.width / rect.width),
    y: (event.clientY - rect.top) * (signatureCanvas.height / rect.height)
  };
}

function beginSignature(event) {
  if ($('registration-fields').hidden || $('signature-block').hidden) return;
  event.preventDefault();
  signatureDrawing = true;
  signatureLastPoint = signaturePoint(event);
  signatureCanvas.setPointerCapture?.(event.pointerId);
}

function drawSignature(event) {
  if (!signatureDrawing || !signatureLastPoint) return;
  event.preventDefault();
  const point = signaturePoint(event);
  signatureContext.beginPath();
  signatureContext.moveTo(signatureLastPoint.x, signatureLastPoint.y);
  signatureContext.lineTo(point.x, point.y);
  signatureContext.stroke();
  signatureLastPoint = point;
  signatureHasInk = true;
  signatureCanvas.classList.add('has-signature');
  $('signature-status').textContent = 'Assinatura registrada.';
  $('signature-status').classList.add('ok');
  updateCaptureSummary();
}

function endSignature(event) {
  if (!signatureDrawing) return;
  event.preventDefault();
  signatureDrawing = false;
  signatureLastPoint = null;
  if (event?.pointerId !== undefined && signatureCanvas.hasPointerCapture?.(event.pointerId)) {
    signatureCanvas.releasePointerCapture(event.pointerId);
  }
  scheduleDraftSave(300, 'Assinatura salva neste aparelho.');
}

function clearSignature() {
  signatureContext.clearRect(0, 0, signatureCanvas.width, signatureCanvas.height);
  signatureHasInk = false;
  signatureDrawing = false;
  signatureLastPoint = null;
  signatureCanvas.classList.remove('has-signature');
  $('signature-status').textContent = 'Assinatura pendente.';
  $('signature-status').classList.remove('ok');
  updateCaptureSummary();
  scheduleDraftSave(300, 'Rascunho atualizado neste aparelho.');
}

async function exportSignatureBlob() {
  const exportCanvas = document.createElement('canvas');
  exportCanvas.width = signatureCanvas.width;
  exportCanvas.height = signatureCanvas.height;
  const context = exportCanvas.getContext('2d');
  context.fillStyle = '#ffffff';
  context.fillRect(0, 0, exportCanvas.width, exportCanvas.height);
  context.drawImage(signatureCanvas, 0, 0);

  return new Promise((resolve, reject) => {
    exportCanvas.toBlob(
      (blob) => blob ? resolve(blob) : reject(new Error('Não foi possível processar a assinatura.')),
      'image/png'
    );
  });
}

function vehiclePlateLabel(plate) {
  return plate ? `Placa: ${plate}` : 'Veículo 0 km — sem placa';
}

signatureCanvas.addEventListener('pointerdown', beginSignature);
signatureCanvas.addEventListener('pointermove', drawSignature);
signatureCanvas.addEventListener('pointerup', endSignature);
signatureCanvas.addEventListener('pointercancel', endSignature);
signatureCanvas.addEventListener('pointerleave', (event) => {
  if (event.pointerType === 'mouse') endSignature(event);
});
$('clear-signature').addEventListener('click', clearSignature);
window.NHResidenceAddress?.init(() => {
  updateCaptureSummary();
  scheduleDraftSave(700, 'CEP e endereço salvos neste aparelho.');
});
$('residence-address').addEventListener('input', () => {
  updateCaptureSummary();
  scheduleDraftSave(700, 'Endereço salvo neste aparelho.');
});
document.querySelectorAll('input[name="preexisting-damage"]').forEach((input) => {
  input.addEventListener('change', () => {
    syncPreexistingDamageUi();
    scheduleDraftSave(250, 'Situação de avaria pré-existente salva neste aparelho.');
  });
});
$('preexisting-damage-details')?.addEventListener('input', () => {
  updateCaptureSummary();
  scheduleDraftSave(600, 'Descrição das avarias salva neste aparelho.');
});
$('retry-load').addEventListener('click', () => load());
$('confirm-digital-acceptance').addEventListener('click', async () => {
  clearMessage();
  try {
    await performDigitalAcceptance();
  } catch (error) {
    $('digital-acceptance-status').textContent = error?.message || 'Não foi possível concluir o aceite digital.';
    msg(error?.message || 'Não foi possível concluir o aceite digital.');
  }
});
$('discard-draft').addEventListener('click', async () => {
  if (!window.confirm('Apagar as fotos, o vídeo, os documentos, o endereço, as avarias e a assinatura salvos neste aparelho?')) return;
  await removeDraft();
  location.reload();
});
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'hidden') {
    if (request && !$('upload-card').hidden) {
      scheduleDraftSave(0, 'Rascunho atualizado neste aparelho.');
    }

    // Nunca aceitamos continuar uma gravação web com a página oculta. Alguns sistemas
    // mantêm o relógio/áudio ativos e suspendem apenas a câmera, criando vídeo congelado.
    if (mediaRecorder?.state === 'recording' && captureMode === 'video') {
      interruptVideoRecordingAndRequireRestart('A página saiu do primeiro plano e a câmera pode ter sido suspensa. Toque em “Retomar gravação” para reiniciar o vídeo do início.');
    }
    return;
  }

  if (recordingInterrupted && captureMode === 'video') {
    showRecordingInterrupted(recordingInterruptionReason || 'A gravação foi pausada. Toque em “Retomar gravação” para continuar.');
  } else if (mediaRecorder?.state === 'recording') {
    void requestRecordingWakeLock();
  }
});

window.addEventListener('pageshow', () => {
  if (recordingInterrupted && captureMode === 'video') {
    showRecordingInterrupted(recordingInterruptionReason || 'A gravação foi pausada. Toque em “Retomar gravação” para continuar.');
  }
});
window.addEventListener('online', () => {
  if (!$('connection-actions').hidden) load();
});
initializeSignaturePad();

function videoExtension(mimeType) {
  switch (mimeType) {
    case 'video/mp4': return 'mp4';
    case 'video/quicktime': return 'mov';
    case 'video/3gpp': return '3gp';
    default: return 'webm';
  }
}

function slugify(value) {
  return String(value || '')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^a-zA-Z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .toLowerCase() || 'arquivo';
}

function formatBytes(bytes) {
  if (!bytes || bytes <= 0) return '0 KB';
  if (bytes < 1024 * 1024) {
    return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  }
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function escapeHtml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#039;');
}

window.addEventListener('beforeunload', () => {
  void releaseRecordingWakeLock();
  stopCameraStream();
});
load();

$('copy-ios-webauthn-link')?.addEventListener('click', async () => {
  try {
    await navigator.clipboard.writeText(location.href);
    $('digital-acceptance-status').textContent = 'Link copiado. Abra o Safari, cole o endereço e confirme o aceite por lá.';
  } catch (_) {
    window.prompt('Copie este link e abra no Safari:', location.href);
  }
});
