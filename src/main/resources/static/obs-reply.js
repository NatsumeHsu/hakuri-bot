'use strict';
(() => {
  const output = document.getElementById('reply');
  let revision = '', timeout, closed = false, generation = 0;
  async function update() {
    if (closed) return;
    const requested = generation;
    try {
      const styleResponse = await fetch('/api/ai/reply/style', { cache: 'no-store', credentials: 'omit' });
      if (styleResponse.ok) {
        const style = await styleResponse.json();
        if (closed || requested !== generation) return;
        output.style.fontFamily = '"' + style.OBS_FONT_FAMILY + '", sans-serif';
        output.style.fontSize = style.OBS_FONT_SIZE + 'px';
        output.style.color = style.OBS_FONT_COLOR;
      }
      const response = await fetch('/api/ai/reply', { cache: 'no-store', credentials: 'omit' });
      if (!response.ok) throw new Error('Reply unavailable');
      const reply = await response.json();
      if (closed || requested !== generation) return;
      const next = reply.instance + ':' + reply.revision;
      if (next !== revision) { output.textContent = subtitleText(reply.text); output.scrollTop = output.scrollHeight; revision = next; }
    } catch {
      // OBS should show subtitles only; retry without rendering network errors.
    } finally {
      if (!closed && requested === generation) timeout = setTimeout(update, 500);
    }
  }
  window.addEventListener('pagehide', () => { closed = true; generation++; clearTimeout(timeout); });
  window.addEventListener('pageshow', event => { if (event.persisted) { closed = false; update(); } });
  update();
})();
