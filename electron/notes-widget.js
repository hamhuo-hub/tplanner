(function () {
  'use strict';

  function todayKey() {
    var d = new Date();
    return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
  }
  function pad2(n) { return n < 10 ? '0' + n : '' + n; }
  function $(id) { return document.getElementById(id); }

  function showSaved() {
    var el = $('save-indicator');
    el.classList.add('visible');
    clearTimeout(el._t);
    el._t = setTimeout(function () { el.classList.remove('visible'); }, 1200);
  }

  function renderMd(text) {
    if (!text || !text.trim()) return '';
    return marked.parse(text, { async: false, breaks: true });
  }

  function showRendered(el, rawText) {
    el.classList.add('rendered');
    el.innerHTML = renderMd(rawText);
    $('mode-hint').textContent = '点击编辑';
  }

  function showRaw(el, rawText) {
    el.classList.remove('rendered');
    el.textContent = rawText;
    $('mode-hint').textContent = 'Markdown';
    // Move cursor to end
    var range = document.createRange();
    var sel = window.getSelection();
    range.selectNodeContents(el);
    range.collapse(false);
    sel.removeAllRanges();
    sel.addRange(range);
  }

  function init() {
    var api = window.notesAPI;
    var editor = $('notes-editor');
    var rawText = '';
    var saveTimer = null;

    if (!api) {
      editor.textContent = 'notesAPI 不可用 — preload 未注入';
      return;
    }

    // Load today's text, show rendered immediately
    api.getJournals().then(function (journals) {
      rawText = (journals || {})[todayKey()] || '';
      if (rawText) showRendered(editor, rawText);
    });

    // External update (sync from main app)
    api.onJournalUpdated(function (date, text) {
      if (date !== todayKey()) return;
      rawText = text || '';
      if (document.activeElement !== editor) {
        showRendered(editor, rawText);
      } else {
        editor.textContent = rawText;
      }
    });

    // Focus → switch to raw markdown for editing
    editor.addEventListener('focus', function () {
      if (editor.classList.contains('rendered')) {
        showRaw(editor, rawText);
      }
    });

    // Input → keep rawText in sync + debounced save
    editor.addEventListener('input', function () {
      rawText = editor.textContent;
      clearTimeout(saveTimer);
      saveTimer = setTimeout(function () {
        api.saveJournal(todayKey(), rawText);
        showSaved();
      }, 500);
    });

    // Blur → re-render markdown + save
    editor.addEventListener('blur', function () {
      rawText = editor.textContent;
      showRendered(editor, rawText);
      clearTimeout(saveTimer);
      api.saveJournal(todayKey(), rawText);
      showSaved();
    });

    // Always-on-top pin
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
