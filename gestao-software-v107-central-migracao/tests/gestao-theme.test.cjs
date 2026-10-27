const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const themeScript = fs.readFileSync(path.join(__dirname, '../web/shared/gestao-theme.js'), 'utf8');

function createPage(storage = new Map()) {
  const listeners = new Map();
  const buttonAttributes = {};
  const label = { textContent: '' };
  const button = {
    setAttribute: (key, value) => { buttonAttributes[key] = value; },
    querySelector: (selector) => selector === '[data-gestao-theme-label]' ? label : null,
  };
  class MockElement { closest() { return button; } }
  const meta = { setAttribute: (key, value) => { meta[key] = value; } };
  const documentElement = { dataset: {}, style: {} };
  const document = {
    documentElement,
    querySelector: (selector) => selector === 'meta[name="theme-color"]' ? meta : null,
    querySelectorAll: () => [button],
    addEventListener: (event, callback) => listeners.set(event, callback),
  };
  const windowListeners = new Map();
  const window = { addEventListener: (event, callback) => windowListeners.set(event, callback) };
  const localStorage = {
    getItem: (key) => storage.get(key) ?? null,
    setItem: (key, value) => storage.set(key, value),
  };
  vm.runInNewContext(themeScript, { document, window, localStorage, Element: MockElement });
  listeners.get('DOMContentLoaded')();
  return { storage, listeners, windowListeners, buttonAttributes, label, documentElement, meta, MockElement };
}

test('alternância de modo persiste entre site, login e Control Plane', () => {
  const storage = new Map();
  const firstPage = createPage(storage);
  assert.equal(firstPage.documentElement.dataset.theme, 'dark');
  assert.equal(firstPage.buttonAttributes['aria-label'], 'Ativar modo claro');
  firstPage.listeners.get('click')({ target: new firstPage.MockElement() });
  assert.equal(firstPage.documentElement.dataset.theme, 'light');
  assert.equal(storage.get('gestao-theme'), 'light');
  assert.equal(createPage(storage).documentElement.dataset.theme, 'light');
});

test('mudança de tema em outra aba reflete na página atual', () => {
  const storage = new Map([['gestao-theme', 'light']]);
  const page = createPage(storage);
  assert.equal(page.meta.content, '#eef4fa');
  assert.equal(page.label.textContent, 'Modo escuro');
  storage.set('gestao-theme', 'dark');
  page.windowListeners.get('storage')({ key: 'gestao-theme' });
  assert.equal(page.documentElement.dataset.theme, 'dark');
});

test('páginas públicas internas e administrativas carregam controle global', () => {
  for (const page of ['../web/admin/index.html', '../web/admin/login.html', '../web/contract/index.html']) {
    const html = fs.readFileSync(path.join(__dirname, page), 'utf8');
    assert.match(html, /shared\/gestao-theme\.js/);
    assert.match(html, /shared\/gestao-theme\.css/);
    assert.match(html, /data-gestao-theme-toggle/);
  }
});
