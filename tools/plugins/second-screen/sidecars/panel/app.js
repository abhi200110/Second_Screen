// Frontend logic for Second Screen Companion Extension
const sidecar = window.sidecar;
const $ = (id) => document.getElementById(id);

let toastTimer;
function toast(message, isError = false) {
  const el = $('toast');
  if (!el) return;
  el.textContent = message;
  el.classList.toggle('error', isError);
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    el.hidden = true;
  }, 3000);
}

// Wrapper for sidecar API fetch
async function callApi(path, { method = 'GET', body } = {}) {
  const fetchFn = sidecar?.fetch ? sidecar.fetch.bind(sidecar) : window.fetch;
  const res = await fetchFn(path, {
    method,
    headers: body !== undefined ? { 'Content-Type': 'application/json' } : undefined,
    body: body !== undefined ? JSON.stringify(body) : undefined,
  });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    throw new Error(data.message || data.error || `${res.status} ${res.statusText}`);
  }
  return data;
}

// ---------------------------------------------------------------------------
// Status Refresh
// ---------------------------------------------------------------------------
async function refreshStatus() {
  const badge = $('status-badge');
  const badgeText = $('status-badge-text');

  try {
    const data = await callApi('/api/status');

    if (!data.device) {
      badge.className = 'badge warn';
      badgeText.textContent = 'No Device';
      $('status-model').textContent = 'Disconnected';
      $('status-battery').textContent = '—';
      $('status-ip').textContent = '—';
      $('status-app').textContent = '—';
      return;
    }

    // Device online
    badge.className = data.app.isRunning ? 'badge ok' : 'badge';
    badgeText.textContent = data.app.isRunning ? 'App Running' : 'Device Ready';

    $('status-model').textContent = `${data.model} (${data.device.id})`;
    
    if (data.battery) {
      $('status-battery').textContent = `${data.battery.level}% ${data.battery.charging ? '⚡' : ''}`;
    } else {
      $('status-battery').textContent = 'Unknown';
    }

    $('status-ip').textContent = data.ip ? data.ip : 'Offline';

    if (data.app.isRunning) {
      $('status-app').innerHTML = `<span style="color: #10b981;">Online (PID ${data.app.pid})</span>`;
    } else {
      $('status-app').innerHTML = `<span style="color: #94a3b8;">Stopped</span>`;
    }
  } catch (err) {
    badge.className = 'badge error';
    badgeText.textContent = 'Sidecar Error';
    console.error('Failed to load status:', err);
  }
}

// ---------------------------------------------------------------------------
// Actions Execution
// ---------------------------------------------------------------------------
async function runAction(actionName, btnElement) {
  if (btnElement) btnElement.disabled = true;
  try {
    const res = await callApi('/api/action', {
      method: 'POST',
      body: { action: actionName },
    });
    if (res.success) {
      toast(res.message);
    } else {
      toast(res.message, true);
    }
    setTimeout(refreshStatus, 800);
  } catch (err) {
    toast(`Action failed: ${err.message}`, true);
  } finally {
    if (btnElement) btnElement.disabled = false;
  }
}

// ---------------------------------------------------------------------------
// Screen Snapshot
// ---------------------------------------------------------------------------
function captureSnapshot() {
  const img = $('preview-img');
  const placeholder = $('preview-placeholder');
  const btn = $('btn-snapshot');

  btn.disabled = true;
  btn.textContent = 'Capturing...';

  const newSrc = `/api/screenshot.png?t=${Date.now()}`;
  const preloader = new Image();

  preloader.onload = () => {
    img.src = newSrc;
    img.hidden = false;
    placeholder.hidden = true;
    btn.disabled = false;
    btn.textContent = '📸 Capture';
    toast('Snapshot captured');
  };

  preloader.onerror = () => {
    btn.disabled = false;
    btn.textContent = '📸 Capture';
    toast('Failed to capture screen (device sleeping or offline)', true);
  };

  preloader.src = newSrc;
}

// ---------------------------------------------------------------------------
// Logcat Stream
// ---------------------------------------------------------------------------
async function refreshLogs() {
  const terminal = $('terminal');
  try {
    const res = await callApi('/api/logs?count=80');
    if (res.logs && res.logs.length > 0) {
      terminal.innerHTML = res.logs
        .map((line) => {
          let cls = 'log-line';
          if (line.includes(' E ') || line.includes('Error') || line.includes('Exception')) cls += ' err';
          else if (line.includes(' W ') || line.includes('Warning')) cls += ' warn';
          else if (line.includes(' I ') || line.includes('CONNECTED') || line.includes('Success')) cls += ' info';

          const escaped = line
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;');
          return `<div class="${cls}">${escaped}</div>`;
        })
        .join('');
      terminal.scrollTop = terminal.scrollHeight;
    }
  } catch (err) {
    console.error('Failed to load logs:', err);
  }
}

// ---------------------------------------------------------------------------
// Startup & Interop
// ---------------------------------------------------------------------------
function init() {
  // Action buttons
  $('btn-launch').addEventListener('click', (e) => runAction('launch', e.currentTarget));
  $('btn-restart').addEventListener('click', (e) => runAction('restart', e.currentTarget));
  $('btn-stop').addEventListener('click', (e) => runAction('stop', e.currentTarget));
  $('btn-install').addEventListener('click', (e) => runAction('install-debug', e.currentTarget));
  $('btn-snapshot').addEventListener('click', captureSnapshot);
  $('btn-refresh-logs').addEventListener('click', refreshLogs);
  $('btn-clear-logs').addEventListener('click', async () => {
    await runAction('clear-logcat');
    $('terminal').innerHTML = '<div class="log-line info">Log buffer cleared</div>';
  });

  // Agent Interop
  if (sidecar?.conversationId) {
    $('agent-card').hidden = false;
    $('btn-send-agent').addEventListener('click', async () => {
      const input = $('agent-input');
      const text = input.value.trim();
      if (!text) return;
      try {
        await sidecar.agent.sendMessage(text);
        input.value = '';
        toast('Message sent to Pair Programmer');
      } catch (err) {
        toast(`Could not send: ${err.message}`, true);
      }
    });
  }

  // Periodic polling
  refreshStatus();
  refreshLogs();
  setInterval(refreshStatus, 4000);
  setInterval(refreshLogs, 6000);
}

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', init);
} else {
  init();
}
