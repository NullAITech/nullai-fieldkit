"""Read-only localhost dashboard. The page holds no data; it fetches /v1/dashboard/data
with a token typed by the user (kept in sessionStorage only). All values are rendered
with textContent (no innerHTML) so audit-log content cannot inject markup."""

DASHBOARD_HTML = r"""<!doctype html><html lang="en"><head><meta charset="utf-8">
<title>zoth-relay dashboard</title><meta name="viewport" content="width=device-width,initial-scale=1">
<style>
:root{--gold:#e8c26a;--bg:#0d0d10;--glass:rgba(255,255,255,.06);--txt:#eee}
body{margin:0;font:14px/1.45 system-ui,sans-serif;background:radial-gradient(circle at 20% 0,#2a2312,var(--bg) 60%);color:var(--txt)}
header{padding:18px 24px;border-bottom:1px solid rgba(232,194,106,.3);display:flex;gap:16px;align-items:center}
h1{margin:0;font-size:20px;color:var(--gold);letter-spacing:.06em}main{padding:20px 24px}
.card{background:var(--glass);border:1px solid rgba(232,194,106,.25);border-radius:14px;padding:14px 18px;margin-bottom:18px;backdrop-filter:blur(8px)}
table{width:100%;border-collapse:collapse}td,th{padding:5px 8px;text-align:left;border-bottom:1px solid rgba(255,255,255,.07);vertical-align:top}
th{color:var(--gold);font-weight:600}input{background:#16161b;color:var(--txt);border:1px solid #444;border-radius:8px;padding:6px 10px;width:340px}
button{background:var(--gold);border:0;border-radius:8px;padding:7px 14px;font-weight:600;cursor:pointer}
.muted{opacity:.6}.bad{color:#ff7b7b}code{font-size:12px}
</style></head><body>
<header><h1>ZOTH-RELAY</h1><span class="muted">read-only · localhost only</span></header>
<main>
<div class="card"><label>Dashboard token (agent with <code>dashboard: true</code>):
<input id="tok" type="password" autocomplete="off"></label> <button id="go">Load</button>
<span id="msg" class="muted"></span></div>
<div class="card"><h3>Agents</h3><table id="agents"><thead><tr><th>name</th><th>profile</th><th>allowed dirs</th><th>rate/min</th><th>state</th></tr></thead><tbody></tbody></table></div>
<div class="card"><h3>Recent audit events</h3><table id="ev"><thead><tr><th>time (local)</th><th>agent</th><th>event</th><th>via</th><th>detail</th></tr></thead><tbody></tbody></table></div>
</main>
<script>
const $=s=>document.querySelector(s);
function row(tb,cells,cls){const tr=document.createElement('tr');if(cls)tr.className=cls;for(const c of cells){const td=document.createElement('td');td.textContent=c==null?'':String(c);tr.appendChild(td)}tb.appendChild(tr)}
async function load(){
  const t=$('#tok').value.trim()||sessionStorage.getItem('zr');if(!t)return;
  const r=await fetch('/v1/dashboard/data',{headers:{Authorization:'Bearer '+t},cache:'no-store'});
  if(!r.ok){$('#msg').textContent='HTTP '+r.status;return}
  sessionStorage.setItem('zr',t);$('#tok').value='';const d=await r.json();
  $('#msg').textContent='v'+d.version+' · up '+Math.round(d.uptime_s)+'s · auto-refresh 10s';
  const a=$('#agents tbody');a.replaceChildren();
  for(const x of d.agents)row(a,[x.name,x.profile,x.allowed_dirs.join(', '),x.rate_limit_per_minute,x.disabled?'revoked':'active'],x.disabled?'bad':'');
  const e=$('#ev tbody');e.replaceChildren();
  for(const x of d.recent){const det=x.command||x.path||x.reason||x.result||'';
    row(e,[new Date(x.ts).toLocaleString(),x.agent||'-',x.event,x.via||'',det],/fail|denied|blocked|forbidden|rate/.test(x.event+(x.result||''))?'bad':'')}
}
$('#go').onclick=load;setInterval(load,10000);load();
</script></body></html>"""
