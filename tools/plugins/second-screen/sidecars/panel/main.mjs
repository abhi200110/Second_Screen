// Backend for Second Screen Companion UI Extension
import { execFile } from 'node:child_process';
import { promisify } from 'node:util';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { platform, release, homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Response, SidecarApp } from 'sidecar_sdk';

const execFileAsync = promisify(execFile);
const HERE = dirname(fileURLToPath(import.meta.url));

const DATA_DIR = process.env.ANTIGRAVITY_EXECUTABLE_DATA_DIR || join(HERE, '.data');
const CONFIG_FILE = join(DATA_DIR, 'config.json');
const STARTED_AT = Date.now();

// Resolve ADB executable path
function getAdbPath() {
  const localAppData = process.env.LOCALAPPDATA || join(homedir(), 'AppData', 'Local');
  const standardAdb = join(localAppData, 'Android', 'Sdk', 'platform-tools', 'adb.exe');
  if (existsSync(standardAdb)) return standardAdb;
  return 'adb';
}

// Execute ADB command with timeout
async function runAdb(args, options = {}) {
  const adb = getAdbPath();
  try {
    const { stdout, stderr } = await execFileAsync(adb, args, {
      timeout: 15000,
      windowsHide: true,
      ...options,
    });
    return {
      success: true,
      stdout: typeof stdout === 'string' ? stdout.trim() : stdout,
      stderr: typeof stderr === 'string' ? stderr.trim() : stderr,
    };
  } catch (err) {
    return {
      success: false,
      error: err.message,
      stdout: typeof err.stdout === 'string' ? err.stdout.trim() : '',
      stderr: typeof err.stderr === 'string' ? err.stderr.trim() : '',
    };
  }
}

const app = new SidecarApp();

// Static file serving
const readLocal = (name) => readFileSync(join(HERE, name), 'utf8');

app.page('/', () => readLocal('index.html'));
app.page('/app.js', () => readLocal('app.js'));
app.api('/styles.css', () => new Response(readLocal('styles.css'), { contentType: 'text/css' }), 'GET');

// GET /api/status: Device, network, app, and system state
app.api('/api/status', async () => {
  const adbPath = getAdbPath();
  const adbExists = existsSync(adbPath) || adbPath === 'adb';

  let device = null;
  let model = 'Unknown';
  let battery = null;
  let ip = null;
  let isAppRunning = false;
  let appPid = null;

  if (adbExists) {
    // Check attached devices
    const devRes = await runAdb(['devices']);
    if (devRes.success) {
      const lines = devRes.stdout.split('\n').filter((l) => l.trim() && !l.includes('List of devices'));
      if (lines.length > 0) {
        const parts = lines[0].trim().split(/\s+/);
        device = { id: parts[0], state: parts[1] || 'device' };
      }
    }

    if (device && device.state === 'device') {
      // Query model
      const modelRes = await runAdb(['shell', 'getprop', 'ro.product.model']);
      if (modelRes.success && modelRes.stdout) {
        model = modelRes.stdout;
      }

      // Query battery
      const batRes = await runAdb(['shell', 'dumpsys', 'battery']);
      if (batRes.success) {
        const levelMatch = batRes.stdout.match(/level:\s*(\d+)/);
        const statusMatch = batRes.stdout.match(/status:\s*(\d+)/);
        if (levelMatch) {
          battery = {
            level: parseInt(levelMatch[1], 10),
            charging: statusMatch ? statusMatch[1] === '2' : false,
          };
        }
      }

      // Query Wi-Fi IP
      const ipRes = await runAdb(['shell', 'ip', '-f', 'inet', 'addr', 'show', 'wlan0']);
      if (ipRes.success) {
        const match = ipRes.stdout.match(/inet\s+([0-9.]+)/);
        if (match) ip = match[1];
      }

      // Check app PID
      const pidRes = await runAdb(['shell', 'pidof', 'com.example.pad2display.debug']);
      if (pidRes.success && pidRes.stdout) {
        isAppRunning = true;
        appPid = pidRes.stdout.split(/\s+/)[0];
      }
    }
  }

  return {
    uptimeSeconds: Math.round((Date.now() - STARTED_AT) / 1000),
    platform: `${platform()} ${release()}`,
    adb: {
      path: adbPath,
      exists: adbExists,
    },
    device,
    model,
    battery,
    ip,
    app: {
      packageName: 'com.example.pad2display.debug',
      isRunning: isAppRunning,
      pid: appPid,
    },
  };
}, 'GET');

// POST /api/action: Execute tablet actions
app.api('/api/action', async (data) => {
  const action = String(data.action || '');

  switch (action) {
    case 'launch': {
      const res = await runAdb([
        'shell',
        'am',
        'start',
        '-n',
        'com.example.pad2display.debug/com.example.pad2display.MainActivity',
      ]);
      return { success: res.success, message: res.success ? 'App launched' : res.error };
    }

    case 'stop': {
      const res = await runAdb(['shell', 'am', 'force-stop', 'com.example.pad2display.debug']);
      return { success: res.success, message: res.success ? 'App stopped' : res.error };
    }

    case 'restart': {
      await runAdb(['shell', 'am', 'force-stop', 'com.example.pad2display.debug']);
      const res = await runAdb([
        'shell',
        'am',
        'start',
        '-n',
        'com.example.pad2display.debug/com.example.pad2display.MainActivity',
      ]);
      return { success: res.success, message: res.success ? 'App restarted' : res.error };
    }

    case 'install-debug': {
      const apkPath = 'D:\\CODE_PLAYGROUND\\SCREEN_MIRROR\\app\\build\\outputs\\apk\\debug\\app-debug.apk';
      if (!existsSync(apkPath)) {
        return { success: false, message: 'Debug APK not found. Build it first with ./gradlew assembleDebug' };
      }
      const res = await runAdb(['install', '-r', apkPath], { timeout: 45000 });
      if (res.success && res.stdout.includes('Success')) {
        await runAdb([
          'shell',
          'am',
          'start',
          '-n',
          'com.example.pad2display.debug/com.example.pad2display.MainActivity',
        ]);
        return { success: true, message: 'Debug APK installed and launched successfully!' };
      }
      return { success: false, message: res.stderr || res.stdout || res.error };
    }

    case 'clear-logcat': {
      await runAdb(['logcat', '-c']);
      return { success: true, message: 'Logcat buffer cleared' };
    }

    default:
      return { success: false, message: `Unknown action: ${action}` };
  }
});

// GET /api/logs: Recent logcat entries filtered for Second Screen
app.api('/api/logs', async (query) => {
  const count = Math.min(Math.max(parseInt(query.count || 60, 10), 10), 200);
  const res = await runAdb(['logcat', '-d', '-t', String(count)]);
  if (!res.success) {
    return { logs: [], error: res.error };
  }

  const keywords = ['pad2display', 'SecondScreenService', 'RtspServer', 'VideoDecoder', 'UibcManager', 'MiceServer', 'TsDemuxer', 'RtpReceiver'];
  const lines = res.stdout.split('\n');
  const filtered = lines.filter((l) => keywords.some((k) => l.includes(k)));

  return { logs: filtered.length > 0 ? filtered : lines.slice(-40) };
}, 'GET');

// GET /api/screenshot.png: Live screen capture served directly as binary PNG
app.api('/api/screenshot.png', async () => {
  const res = await runAdb(['exec-out', 'screencap', '-p'], {
    encoding: 'buffer',
    maxBuffer: 25 * 1024 * 1024,
    timeout: 10000,
  });

  if (res.success && Buffer.isBuffer(res.stdout) && res.stdout.length > 100) {
    return new Response(res.stdout, { contentType: 'image/png' });
  }

  // Fallback 1x1 transparent PNG if screenshot failed
  const emptyPng = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==',
    'base64'
  );
  return new Response(emptyPng, { contentType: 'image/png', status: 503 });
}, 'GET');

app.run();
