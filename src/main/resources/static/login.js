'use strict';
const alert = document.getElementById('login-notice');
if (new URLSearchParams(location.search).has('error')) { alert.hidden = false; alert.textContent = '帳號或密碼錯誤，請重新輸入。'; }
if (new URLSearchParams(location.search).has('logout')) { alert.hidden = false; alert.textContent = '已安全登出。'; }
fetch('/api/csrf').then(response => { if (!response.ok) throw new Error(); return response.json(); }).then(data => {
  document.getElementById('csrf').value = data.token;
  const button = document.getElementById('login-button'); button.disabled = false; button.textContent = '登入後台 ↗';
}).catch(() => { alert.hidden = false; alert.textContent = '無法取得登入驗證，請重新整理頁面。'; });
