import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const html = readFileSync(new URL('../src/main/resources/templates/index.html', import.meta.url), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)?.[1];
assert.ok(script, 'main page script must exist');
for (const id of ['documentsTableBody', 'deletedDocumentsTableBody', 'documentActionAlert']) {
    assert.ok(html.includes(`id="${id}"`), `${id} must exist in the page`);
}

class Element {
    constructor(tagName) {
        this.tagName = tagName;
        this.children = [];
        this.dataset = {};
        this._text = '';
        this.disabled = false;
    }
    set textContent(value) {
        this._text = String(value);
        this.children = [];
    }
    get textContent() {
        return this._text + this.children.map(child => child.textContent).join('');
    }
    appendChild(child) {
        this.children.push(child);
        return child;
    }
    replaceChildren(...children) {
        this._text = '';
        this.children = children;
    }
    addEventListener() {}
}

const elements = Object.fromEntries(
    ['documentsTableBody', 'deletedDocumentsTableBody', 'documentActionAlert']
        .map(id => [id, new Element('div')])
);
let active = [{ id: 7, fileName: '<img src=x onerror=alert(1)>.txt',
    status: 'completed', summary: 'Ready', uploadTime: '2026-09-24T10:00:00' }];
let deleted = [];
const calls = [];
const fetch = async (url, options = {}) => {
    calls.push({ url, options });
    assert.equal(options.headers.Authorization, 'Bearer fixture-token');
    if (url.startsWith('/api/documents?')) {
        return { ok: true, status: 200, json: async () => ({
            data: { content: active, totalPages: 1 }
        }) };
    }
    if (url === '/api/documents/deleted') {
        return { ok: true, status: 200, json: async () => ({ data: deleted }) };
    }
    if (url === '/api/documents/7' && options.method === 'DELETE') {
        deleted = active;
        active = [];
        return { ok: true, status: 200, json: async () => ({}) };
    }
    if (url === '/api/documents/7/restore' && options.method === 'POST') {
        active = deleted.map(doc => ({ ...doc, status: 'processing' }));
        deleted = [];
        return { ok: true, status: 202, json: async () => ({}) };
    }
    if (url === '/api/documents/7/status') {
        return { ok: true, status: 200, json: async () => ({
            data: { status: 'completed', summary: 'Rebuilt summary' }
        }) };
    }
    throw new Error(`unexpected request: ${url}`);
};

const context = vm.createContext({
    document: {
        getElementById: id => elements[id] ?? null,
        createElement: tagName => new Element(tagName),
        querySelectorAll: () => [],
        addEventListener() {}
    },
    window: { addEventListener() {}, confirm: () => true, location: { reload() {} } },
    localStorage: { getItem: () => 'fixture-token', removeItem() {} },
    fetch,
    setInterval() {},
    setTimeout() {},
    console
});
vm.runInContext(script, context);

await context.refreshDocuments();
await context.refreshDeletedDocuments();
assert.equal(elements.documentsTableBody.children.length, 1);
assert.equal(elements.documentsTableBody.children[0].children[1].textContent,
    '<img src=x onerror=alert(1)>.txt');
const deleteButton = elements.documentsTableBody.children[0].children[5].children[0];
assert.equal(deleteButton.dataset.documentAction, 'delete');
await context.handleDocumentAction({ target: { closest: () => deleteButton } });
assert.equal(elements.deletedDocumentsTableBody.children.length, 1);
assert.equal(calls.find(call => call.options.method === 'DELETE')?.url, '/api/documents/7');

const restoreButton = elements.deletedDocumentsTableBody.children[0].children[2].children[0];
assert.equal(restoreButton.dataset.documentAction, 'restore');
await context.handleDocumentAction({ target: { closest: () => restoreButton } });
assert.equal(calls.find(call => call.options.method === 'POST')?.url, '/api/documents/7/restore');
assert.equal(elements.deletedDocumentsTableBody.children[0].textContent, '回收站为空');
assert.equal(elements.documentsTableBody.children[0].dataset.docStatus, 'processing');
assert.equal(elements.documentsTableBody.children[0].children[5].children[0].disabled, true);
const restoredRow = elements.documentsTableBody.children[0];
restoredRow.getAttribute = name => name === 'data-doc-id' ? '7' : null;
restoredRow.setAttribute = (name, value) => {
    if (name === 'data-doc-status') restoredRow.dataset.docStatus = value;
};
restoredRow.querySelector = selector => {
    if (selector === '.doc-status-badge') return restoredRow.children[2].children[0];
    if (selector === '.doc-summary-box') return restoredRow.children[3].children[0];
    if (selector === 'button[data-document-action="delete"]') return restoredRow.children[5].children[0];
    return null;
};
context.document.querySelectorAll = () => [restoredRow];
context.checkProcessingDocuments();
await new Promise(resolve => setImmediate(resolve));
assert.equal(restoredRow.dataset.docStatus, 'completed');
assert.equal(restoredRow.children[3].children[0].textContent, 'Rebuilt summary');
assert.equal(restoredRow.children[5].children[0].disabled, false);
console.log('Document web client delete/restore flow passed');
