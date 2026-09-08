import { applyCategory, initializeWindowControls } from './widget-shared.mjs';

/* tPlanner Today Widget — vanilla renderer.
 * Receives event lists from the main process via window.widgetAPI
 * (set up by widget-preload.cjs). All persistence and reminder firing
 * happen in main; this file only renders.
 */
(function () {
  'use strict';

  var DOWS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

  var state = {
    events: [],
    now: new Date(),
  };

  // Persist completed-section collapsed state across sessions
  var completedCollapsed = localStorage.getItem('widget_completed_collapsed') !== 'false';
  function saveCollapsed(v) {
    completedCollapsed = v;
    localStorage.setItem('widget_completed_collapsed', v ? 'true' : 'false');
  }

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
      var icon = el('div', 'empty-icon'); icon.textContent = '✓';
      icon.setAttribute('aria-hidden', 'true');
      var text = el('div', 'empty-text'); text.textContent = '今天没有安排，享受清闲吧';
      empty.appendChild(icon);
      empty.appendChild(text);
      list.appendChild(empty);
      return;
    }

    var nowTs = state.now.getTime();
    var groups = { current: [], upcoming: [], past: [], done: [] };
    todays.forEach(function (e) {
      // Completed tasks go to dedicated "done" group
      if (e.type === 'task' && e.completed) { groups.done.push(e); return; }
      var st = statusFor(e, nowTs);
      if (st === 'past') groups.past.push(e);
      else if (st === 'now') groups.current.push(e);
      else groups.upcoming.push(e);
    });

    var sections = [
      { key: 'current',  label: '进行中', list: groups.current },
      { key: 'upcoming', label: '稍后',   list: groups.upcoming },
      { key: 'past',     label: '已过',   list: groups.past },
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

    // ── Completed section (collapsible) ──────────────────────────────────
    if (groups.done.length > 0) {
      var doneHd = el('button', 'group-label group-toggle');
      doneHd.type = 'button';
      doneHd.setAttribute('aria-expanded', String(!completedCollapsed));
      doneHd.setAttribute('aria-controls', 'completed-items');

      var doneLabel = document.createTextNode('已完成 ');
      doneHd.appendChild(doneLabel);
      var doneCount = el('span', 'count'); doneCount.textContent = groups.done.length;
      doneHd.appendChild(doneCount);

      var arrow = el('span');
      arrow.setAttribute('aria-hidden', 'true');
      arrow.textContent = completedCollapsed ? '▶' : '▼';
      doneHd.appendChild(arrow);

      var doneBody = el('div');
      doneBody.id = 'completed-items';
      doneBody.style.display = completedCollapsed ? 'none' : '';
      groups.done.forEach(function (e) { doneBody.appendChild(renderItem(e, nowTs, 'done')); });

      doneHd.addEventListener('click', function () {
        saveCollapsed(!completedCollapsed);
        doneHd.setAttribute('aria-expanded', String(!completedCollapsed));
        doneBody.style.display = completedCollapsed ? 'none' : '';
        arrow.textContent = completedCollapsed ? '▶' : '▼';
      });

      list.appendChild(doneHd);
      list.appendChild(doneBody);
    }
  }

  function renderItem(e, nowTs, sectionKey) {
    var status = statusFor(e, nowTs);
    var checklist = e.checklist || [];
    var hasChecklist = checklist.length > 0;
    var doneCount = checklist.filter(function(i) { return i.completed; }).length;
    var allDone = hasChecklist ? doneCount === checklist.length : true;

    var completed = e.type === 'task' && e.completed;
    var item = el('div', 'item' + (e.type === 'task' ? ' task' : '')
      + (completed ? ' done' : '')
      + (!completed && status === 'now' ? ' now' : '')
      + (!completed && status === 'past' ? ' past' : ''));
    applyCategory(item, e.colorId);
    if (e.type === 'task') {
      var bullet = el('button', 'task-check');
      bullet.type = 'button';
      bullet.setAttribute('role', 'checkbox');
      bullet.setAttribute('aria-checked', String(Boolean(e.completed)));
      bullet.setAttribute('aria-label', (e.completed ? '取消完成：' : '完成：') + (e.title || '无标题任务'));
      var check = el('span', 'check-mark');
      check.setAttribute('aria-hidden', 'true');
      check.textContent = e.completed ? '✓' : '';
      bullet.appendChild(check);
      // Block main toggle if subtasks not all done
      bullet.title = hasChecklist && !allDone ? '请先完成所有子任务' : '';
      bullet.disabled = hasChecklist && !allDone && !e.completed;
      if (bullet.disabled) bullet.setAttribute('aria-label', '请先完成所有子任务：' + (e.title || '无标题任务'));
      bullet.addEventListener('click', function (ev) {
        ev.stopPropagation();
        if (hasChecklist && !allDone && !e.completed) return;
        if (window.widgetAPI && window.widgetAPI.toggleTask) {
          window.widgetAPI.toggleTask(e.id);
        }
      });
      item.appendChild(bullet);
    } else {
      var bar = el('span', 'item-bullet color-bar');
      bar.setAttribute('aria-hidden', 'true');
      item.appendChild(bar);
    }

    var body = el('div', 'item-body');
    var row1 = el('div', 'item-row1');
    var title = el('span', 'item-title');
    title.textContent = e.title || '(无标题)';
    title.title = e.title || '';
    row1.appendChild(title);

    // Subtask progress badge
    if (hasChecklist) {
      var badge = el('button', 'progress-badge' + (allDone ? ' done-all' : ''));
      badge.type = 'button';
      badge.setAttribute('aria-expanded', 'true');
      badge.setAttribute('aria-label', '子任务：已完成 ' + doneCount + ' 项，共 ' + checklist.length + ' 项；收起或展开');
      badge.textContent = doneCount + '/' + checklist.length;
      row1.appendChild(badge);
    }

    if (completed) {
      var tag3 = el('span', 'item-tag done'); tag3.textContent = '完成';
      row1.appendChild(tag3);
    } else if (status === 'now') {
      var tag = el('span', 'item-tag now'); tag.textContent = '现在';
      row1.appendChild(tag);
    } else if (status === 'soon') {
      var tag2 = el('span', 'item-tag soon'); tag2.textContent = '即将';
      row1.appendChild(tag2);
    } else if (status === 'past') {
      var pastTag = el('span', 'item-tag past'); pastTag.textContent = '已过';
      row1.appendChild(pastTag);
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

    // Subtask list (collapsible)
    if (hasChecklist) {
      var subtaskOpen = true; // default expanded
      var subtaskList = el('div', 'subtask-list');

      function renderSubtasks() {
        clear(subtaskList);
        checklist.forEach(function(sub) {
          var subId = sub.id;
          var row = el('button', 'subtask-item');
          row.type = 'button';
          row.setAttribute('role', 'checkbox');
          row.setAttribute('aria-checked', String(Boolean(sub.completed)));

          var sbullet = el('span', 'check-mark');
          sbullet.setAttribute('aria-hidden', 'true');
          sbullet.textContent = sub.completed ? '✓' : '';
          row.addEventListener('click', function(ev) {
            ev.stopPropagation();
            if (window.widgetAPI && window.widgetAPI.toggleSubtask) {
              window.widgetAPI.toggleSubtask(e.id, subId);
            }
          });
          row.appendChild(sbullet);

          var stext = el('span', 'subtask-text' + (sub.completed ? ' done' : ''));
          stext.textContent = sub.text || '';
          row.appendChild(stext);
          subtaskList.appendChild(row);
        });
      }
      renderSubtasks();

      // Toggle expand/collapse via clicking the progress badge
      if (badge) {
        badge.addEventListener('click', function(ev) {
          ev.stopPropagation();
          subtaskOpen = !subtaskOpen;
          badge.setAttribute('aria-expanded', String(subtaskOpen));
          subtaskList.style.display = subtaskOpen ? '' : 'none';
        });
      }

      body.appendChild(subtaskList);
    }

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
      var msg = el('div', 'empty error');
      msg.setAttribute('role', 'alert');
      msg.innerHTML = '<div class="empty-icon" aria-hidden="true">!</div>'
        + '<div class="empty-text">暂时无法加载今日安排，请重新打开便签。</div>';
      list.appendChild(msg);
      return;
    }

    // Initial pull from main
    api.getEvents().then(setEvents).catch(function () { setEvents([]); });

    // Live updates from main process
    api.onEvents(setEvents);

    initializeWindowControls(api);
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
