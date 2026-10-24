'use strict';
let csrf, currentStatus, timer, busy = false;
const $ = id => document.getElementById(id);
function notice(text, error = false) { $('notice').textContent = text; $('notice').className = error ? 'error' : ''; $('notice').hidden = false; }
async function api(path, method = 'GET', body) {
  const headers = {};
  if (method !== 'GET') { if (!csrf) csrf = await api('/csrf'); headers[csrf.header] = csrf.token; }
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const response = await fetch('/api' + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  if (response.status === 401 || response.redirected) { location.href = '/login.html'; throw new Error('請重新登入'); }
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(data.message || data.detail || (response.status === 403 ? '驗證已過期，請重新整理頁面' : '操作失敗（' + response.status + '）'));
  return data;
}
async function refresh() {
  if (busy) return; busy = true;
  try {
    const data = await api('/status'); currentStatus = data;
    const d = data.discord, c = data.conversation;
    const twitchStates = { STOPPED: '尚未啟動', DISABLED: '已停用', CONNECTING: '連線中', AUTHENTICATING: '登入中', JOINING: '加入頻道中', JOINED: '已加入聊天室', RECONNECTING: '重新連線中' };
    $('twitch-state').textContent = '#' + data.twitch.channel + ' · ' + (twitchStates[data.twitch.state] || data.twitch.state);
    $('twitch-counts').textContent = '收到 ' + data.twitch.received + ' 則 · 排入 AI ' + data.twitch.queued + ' 則' + (data.twitch.lastError ? ' · ' + data.twitch.lastError : '');
    const launcher = data.aivt;
    const launchButton = $('start-aivt');
    launchButton.disabled = !launcher.supported || launcher.running;
    $('aivt-state').textContent = !launcher.supported ? '僅支援 Windows' : launcher.detail;
    $('aivt-processes').replaceChildren();
    for (const process of launcher.processes) {
      const badge = document.createElement('span'); badge.className = 'tag';
      badge.textContent = process.name + ' · PID ' + process.pid + ' · ' + (process.running ? '執行中' : '已結束');
      $('aivt-processes').append(badge);
    }
    $('discord-state').textContent = d.connected ? '已連線' : d.enabled ? '連線中斷' : '已停用'; $('bot-name').textContent = d.name;
    $('proxy-state').textContent = c.openllmEnabled ? (c.proxyConnected ? '已連線' : '未連線') : '已停用';
    $('conversation-state').textContent = c.interruptPending ? '等待中斷確認' : c.conversationActive ? '正在對話' : '等待新對話';
    $('voice-state').textContent = c.voiceConnected ? '已加入' : '尚未加入'; $('play-state').textContent = c.playing ? '正在播放回覆' : '目前沒有播放音訊';
    $('asr-state').textContent = d.pendingAsr + ' / 3'; $('recordings-state').textContent = d.recordings + ' 位使用者錄音中';
    $('queue-count').textContent = c.inputCount + ' 則文字 · ' + c.audioCount + ' 段音訊';
    $('queue').replaceChildren();
    for (const text of c.queue) { const li = document.createElement('li'); li.textContent = text; $('queue').append(li); }
    if (!c.queue.length) { const li = document.createElement('li'); li.className = 'empty'; li.textContent = '佇列已清空，等待下一段對話。'; $('queue').append(li); }
    $('updated').textContent = '更新於 ' + new Date(data.time).toLocaleTimeString('zh-TW'); $('sync-dot').style.background = '';
    $('message-form').querySelector('button').disabled = !c.openllmEnabled;
  } catch (error) { $('updated').textContent = '更新失敗'; $('sync-dot').style.background = '#bd6b54'; notice(error.message, true); }
  finally { busy = false; }
}
const groups = [
  ['OBS 字幕', [['OBS_FONT_FAMILY','字型名稱','text'],['OBS_FONT_SIZE','字體大小（16–160）','number'],['OBS_FONT_COLOR','文字顏色','color']]],
  ['服務連線', [['BOT_ENABLED','啟用 Discord Bot','boolean'],['DISCORD_VOICE_ENABLED','啟用 Discord 語音','boolean'],['OPENLLM_ENABLED','啟用 Open-LLM','boolean'],['TWITCH_ENABLED','啟用 Twitch','boolean'],['OPENLLM_HTTP','Open-LLM HTTP 網址','text'],['OPENLLM_WS','Proxy WebSocket 網址','text'],['TWITCH_CHANNEL','Twitch 頻道','text'],['FFMPEG_PATH','FFmpeg 執行檔路徑','text']]],
  ['語音與辨識', [['VOICE_SILENCE_MS','靜音分段時間（毫秒）','number'],['VOICE_MIN_SECONDS','最短錄音（秒）','number'],['VOICE_MAX_SECONDS','最長錄音（秒）','number'],['VOICE_MAX_QUEUE','佇列上限','number'],['VOICE_COOLDOWN_MS','錄音冷卻時間（毫秒）','number'],['WAKE_WORD','喚醒詞','text'],['REQUIRE_WAKE_WORD','語音需要喚醒詞','boolean']]]
];
async function loadSettings() {
  const data = await api('/settings'); $('settings-fields').replaceChildren();
  for (const [title, fields] of groups) {
    const group = document.createElement('fieldset'); group.className = 'field-group';
    const legend = document.createElement('legend'); legend.textContent = title; group.append(legend);
    const grid = document.createElement('div'); grid.className = 'field-grid'; group.append(grid);
    for (const [key, title, type] of fields) {
      const field = document.createElement('div'); field.className = 'field' + (type === 'boolean' ? ' toggle' : '');
      const label = document.createElement('label'); label.htmlFor = key; label.textContent = title;
      const input = document.createElement('input'); input.id = input.name = key; input.type = type === 'boolean' ? 'checkbox' : type;
      if (type === 'boolean') input.checked = data.saved[key] === 'true'; else { input.value = data.saved[key]; input.required = true; }
      if (type === 'number') { input.min = key === 'VOICE_MIN_SECONDS' || key === 'VOICE_COOLDOWN_MS' ? '0' : '1'; input.step = key.endsWith('SECONDS') ? '0.1' : '1'; }
      if (key === 'OBS_FONT_SIZE') { input.min = '16'; input.max = '160'; input.step = '1'; }
      if (['FFMPEG_PATH','TWITCH_CHANNEL','WAKE_WORD'].includes(key)) { input.readOnly = true; input.title = '請在 .env 修改此設定'; label.textContent += '（.env）'; }
      field.append(label, input); grid.append(field);
    }
    $('settings-fields').append(group);
  }
  showSettingsState(data);
}
function showSettingsState(data) {
  $('settings-status').textContent = data.restartRequired ? '● 已儲存，等待套用' : '目前設定已套用';
  $('environment-note').textContent = '設定儲存於 SQLite · .env 欄位需在本機檔案修改';
}
$('refresh').addEventListener('click', refresh);
$('message-form').addEventListener('submit', async event => {
  event.preventDefault(); const button = event.currentTarget.querySelector('button'); button.disabled = true;
  try { notice((await api('/messages', 'POST', { text: $('message').value })).message); $('message').value = ''; await refresh(); }
  catch (error) { notice(error.message, true); } finally { button.disabled = currentStatus ? !currentStatus.conversation.openllmEnabled : false; }
});
document.querySelectorAll('[data-action]').forEach(button => button.addEventListener('click', async () => {
  const action = button.dataset.action;
  if (action !== 'start-aivt' && !confirm('確定要' + button.textContent + '？')) return;
  button.disabled = true;
  try { notice((await api('/actions/' + action, 'POST')).message); await refresh(); if (action === 'start-aivt') await loadSettings(); } catch (error) { notice(error.message, true); }
  finally { button.disabled = action === 'start-aivt' && currentStatus ? currentStatus.aivt.running : false; }
}));
$('settings-form').addEventListener('submit', async event => {
  event.preventDefault(); const button = event.currentTarget.querySelector('button'); button.disabled = true;
  const values = {}; event.currentTarget.querySelectorAll('input:not([readonly]):not(:disabled)').forEach(input => { values[input.name] = input.type === 'checkbox' ? String(input.checked) : input.value.trim(); });
  try { const data = await api('/settings', 'PUT', values); showSettingsState(data); notice('設定已儲存。OBS 字幕樣式即時套用；Open-LLM／Discord 語音會在 AI 服務就緒後套用，其餘設定請重新啟動 Bot。'); }
  catch (error) { notice(error.message, true); } finally { button.disabled = false; }
});
$('logout').addEventListener('click', async () => { try { if (!csrf) csrf = await api('/csrf'); await fetch('/logout', { method: 'POST', headers: { [csrf.header]: csrf.token } }); location.href = '/login.html'; } catch (error) { notice(error.message, true); } });
document.querySelectorAll('nav a').forEach(link => link.addEventListener('click', () => { document.querySelectorAll('nav a').forEach(item => item.classList.remove('active')); link.classList.add('active'); }));
loadSettings().catch(error => notice(error.message, true)); refresh(); timer = setInterval(refresh, 5000);
window.addEventListener('pagehide', () => clearInterval(timer));
document.getElementById('password-form').addEventListener('submit', async event => {
  event.preventDefault(); const button = event.currentTarget.querySelector('button'); button.disabled = true;
  try { notice((await api('/password', 'PUT', { password: $('new-password').value })).message); event.target.reset(); }
  catch (error) { notice(error.message, true); } finally { button.disabled = false; }
});
