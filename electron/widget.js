/* tPlanner Today Widget — vanilla renderer.
 * Receives event lists from the main process via window.widgetAPI
 * (set up by widget-preload.cjs). All persistence and reminder firing
 * happen in main; this file only renders.
 */
(function () {
  'use strict';

  var EVENT_COLORS = [
    '#5B8FCC', '#C9A84C', '#C0697A', '#5B9E72',
    '#8B6BAE', '#C87D5A', '#4A9DA8', '#8A8A8A'
  ];

  var DOWS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

  var state = {
    events: [],
    now: new Date(),
  };

  // ── DOM helpers ────────────────────────────────────────────────────
  function $(id) { return document.getElementById(id); }
  function el(tag, cls) {
    var n = document.createElement(tag);
    if (cls) n.className = cls;
    return n;
  }
  function clear(node) { while (node.firstChild) node.removeChild(node.firstChild); }
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }

  function fmtTime(d) { return pad2(d.getHours()) + ':' + pad2(d.getMinutes()); }
  function fmtDate(d) { return d.getMonth() + 1 + '月' + d.getDate() + '日 · ' + DOWS[d.getDay()]; }

  function isSameDay(a, b) {
    return a.getFullYear() === b.getFullYear()
        && a.getMonth() === b.getMonth()
        && a.getDate() === b.getDate();
  }

  // ── Filtering / sorting ────────────────────────────────────────────
  function eventsForToday() {
    var now = state.now;
    return state.events.filter(function (e) {
      // Includes: events that start today, end today, or span over today
      return isSameDay(e.start, now) || isSameDay(e.end, now)
          || (e.start.getTime() <= now.getTime() && e.end.getTime() >= now.getTime());
    }).sort(function (a, b) { return a.start.getTime() - b.start.getTime(); });
  }

  function statusFor(e, nowTs) {
    var s = e.start.getTime();
    var en = e.end.getTime();
    if (en < nowTs) return 'past';
    if (s <= nowTs && nowTs <= en) return 'now';
    if (s - nowTs <= 5 * 60 * 1000) return 'soon';
    return 'future';
  }

  // ── Render ─────────────────────────────────────────────────────────
  function renderHeader() {
    $('hdr-date').textContent = fmtDate(state.now);
    var todays = eventsForToday();
    $('hdr-sub').textContent = todays.length === 0
      ? '今日空闲'
      : '今日 ' + todays.length + ' 项';
  }

  function renderList() {
    var list = $('list');
    clear(list);
    var todays = eventsForToday();
    if (todays.length === 0) {
      var empty = el('div', 'empty');
      var icon = el('div', 'empty-icon material-symbols-outlined'); icon.textContent = 'event_available';
      var text = el('div', 'empty-text'); text.textContent = '今天没有安排，享受清闲吧';
      empty.appendChild(icon);
      empty.appendChild(text);
      list.appendChild(empty);
      return;
    }

    var nowTs = state.now.getTime();
    var groups = { current: [], upcoming: [], past: [] };
    todays.forEach(function (e) {
      var st = statusFor(e, nowTs);
      if (st === 'past') groups.past.push(e);
      else if (st === 'now') groups.current.push(e);
      else groups.upcoming.push(e);
    });

    var sections = [
      { key: 'current',  label: '进行中', list: groups.current },
      { key: 'upcoming', label: '稍后',  list: groups.upcoming },
      { key: 'past',     label: '已过',  list: groups.past },
    ];

    sections.forEach(function (sec) {
      if (sec.list.length === 0) return;
      var hd = el('div', 'group-label');
      hd.appendChild(document.createTextNode(sec.label));
      var c = el('span', 'count'); c.textContent = sec.list.length;
      hd.appendChild(c);
      list.appendChild(hd);
      sec.list.forEach(function (e) { list.appendChild(renderItem(e, nowTs, sec.key)); });
    });
  }

  function renderItem(e, nowTs, sectionKey) {
    var status = statusFor(e, nowTs);
    var item = el('div', 'item' + (e.type === 'task' ? ' task' : '')
      + (e.type === 'task' && e.completed ? ' done' : '')
      + (status === 'now' ? ' now' : '')
      + (status === 'past' ? ' past' : ''));

    // Bullet — task is a clickable circle, others get a colored slab
    var color = EVENT_COLORS[(e.colorId || 0) % EVENT_COLORS.length];
    if (e.type === 'task') {
      var bullet = el('span', 'item-bullet');
      bullet.addEventListener('click', function (ev) {
        ev.stopPropagation();
        if (window.widgetAPI && window.widgetAPI.toggleTask) {
          window.widgetAPI.toggleTask(e.id);
        }
      });
      item.appendChild(bullet);
    } else {
      var bar = el('span', 'item-bullet color-bar');
      bar.style.background = color;
      item.appendChild(bar);
    }

    var body = el('div', 'item-body');
    var row1 = el('div', 'item-row1');
    var title = el('span', 'item-title');
    title.textContent = e.title || '(无标题)';
    title.title = e.title || '';
    row1.appendChild(title);

    if (status === 'now') {
      var tag = el('span', 'item-tag now'); tag.textContent = '现在';
      row1.appendChild(tag);
    } else if (status === 'soon') {
      var tag2 = el('span', 'item-tag soon'); tag2.textContent = '即将';
      row1.appendChild(tag2);
    } else if (e.type === 'task' && e.completed) {
      var tag3 = el('span', 'item-tag done'); tag3.textContent = '完成';
      row1.appendChild(tag3);
    }
    body.appendChild(row1);

    var row2 = el('div', 'item-row1');
    var timeStr = fmtTime(e.start) + ' – ' + fmtTime(e.end);
    var time = el('span', 'item-time');
    time.textContent = timeStr;
    row2.appendChild(time);
    if (e.note) {
      var note = el('span', 'item-note');
      note.textContent = e.note;
      note.title = e.note;
      row2.appendChild(note);
    }
    body.appendChild(row2);

    item.appendChild(body);
    return item;
  }

  function renderStats() {
    var todays = eventsForToday();
    var taskTotal = todays.filter(function (e) { return e.type === 'task'; }).length;
    var taskDone  = todays.filter(function (e) { return e.type === 'task' && e.completed; }).length;
    var stats = $('stats');
    clear(stats);
    if (taskTotal > 0) {
      var s1 = el('span'); s1.textContent = '任务 ' + taskDone + '/' + taskTotal;
      stats.appendChild(s1);
    } else {
      var s2 = el('span'); s2.textContent = '事件 ' + todays.length;
      stats.appendChild(s2);
    }
    var nowStr = fmtTime(state.now);
    var s3 = el('span'); s3.textContent = '· ' + nowStr;
    stats.appendChild(s3);
  }

  function render() {
    state.now = new Date();
    renderHeader();
    renderList();
    renderStats();
  }

  // ── Event wiring ───────────────────────────────────────────────────
  function setEvents(arr) {
    state.events = (arr || []).map(function (e) {
      return Object.assign({}, e, {
        start: new Date(e.start),
        end:   new Date(e.end),
      });
    });
    render();
  }

  function init() {
    var api = window.widgetAPI;
    if (!api) {
      // Preload not loaded — show a clear error so the user reports it
      var list = $('list');
      clear(list);
      var msg = el('div', 'empty');
      msg.innerHTML = '<div class="empty-icon material-symbols-outlined">warning</div>'
        + '<div class="empty-text">widgetAPI 不可用<br>preload 未注入</div>';
      list.appendChild(msg);
      return;
    }

    // Initial pull from main
    api.getEvents().then(setEvents).catch(function () { setEvents([]); });

    // Live updates from main process
    api.onEvents(setEvents);

    // Always-on-top button reflects state on click
    api.isAlwaysOnTop().then(function (on) {
      $('btn-pin').classList.toggle('active', on);
    });

    $('btn-pin').addEventListener('click', function () {
      api.toggleAlwaysOnTop().then(function (on) {
        $('btn-pin').classList.toggle('active', on);
      });
    });
    $('btn-open').addEventListener('click', api.openMain);
    $('btn-close').addEventListener('click', api.close);
    $('btn-refresh').addEventListener('click', function () {
      api.getEvents().then(setEvents);
    });

    // Re-render every 30s so "current/past/upcoming" stays accurate
    setInterval(render, 30 * 1000);
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
