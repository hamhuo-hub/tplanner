import { lightTokens } from './tplanner-light.mjs';

// Invalid IDs retain the existing default category; persisted IDs are never changed.
export function categoryForId(value) {
  const id = Number(value);
  return lightTokens.semantic.category[`id${Number.isInteger(id) && id >= 0 && id < 8 ? id : 0}`];
}

export function applyCategory(element, value) {
  const category = categoryForId(value);
  element.dataset.categoryId = String(category.id);
  for (const role of ['accent', 'foreground', 'background', 'border']) {
    element.style.setProperty(`--category-${role}`, category[role]);
  }
}

export function initializeWindowControls(api) {
  const pin = document.getElementById('btn-pin');
  const reflectPin = (on) => {
    pin.classList.toggle('active', on);
    pin.setAttribute('aria-pressed', String(Boolean(on)));
    pin.title = on ? '取消始终置顶' : '始终置顶';
    pin.setAttribute('aria-label', pin.title);
  };
  api.isAlwaysOnTop().then(reflectPin);
  pin.addEventListener('click', () => api.toggleAlwaysOnTop().then(reflectPin));
  document.getElementById('btn-open').addEventListener('click', () => api.openMain());
  document.getElementById('btn-close').addEventListener('click', () => api.close());
}
