(function () {
  'use strict';

  function todayKey() {
    var d = new Date();
    return d.getFullYear() + '-'
      + pad2(d.getMonth() + 1) + '-'
      + pad2(d.getDate());
  }
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
  function $(id) { return document.getElementById(id); }

  function showSaved() {
    var el = $('save-indicator');
    el.classList.add('visible');
    clearTimeout(el._t);
    el._t = setTimeout(function () { el.classList.remove('visible'); }, 1200);
  }

  function init() {
    var api = window.notesAPI;
    if (!api) {
      $('notes-input').placeholder = 'notesAPI 不可用 — preload 未注入';
      return;
    }

    var input = $('notes-input');
    var saveTimer = null;

    // Load today's journal text
    api.getJournals().then(function (journals) {
      input.value = (journals || {})[todayKey()] || '';
    });

    // Live sync from other windows (main app, task widget)
    api.onJournalUpdated(function (date, text) {
      if (date === todayKey()) input.value = text || '';
    });

    // Debounced save on input
    input.addEventListener('input', function () {
      clearTimeout(saveTimer);
      saveTimer = setTimeout(function () {
        api.saveJournal(todayKey(), input.value);
        showSaved();
      }, 500);
    });

    // Always-on-top pin button
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
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
