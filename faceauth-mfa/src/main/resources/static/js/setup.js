// Setup page: link authenticator app, then enroll face.

const steps = document.getElementById('steps');
const msg = document.getElementById('msg');
const totpSection = document.getElementById('totp-section');
const faceSection = document.getElementById('face-section');
const totpForm = document.getElementById('totp-form');
const video = document.getElementById('video');
const overlay = document.getElementById('overlay');
const promptEl = document.getElementById('prompt');
const enrollBtn = document.getElementById('enroll-btn');
const sampleDots = [...document.querySelectorAll('#samples span')];

let isDashboardFlow = false;

async function init() {
  try {
    const status = await api('/api/setup/status');
    isDashboardFlow = status.stage === 'AUTHENTICATED';
    const wantsFace = new URLSearchParams(location.search).get('redo') === 'face';
    if (!status.totpEnabled) return startTotp();
    if (!status.faceEnrolled || (isDashboardFlow && wantsFace)) return startFace();
    if (isDashboardFlow && new URLSearchParams(location.search).get('redo') === 'totp') return startTotp();
    window.location.href = isDashboardFlow ? '/dashboard.html' : '/login.html';
  } catch (err) {
    showMessage(msg, err.message);
  }
}

// ---------- A: authenticator ----------
async function startTotp() {
  setSteps(steps, 1);
  totpSection.classList.remove('hidden');
  const data = await api('/api/setup/totp/start', { method: 'POST' });
  document.getElementById('qr').src = data.qrDataUrl;
  document.getElementById('secret').textContent = data.secret.match(/.{1,4}/g).join(' ');
  totpForm.code.focus();
}

totpForm.code.addEventListener('input', () => {
  totpForm.code.value = totpForm.code.value.replace(/\D/g, '').slice(0, 6);
});

totpForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  const btn = totpForm.querySelector('button');
  setBusy(btn, true, 'Verifying…');
  try {
    const data = await api('/api/setup/totp/confirm', { method: 'POST', body: { code: totpForm.code.value } });
    if (data.next) return (window.location.href = data.next);
    totpSection.classList.add('hidden');
    hideMessage(msg);
    startFace();
  } catch (err) {
    showMessage(msg, err.message);
    totpForm.code.value = '';
  } finally {
    setBusy(btn, false);
  }
});

// ---------- B: face ----------
async function startFace() {
  setSteps(steps, 2);
  faceSection.classList.remove('hidden');
  try {
    await Promise.all([FaceAuth.loadModels(), FaceAuth.startCamera(video)]);
    promptEl.textContent = 'Ready — press "Start capture"';
    enrollBtn.disabled = false;
  } catch (err) {
    promptEl.textContent = 'Camera / model unavailable';
    showMessage(msg, err.message);
  }
}

enrollBtn.addEventListener('click', async () => {
  setBusy(enrollBtn, true, 'Capturing…');
  hideMessage(msg);
  sampleDots.forEach(d => d.classList.remove('filled'));
  try {
    const descriptors = await FaceAuth.enroll(video, overlay, t => (promptEl.textContent = t),
      n => sampleDots[n - 1]?.classList.add('filled'), sampleDots.length);
    const data = await api('/api/setup/face/enroll', { method: 'POST', body: { descriptors } });
    FaceAuth.stopCamera(video);
    setSteps(steps, 3);
    showMessage(msg, 'Face registered! Redirecting…', 'success');
    setTimeout(() => (window.location.href = data.next || '/login.html?setup=done'), 900);
  } catch (err) {
    showMessage(msg, err.message);
    setBusy(enrollBtn, false);
  }
});

document.getElementById('logout').addEventListener('click', async (e) => {
  e.preventDefault();
  FaceAuth.stopCamera(video);
  if (isDashboardFlow) return (window.location.href = '/dashboard.html');
  await api('/api/auth/logout', { method: 'POST' }).catch(() => {});
  window.location.href = '/';
});

init();
