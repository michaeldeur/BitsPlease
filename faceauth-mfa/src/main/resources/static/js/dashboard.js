// Dashboard: shows profile + login history pulled from the Java API as JSON.

const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const badge = (ok, yes, no) => `<span class="badge ${ok ? 'ok' : 'bad'}">${ok ? yes : no}</span>`;

async function load() {
  try {
    const [me, logins] = await Promise.all([api('/api/me'), api('/api/me/logins')]);

    document.getElementById('greeting').textContent = `Welcome, ${me.username}`;
    document.getElementById('who').textContent = me.username;
    document.getElementById('email').textContent = me.email;
    document.getElementById('since').textContent = me.memberSince ? new Date(me.memberSince).toLocaleDateString() : '—';
    document.getElementById('totp-status').innerHTML = badge(me.totpEnabled, 'Linked', 'Not linked');
    document.getElementById('face-status').innerHTML =
      badge(me.faceEnrolled, `Enrolled · ${me.faceSamples} samples`, 'Not enrolled');

    document.getElementById('history').innerHTML = logins.length
      ? logins.map(r => `
          <tr>
            <td>${esc(new Date(r.time).toLocaleString())}</td>
            <td>${esc(r.event)}</td>
            <td>${badge(r.success, 'Success', 'Failed')}</td>
            <td class="muted">${esc(r.detail || '')}</td>
            <td class="muted">${esc(r.ip)}</td>
          </tr>`).join('')
      : '<tr><td colspan="5" class="muted">No activity yet.</td></tr>';
  } catch (err) {
    if (err.status === 401) window.location.href = '/login.html';
  }
}

document.getElementById('logout').addEventListener('click', async () => {
  await api('/api/auth/logout', { method: 'POST' }).catch(() => {});
  window.location.href = '/';
});

load();
