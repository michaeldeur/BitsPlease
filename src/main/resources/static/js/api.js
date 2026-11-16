// Shared helper: send/receive JSON to the Java backend.
// Usage:  const data = await api('/api/auth/login', { method: 'POST', body: { username, password } });

async function api(path, { method = 'GET', body } = {}) {
  const res = await fetch(path, {
    method,
    credentials: 'same-origin',
    headers: body ? { 'Content-Type': 'application/json' } : {},
    body: body ? JSON.stringify(body) : undefined,
  });
  let data = {};
  try { data = await res.json(); } catch { /* empty body */ }
  if (!res.ok) {
    const err = new Error(data.error || `Request failed (${res.status})`);
    err.status = res.status;
    throw err;
  }
  return data;
}

function showMessage(el, text, type = 'error') {
  el.textContent = text;
  el.className = `msg ${type}`;
  el.classList.remove('hidden');
}

function hideMessage(el) {
  el.classList.add('hidden');
}

function setBusy(button, busy, label) {
  if (busy) {
    button.dataset.label = button.innerHTML;
    button.innerHTML = `<span class="spinner"></span> ${label || 'Working…'}`;
    button.disabled = true;
  } else {
    button.innerHTML = button.dataset.label || button.innerHTML;
    button.disabled = false;
  }
}

function setSteps(container, activeIndex) {
  [...container.querySelectorAll('.step')].forEach((s, i) => {
    s.classList.toggle('done', i < activeIndex);
    s.classList.toggle('active', i === activeIndex);
  });
}
