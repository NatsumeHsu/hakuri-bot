'use strict';
(() => {
  const output = document.getElementById('ai-reply-text');
  const state = document.getElementById('ai-reply-state');
  const labels = { WAITING: '等待回覆', THINKING: 'AI 思考中', STREAMING: '正在回覆', COMPLETE: '回覆完成', INTERRUPTED: '已中斷', ERROR: '回覆失敗' };
  let version = '', timeout, closed = false, generation = 0;
  async function update() {
    if (closed) return;
    const requestGeneration = generation;
    try {
      const reply = await api('/ai/reply');
      if (closed || requestGeneration !== generation) return;
      const nextVersion = reply.instance + ':' + reply.revision;
      if (version !== nextVersion) {
        version = nextVersion;
        output.textContent = subtitleText(reply.text) || (reply.state === 'THINKING' ? '正在等待 AI 輸出…' : 'AI 回覆會自動顯示於此。');
        output.scrollTop = output.scrollHeight;
      }
      state.textContent = (labels[reply.state] || reply.state) + (reply.truncated ? ' · 文字已達顯示上限' : '');
    } catch (error) { state.textContent = '更新失敗，正在重試'; }
    finally { if (!closed && requestGeneration === generation) timeout = setTimeout(update, 500); }
  }
  window.addEventListener('pagehide', () => { generation++; closed = true; clearTimeout(timeout); });
  window.addEventListener('pageshow', event => { if (event.persisted) { closed = false; update(); } });
  update();
})();
