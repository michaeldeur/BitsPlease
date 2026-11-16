// Login page: password → authenticator code → face scan

const steps = document.getElementById('steps');
const msg = document.getElementById('msg');
const pwForm = document.getElementById('step-password');
const totpForm = document.getElementById('step-totp');
const faceStep = document.getElementById('step-face');
const video = document.getElementById('video');
const overlay = document.getElementById('overlay');
const promptEl = document.getElementById('prompt');
const faceBtn = document.getElementById('face-btn');

if (new URLSearchParams(location.search).get('setup') === 'done') {
  document.getElementById('setup-done').classList.remove('hidden');
}

function show(step) {
  [pwForm, totpForm, faceStep].forEach(el => el.classList.add('hidden'));
  hideMessage(msg);
  if (step === 'password') { pwForm.classList.remove('hidden'); setSteps(steps, 0); }
  if (step === 'totp')     { totpForm.classList.remove('hidden'); setSteps(steps, 1); totpForm.code.focus(); }
  if (step === 'face')     { faceStep.classList.remove('hidden'); setSteps(steps, 2); prepareFace(); }
}

function handleError(err) {
  showMessage(msg, err.message);
  // Server cancelled the login (too many tries / session expired) → back to step 1
  if (err.status === 401 && /log in again|login page/i.test(err.message)) {
    FaceAuth.stopCamera(video);
    setTimeout(() => show('password'), 1800);
  }
}

// ---------- Step 1: password ----------
pwForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  const btn = pwForm.querySelector('button');
  setBusy(btn, true, 'Checking…');
  try {
    const data = await api('/api/auth/login', {
      method: 'POST',
      body: { username: pwForm.username.value.trim(), password: pwForm.password.value },
    });
    pwForm.password.value = '';
    if (data.next.startsWith('/')) return (window.location.href = data.next); // unfinished setup
    show(data.next);
  } catch (err) {
    handleError(err);
  } finally {
    setBusy(btn, false);
  }
});

// ---------- Step 2: TOTP ----------
totpForm.code.addEventListener('input', () => {
  totpForm.code.value = totpForm.code.value.replace(/\D/g, '').slice(0, 6);
  if (totpForm.code.value.length === 6) totpForm.requestSubmit();
});

totpForm.addEventListener('submit', async (e) => {
  e.preventDefault();
  const btn = totpForm.querySelector('button');
  setBusy(btn, true, 'Verifying…');
  try {
    const data = await api('/api/auth/verify-totp', { method: 'POST', body: { code: totpForm.code.value } });
    if (data.next.startsWith('/')) return (window.location.href = data.next);
    show(data.next);
  } catch (err) {
    totpForm.code.value = '';
    handleError(err);
  } finally {
    setBusy(btn, false);
  }
});

// ---------- Step 3: face ----------
async function prepareFace() {
  try {
    promptEl.textContent = 'Loading AI model…';
    await Promise.all([FaceAuth.loadModels(), FaceAuth.startCamera(video)]);
    promptEl.textContent = 'Ready — press "Start face scan"';
    faceBtn.disabled = false;
  } catch (err) {
    promptEl.textContent = 'Camera / model unavailable';
    showMessage(msg, err.message);
  }
}

faceBtn.addEventListener('click', async () => {
  setBusy(faceBtn, true, 'Scanning…');
  hideMessage(msg);
  try {
    const descriptor = await FaceAuth.verifyWithLiveness(video, overlay, t => (promptEl.textContent = t));
    const data = await api('/api/auth/verify-face', { method: 'POST', body: { descriptor } });
    promptEl.textContent = 'Face matched ✔';
    showMessage(msg, 'Verified! Redirecting…', 'success');
    FaceAuth.stopCamera(video);
    setSteps(steps, 3);
    setTimeout(() => (window.location.href = data.next), 700);
  } catch (err) {
    FaceAuth.draw(overlay, video, null);
    promptEl.textContent = 'Try again';
    handleError(err);
    setBusy(faceBtn, false);
  }
});

window.addEventListener('beforeunload', () => FaceAuth.stopCamera(video));
