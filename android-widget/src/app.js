'use strict';

// ── Config ────────────────────────────────────────────────────────────────────
const cfg = {
    get ip()   { return localStorage.getItem('server_ip')   || ''; },
    get port() { return localStorage.getItem('server_port') || '37401'; },
    set ip(v)  { localStorage.setItem('server_ip', v); },
    set port(v){ localStorage.setItem('server_port', v); },
    get base() { return this.ip ? `http://${this.ip}:${this.port}` : null; },
};

// ── State ─────────────────────────────────────────────────────────────────────
let allEvents = [];
let journals  = {};
let completedCollapsed = localStorage.getItem('completed_collapsed') !== 'false';
let journalTimer = null;
let refreshTimer = null;

// ── DOM helpers ───────────────────────────────────────────────────────────────
const $ = id => document.getElementById(id);
const DOWS = ['周日','周一','周二','周三','周四','周五','周六'];
function pad2(n) { return n < 10 ? '0' + n : '' + n; }
function fmtTime(d) { return pad2(d.getHours()) + ':' + pad2(d.getMinutes()); }
function fmtDate(d) { return (d.getMonth()+1)+'月'+d.getDate()+'日 · '+DOWS[d.getDay()]; }
function todayKey() {
    const d = new Date();
    return d.getFullYear()+'-'+pad2(d.getMonth()+1)+'-'+pad2(d.getDate());
}
function isSameDay(a,b) {
    return a.getFullYear()===b.getFullYear()&&a.getMonth()===b.getMonth()&&a.getDate()===b.getDate();
}

// ── LAN Scanner ───────────────────────────────────────────────────────────────
async function probeHost(ip, port, timeout) {
    try {
        const res = await fetch(`http://${ip}:${port}/health`, {
            signal: AbortSignal.timeout(timeout),
        });
        if (!res.ok) return null;
        const data = await res.json();
        return { ip, port, name: data.name || ip, events: data.events || 0 };
    } catch (_) { return null; }
}

async function scanSubnet(prefix, port) {
    // Scan 254 hosts in batches of 40 concurrently
    const found = [];
    const BATCH = 40;
    for (let base = 1; base <= 254; base += BATCH) {
        const batch = [];
        for (let i = base; i < base + BATCH && i <= 254; i++) {
            batch.push(probeHost(`${prefix}.${i}`, port, 400));
        }
        const results = await Promise.all(batch);
        results.forEach(r => { if (r) found.push(r); });
        if (found.length > 0) break; // stop at first hit in this subnet
    }
    return found;
}

async function scanLAN(port) {
    // Typical home/office subnets, most common first
    const prefixes = [
        '192.168.0','192.168.1','192.168.2','192.168.3',
        '192.168.4','192.168.5','192.168.10','192.168.100',
        '10.0.0','10.0.1','172.16.0',
    ];
    const all = [];
    for (const prefix of prefixes) {
        const found = await scanSubnet(prefix, port);
        all.push(...found);
        if (all.length >= 5) break; // enough results
    }
    return all;
}

// ── HTTP helpers ──────────────────────────────────────────────────────────────
async function fetchJSON(path) {
    const r = await fetch(cfg.base + path, { signal: AbortSignal.timeout(6000) });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    return r.json();
}
async function putJSON(path, body) {
    await fetch(cfg.base + path, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
        signal: AbortSignal.timeout(6000),
    });
}

// ── Data ──────────────────────────────────────────────────────────────────────
async function loadAll() {
    $('btn-refresh').textContent = '…';
    try {
        const [events, jrnls] = await Promise.all([
            fetchJSON('/tplanner/events'),
            fetchJSON('/tplanner/journals').catch(() => ({})),
        ]);
        allEvents = events
            .filter(e => !e.deletedAt)
            .map(e => ({ ...e, start: new Date(e.start), end: new Date(e.end) }));
        journals = jrnls || {};
        render();
    } catch (err) {
        $('hdr-sub').textContent = '连接失败：' + err.message;
    } finally {
        $('btn-refresh').textContent = '↻';
    }
}

function liveToday() {
    const now = new Date();
    return allEvents.filter(e =>
        isSameDay(e.start,now)||isSameDay(e.end,now)||(e.start<=now&&e.end>=now)
    ).sort((a,b)=>a.start-b.start);
}

function serializeEvent(e) {
    return { ...e,
        start: e.start instanceof Date ? e.start.toISOString() : e.start,
        end:   e.end   instanceof Date ? e.end.toISOString()   : e.end,
    };
}

async function toggleTask(eventId) {
    const ev = allEvents.find(e=>e.id===eventId);
    if (!ev||ev.type!=='task') return;
    const cl = ev.checklist||[];
    if (cl.length>0&&!cl.every(i=>i.completed)&&!ev.completed) return;
    ev.completed=!ev.completed; ev.updatedAt=Date.now();
    render();
    await putJSON('/tplanner/events', allEvents.map(serializeEvent));
}

async function toggleSubtask(eventId, subtaskId) {
    const ev = allEvents.find(e=>e.id===eventId); if(!ev) return;
    const sub = (ev.checklist||[]).find(s=>s.id===subtaskId); if(!sub) return;
    sub.completed=!sub.completed;
    ev.completed=ev.checklist.every(s=>s.completed);
    ev.updatedAt=Date.now();
    render();
    await putJSON('/tplanner/events', allEvents.map(serializeEvent));
}

// ── Render ────────────────────────────────────────────────────────────────────
const COLORS = ['#5B8FCC','#C9A84C','#C0697A','#5B9E72','#8B6BAE','#C87D5A','#4A9DA8','#8A8A8A'];

function render() {
    const now    = new Date();
    const list   = $('list');
    const todays = liveToday();

    $('hdr-date').textContent = fmtDate(now);
    $('hdr-sub').textContent  = todays.length===0?'今日空闲':`今日 ${todays.length} 项`;
    $('journal-input').value  = journals[todayKey()]||'';
    list.innerHTML='';

    if (!cfg.base) { $('no-server').classList.remove('hidden'); return; }
    $('no-server').classList.add('hidden');

    if (todays.length===0) {
        list.innerHTML='<div class="empty"><div class="empty-icon">✨</div><div class="empty-text">今天没有安排，享受清闲吧</div></div>';
        return;
    }

    const nowTs=now.getTime();
    function statusFor(e) {
        if (e.end<now) return 'past';
        if (e.start<=now&&e.end>=now) return 'now';
        if (e.start.getTime()-nowTs<300000) return 'soon';
        return 'future';
    }

    const done=todays.filter(e=>e.type==='task'&&e.completed);
    const active=todays.filter(e=>!(e.type==='task'&&e.completed));
    const current=active.filter(e=>statusFor(e)==='now');
    const upcoming=active.filter(e=>statusFor(e)!=='now'&&statusFor(e)!=='past');
    const past=active.filter(e=>statusFor(e)==='past');

    function sec(label,items) {
        if(!items.length) return;
        const hd=document.createElement('div');
        hd.className='group-label';
        hd.innerHTML=`${label} <span class="count">${items.length}</span>`;
        list.appendChild(hd);
        items.forEach(e=>list.appendChild(renderItem(e,statusFor(e))));
    }
    sec('进行中',current); sec('稍后',upcoming); sec('已过',past);

    if (done.length>0) {
        const hd=document.createElement('div');
        hd.className='group-label clickable';
        hd.innerHTML=`已完成 <span class="count">${done.length}</span> <span style="font-size:9px;margin-left:4px">${completedCollapsed?'▶':'▼'}</span>`;
        const bd=document.createElement('div');
        bd.style.display=completedCollapsed?'none':'';
        done.forEach(e=>bd.appendChild(renderItem(e,statusFor(e))));
        hd.addEventListener('click',()=>{
            completedCollapsed=!completedCollapsed;
            localStorage.setItem('completed_collapsed',completedCollapsed);
            bd.style.display=completedCollapsed?'none':'';
            const sp=hd.querySelector('span:last-child');
            if(sp) sp.textContent=completedCollapsed?'▶':'▼';
        });
        list.appendChild(hd); list.appendChild(bd);
    }
}

function renderItem(e, status) {
    const cl=e.checklist||[];
    const done=cl.filter(i=>i.completed).length;
    const allDone=cl.length>0?done===cl.length:true;
    const color=COLORS[(e.colorId||0)%COLORS.length];

    const div=document.createElement('div');
    div.className='item'+(status==='now'?' now':'')+(status==='past'?' past':'');

    const bullet=document.createElement('span');
    if (e.type==='task') {
        bullet.className='item-bullet'+(e.completed?' done':'');
        const canToggle=allDone||e.completed;
        bullet.style.opacity=(!canToggle&&!e.completed)?'0.4':'1';
        bullet.style.cursor=(!canToggle&&!e.completed)?'not-allowed':'pointer';
        bullet.addEventListener('click',()=>toggleTask(e.id));
    } else {
        bullet.className='item-bullet color-bar';
        bullet.style.background=color;
    }
    div.appendChild(bullet);

    const body=document.createElement('div'); body.className='item-body';
    const r1=document.createElement('div'); r1.className='item-row1';
    const title=document.createElement('span');
    title.className='item-title'+(e.completed?' done':'');
    title.textContent=e.title||'(无标题)';
    r1.appendChild(title);

    if (cl.length>0) {
        const badge=document.createElement('span');
        badge.className='progress-badge'+(allDone?' all-done':'');
        badge.textContent=done+'/'+cl.length;
        badge.style.cursor='pointer';
        badge.addEventListener('click',()=>{
            const sl=div.querySelector('.subtask-list');
            if(sl) sl.style.display=sl.style.display==='none'?'':'none';
        });
        r1.appendChild(badge);
    }
    if(status==='now'){const t=document.createElement('span');t.className='item-tag now-tag';t.textContent='现在';r1.appendChild(t);}
    else if(status==='soon'){const t=document.createElement('span');t.className='item-tag soon-tag';t.textContent='即将';r1.appendChild(t);}
    else if(e.completed){const t=document.createElement('span');t.className='item-tag done-tag';t.textContent='完成';r1.appendChild(t);}
    body.appendChild(r1);

    const r2=document.createElement('div'); r2.className='item-row1';
    const time=document.createElement('span'); time.className='item-time';
    time.textContent=fmtTime(e.start)+' – '+fmtTime(e.end);
    r2.appendChild(time); body.appendChild(r2);

    if (cl.length>0) {
        const sl=document.createElement('div'); sl.className='subtask-list';
        cl.forEach(sub=>{
            const sr=document.createElement('div'); sr.className='subtask-item';
            const sb=document.createElement('span');
            sb.className='subtask-bullet'+(sub.completed?' done':'');
            sb.addEventListener('click',()=>toggleSubtask(e.id,sub.id));
            const st=document.createElement('span');
            st.className='subtask-text'+(sub.completed?' done':'');
            st.textContent=sub.text||'';
            sr.appendChild(sb); sr.appendChild(st); sl.appendChild(sr);
        });
        body.appendChild(sl);
    }
    div.appendChild(body);
    return div;
}

// ── Journal ───────────────────────────────────────────────────────────────────
$('journal-input').addEventListener('input', e => {
    clearTimeout(journalTimer);
    journalTimer=setTimeout(async()=>{
        const key=todayKey(); journals[key]=e.target.value;
        if(cfg.base) try{await putJSON('/tplanner/journals',journals);}catch(_){}
    },800);
});

// ── Settings ──────────────────────────────────────────────────────────────────
function openSettings() {
    $('input-ip').value=$('input-ip').value||cfg.ip;
    $('input-port').value=cfg.port;
    $('scan-status').classList.add('hidden');
    $('scan-results').classList.add('hidden');
    $('settings-overlay').classList.remove('hidden');
}
function closeSettings() { $('settings-overlay').classList.add('hidden'); }

function showScanResult(servers) {
    const box=$('scan-results');
    box.innerHTML='';
    if (!servers.length) {
        box.innerHTML='<div style="padding:12px;color:var(--dim);font-size:12px;text-align:center">未发现服务器</div>';
        box.classList.remove('hidden'); return;
    }
    servers.forEach(s=>{
        const row=document.createElement('div'); row.className='scan-result-item';
        row.innerHTML=`
            <div>
                <div class="scan-result-name">${s.name}</div>
                <div class="scan-result-addr">${s.ip}:${s.port}</div>
            </div>
            <div class="scan-result-count">${s.events} 条</div>`;
        row.addEventListener('click',()=>{
            $('input-ip').value=s.ip;
            $('input-port').value=s.port;
            box.classList.add('hidden');
        });
        box.appendChild(row);
    });
    box.classList.remove('hidden');
}

$('btn-scan').addEventListener('click', async () => {
    const btn=$('btn-scan');
    const status=$('scan-status');
    const port=$('input-port').value||'37401';
    btn.disabled=true; btn.textContent='扫描中…';
    status.textContent='正在扫描局域网，请稍候…';
    status.classList.remove('hidden');
    $('scan-results').classList.add('hidden');
    try {
        const found=await scanLAN(port);
        showScanResult(found);
        status.textContent=found.length?`发现 ${found.length} 台服务器`:'扫描完成';
    } catch(e) {
        status.textContent='扫描出错：'+e.message;
    } finally {
        btn.disabled=false; btn.textContent='🔍 重新扫描';
    }
});

$('btn-settings').addEventListener('click', openSettings);
$('btn-go-settings').addEventListener('click', openSettings);
$('btn-cancel-settings').addEventListener('click', closeSettings);
$('btn-save-settings').addEventListener('click', ()=>{
    const ip=$('input-ip').value.trim();
    const port=$('input-port').value.trim()||'37401';
    if(!ip){alert('请填写 IP 地址');return;}
    cfg.ip=ip; cfg.port=port;
    closeSettings(); loadAll();
});
$('settings-overlay').addEventListener('click',e=>{
    if(e.target===$('settings-overlay')) closeSettings();
});

// ── Refresh ───────────────────────────────────────────────────────────────────
$('btn-refresh').addEventListener('click', loadAll);
setInterval(loadAll, 30000);

// Pull-to-refresh
let ptY=0;
const mainEl=$('list');
mainEl.addEventListener('touchstart',e=>{ptY=e.touches[0].clientY;},{passive:true});
mainEl.addEventListener('touchend',e=>{
    if(mainEl.scrollTop===0&&e.changedTouches[0].clientY-ptY>60) loadAll();
},{passive:true});

// ── Init ──────────────────────────────────────────────────────────────────────
if (!cfg.ip) openSettings();
else loadAll();
