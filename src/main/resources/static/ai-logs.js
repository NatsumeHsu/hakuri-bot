'use strict';
(() => {
  const output = document.getElementById('ai-log-output');
  const status = document.getElementById('ai-log-status');
  const source = document.getElementById('ai-log-source');
  const pause = document.getElementById('ai-log-pause');
  const autoScroll = document.getElementById('ai-log-scroll');
  const labels = { input: '文字輸入', voice: '語音轉文字', step: '流程' };
  const pageLimit = 2000;
  let cursor = 0, instance = '', rows = [], timeout, paused = false, closed = false, fetching = false, revision = 0;
  function render() {
    const position = output.scrollTop;
    output.textContent = rows.length ? rows.join('\n') : '等待文字或語音輸入…';
    if (autoScroll.checked) output.scrollTop = output.scrollHeight;
    else output.scrollTop = position;
  }
  function schedule(delay) { clearTimeout(timeout); if (!closed && !paused) timeout = setTimeout(poll, delay); }
  async function poll() {
    if (closed || paused || fetching) return;
    fetching = true; const requestedRevision = revision; let delay = 1000;
    try {
      const query = new URLSearchParams({ source: source.value, after: String(cursor), instance });
      const data = await api('/activity?' + query);
      if (closed || paused || requestedRevision !== revision) return;
      if (data.reset || (instance && instance !== data.instanceId)) rows = [];
      if (data.truncated) rows.push('── 較早紀錄已超出暫存範圍，以下顯示目前保留的輸出 ──');
      for (const line of data.entries) {
        const time = new Date(line.receivedAt).toLocaleTimeString('zh-TW', { hour12: false });
        rows.push('[' + time + '] [' + labels[line.source] + '] ' + line.text);
      }
      if (rows.length > pageLimit) rows = rows.slice(-pageLimit);
      if (data.entries.length || data.reset || data.truncated) render();
      cursor = data.cursor; instance = data.instanceId;
      document.getElementById('activity-step').textContent = '目前：' + data.currentStep;
      status.textContent = '即時更新 · 伺服器保留最近 ' + data.capacity + ' 筆 · 本頁 ' + rows.length + ' / ' + pageLimit + ' 筆';
      if (data.hasMore) delay = 0;
    } catch (error) {
      status.textContent = '連線暫停，正在重試：' + error.message; delay = 3000;
    } finally {
      fetching = false;
      schedule(requestedRevision !== revision ? 0 : delay);
    }
  }
  source.addEventListener('change', () => {
    revision++; cursor = 0; rows = []; render();
    if (!fetching) schedule(0);
  });
  pause.addEventListener('click', () => {
    paused = !paused; pause.textContent = paused ? '繼續接收' : '暫停接收';
    pause.setAttribute('aria-pressed', String(paused));
    if (paused) { clearTimeout(timeout); status.textContent = '已暫停瀏覽器接收，伺服器仍持續保存輸出'; }
    else schedule(0);
  });
  document.getElementById('ai-log-clear').addEventListener('click', () => { rows = []; render(); });
  autoScroll.addEventListener('change', () => { if (autoScroll.checked) output.scrollTop = output.scrollHeight; });
  window.addEventListener('pagehide', () => { revision++; closed = true; clearTimeout(timeout); });
  window.addEventListener('pageshow', event => { if (event.persisted) { closed = false; schedule(0); } });
  render(); poll();
})();
