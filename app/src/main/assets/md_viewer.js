'use strict';

const ALLOWED_ELEMENTS = new Set([
  'A', 'BLOCKQUOTE', 'BR', 'CODE', 'DEL', 'EM', 'H1', 'H2', 'H3', 'H4', 'H5',
  'H6', 'HR', 'IMG', 'INPUT', 'LI', 'OL', 'P', 'PRE', 'SPAN', 'STRONG', 'TABLE',
  'TBODY', 'TD', 'TH', 'THEAD', 'TR', 'UL'
]);

function safeLink(rawValue) {
  const value = rawValue.trim();
  if (value.startsWith('#')) return value;
  try {
    const parsed = new URL(value);
    return ['https:', 'http:', 'mailto:'].includes(parsed.protocol) ? parsed.href : null;
  } catch (_) {
    return null;
  }
}

function safeImage(rawValue) {
  const value = rawValue.trim();
  return /^data:image\/(?:png|gif|jpe?g|webp);base64,[a-z0-9+/=\s]+$/i.test(value)
    ? value
    : null;
}

function sanitizeElement(element) {
  if (!ALLOWED_ELEMENTS.has(element.tagName)) {
    element.replaceWith(document.createTextNode(element.textContent || ''));
    return;
  }

  const originalAttributes = Array.from(element.attributes);
  for (const attribute of originalAttributes) element.removeAttribute(attribute.name);

  if (element.tagName === 'A') {
    const href = originalAttributes.find((attribute) => attribute.name.toLowerCase() === 'href');
    const safeHref = href ? safeLink(href.value) : null;
    if (safeHref) element.setAttribute('href', safeHref);
    element.setAttribute('rel', 'noopener noreferrer');
  } else if (element.tagName === 'IMG') {
    const src = originalAttributes.find((attribute) => attribute.name.toLowerCase() === 'src');
    const alt = originalAttributes.find((attribute) => attribute.name.toLowerCase() === 'alt');
    const safeSrc = src ? safeImage(src.value) : null;
    if (safeSrc) element.setAttribute('src', safeSrc);
    if (alt) element.setAttribute('alt', alt.value);
  } else if (element.tagName === 'INPUT') {
    const type = originalAttributes.find((attribute) => attribute.name.toLowerCase() === 'type');
    if (!type || type.value.toLowerCase() !== 'checkbox') {
      element.replaceWith(document.createTextNode(element.textContent || ''));
      return;
    }
    element.setAttribute('type', 'checkbox');
    element.setAttribute('disabled', '');
    if (originalAttributes.some((attribute) => attribute.name.toLowerCase() === 'checked')) {
      element.setAttribute('checked', '');
    }
  }
}

function sanitizeMarkdownHtml(html) {
  const template = document.createElement('template');
  template.innerHTML = html;
  const elements = Array.from(template.content.querySelectorAll('*'));
  // Work from the leaves upward so replacing an unsafe parent cannot retain active descendants.
  for (let index = elements.length - 1; index >= 0; index--) {
    sanitizeElement(elements[index]);
  }
  return template.content;
}

function renderMarkdown(text) {
  const content = document.getElementById('content');
  content.replaceChildren();
  if (!text || !text.trim()) {
    const hint = document.createElement('p');
    hint.className = 'hint';
    hint.textContent = '记录今天的想法、灵感…';
    content.appendChild(hint);
    return;
  }
  const parsed = marked.parse(text, { async: false, breaks: true });
  content.appendChild(sanitizeMarkdownHtml(parsed));
}

// Kotlin passes content as UTF-8 base64 to avoid JavaScript string escaping issues.
function renderBase64(base64) {
  try {
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
    renderMarkdown(new TextDecoder('utf-8').decode(bytes));
  } catch (_) {
    renderMarkdown('');
  }
}
