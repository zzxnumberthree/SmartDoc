import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const html = readFileSync(new URL('../src/main/resources/templates/index.html', import.meta.url), 'utf8');
const script = html.match(/<script id="comparisonWebClient">([\s\S]*?)<\/script>/)?.[1];
assert.ok(script, 'comparison client script must exist');
assert.ok(!script.includes('innerHTML'), 'comparison results must use safe text rendering');
new vm.Script(script, { filename: 'comparisonWebClient' });

const ids = ['compareDocumentA', 'compareDocumentB', 'compareRefreshButton', 'compareRunButton',
    'compareStatus', 'compareResults', 'compareOverview', 'compareHeadingA', 'compareHeadingB',
    'compareOnlyA', 'compareOnlyB'];
for (const id of ids) assert.ok(html.includes(`id="${id}"`), `${id} must exist`);

class Element {
    constructor(tagName = 'div') {
        this.tagName = tagName;
        this.children = [];
        this.listeners = {};
        this.className = '';
        this.disabled = false;
        this.value = '';
        this._text = '';
        this.classList = {
            add: name => { this.className = [...new Set([...this.className.split(/\s+/), name])].join(' ').trim(); },
            remove: name => { this.className = this.className.split(/\s+/).filter(item => item !== name).join(' '); }
        };
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
    addEventListener(type, listener) {
        this.listeners[type] = listener;
    }
}

const documents = [
    { id: 11, fileName: '<b>old</b>.txt' },
    { id: 12, fileName: 'new.txt' }
];
const comparison = {
    documentA: { id: 11, title: '<b>old</b>.txt' },
    documentB: { id: 12, title: 'new.txt' },
    sharedCount: 1,
    onlyACount: 1,
    onlyBCount: 1,
    onlyATruncated: false,
    onlyBTruncated: false,
    onlyA: [{ documentId: 11, chunkIndex: 0, pageNumber: null, text: '<script>alert(1)</script>' }],
    onlyB: [{ documentId: 12, chunkIndex: 1, pageNumber: 2, text: 'new policy' }]
};

function response(data, status = 200) {
    return { ok: status >= 200 && status < 300, status, json: async () => ({ data }) };
}

function setup(handler) {
    const elements = Object.fromEntries(ids.map(id => [id, new Element()]));
    const calls = [];
    let token = 'fixture-token';
    let reloads = 0;
    const context = vm.createContext({
        document: {
            getElementById: id => elements[id],
            createElement: tag => new Element(tag)
        },
        localStorage: {
            getItem: key => key === 'jwtToken' ? token : null,
            removeItem: key => { if (key === 'jwtToken') token = null; }
        },
        window: { addEventListener() {}, location: { reload: () => { reloads++; } } },
        fetch: async (url, options) => {
            calls.push({ url, options });
            return handler(url, options);
        }
    });
    vm.runInContext(script, context);
    return { elements, calls, getToken: () => token, getReloads: () => reloads };
}

const success = setup(url => url.startsWith('/api/documents?')
    ? response({ content: documents, totalPages: 1 })
    : response(comparison));
await success.elements.compareRefreshButton.listeners.click();
assert.equal(success.elements.compareDocumentA.children.length, 2);
assert.equal(success.elements.compareDocumentA.value, '11');
assert.equal(success.elements.compareDocumentB.value, '12');
assert.equal(success.elements.compareDocumentA.children[0].textContent, '<b>old</b>.txt · #11');
await success.elements.compareRunButton.listeners.click();
assert.equal(success.calls[1].url, '/api/documents/compare');
assert.equal(success.calls[1].options.method, 'POST');
assert.equal(success.calls[1].options.headers.Authorization, 'Bearer fixture-token');
assert.deepEqual(JSON.parse(success.calls[1].options.body), { documentIdA: 11, documentIdB: 12 });
assert.match(success.elements.compareOverview.textContent, /共同文本行（去重） 1/);
assert.equal(success.elements.compareOnlyA.children[0].children[1].textContent, '<script>alert(1)</script>');
assert.equal(success.elements.compareOnlyA.children[0].children[1].children.length, 0,
    'evidence must not parse HTML');
assert.match(success.elements.compareOnlyB.children[0].textContent, /PDF 第 2 页/);

success.elements.compareDocumentB.value = '11';
await success.elements.compareRunButton.listeners.click();
assert.match(success.elements.compareStatus.textContent, /两份不同/);
assert.equal(success.calls.length, 2, 'same-document selection must not call compare API');

const forbidden = setup(url => url.startsWith('/api/documents?')
    ? response({ content: documents, totalPages: 1 })
    : response(null, 404));
await forbidden.elements.compareRefreshButton.listeners.click();
await forbidden.elements.compareRunButton.listeners.click();
assert.match(forbidden.elements.compareStatus.textContent, /无权访问/);
assert.equal(forbidden.getToken(), 'fixture-token', '404 should not clear authentication');

const oversized = setup(url => url.startsWith('/api/documents?')
    ? response({ content: documents, totalPages: 1 })
    : { ok: false, status: 400, json: async () => ({ detail: '文書の比較対象が上限を超えています。' }) });
await oversized.elements.compareRefreshButton.listeners.click();
await oversized.elements.compareRunButton.listeners.click();
assert.match(oversized.elements.compareStatus.textContent, /上限/);

const expired = setup(() => response(null, 401));
await expired.elements.compareRefreshButton.listeners.click();
assert.equal(expired.getToken(), null);
assert.equal(expired.getReloads(), 1);

let failRefresh = false;
const retry = setup(url => url.startsWith('/api/documents?')
    ? failRefresh ? response(null, 500) : response({ content: documents, totalPages: 1 })
    : response(comparison));
await retry.elements.compareRefreshButton.listeners.click();
failRefresh = true;
await retry.elements.compareRefreshButton.listeners.click();
assert.equal(retry.elements.compareRunButton.disabled, false,
    'a temporary refresh error must leave prior valid selections usable');
assert.match(retry.elements.compareStatus.textContent, /HTTP 500/);

let releaseComparison;
const stale = setup(url => url.startsWith('/api/documents?')
    ? response({ content: documents, totalPages: 1 })
    : new Promise(resolve => { releaseComparison = resolve; }));
await stale.elements.compareRefreshButton.listeners.click();
const pending = stale.elements.compareRunButton.listeners.click();
stale.elements.compareDocumentB.value = '11';
stale.elements.compareDocumentB.listeners.change();
releaseComparison(response(comparison));
await pending;
assert.ok(stale.elements.compareResults.className.includes('d-none'),
    'a late comparison must not replace a changed selection');

console.log('Document comparison web client selection, API, authorization, and safe rendering passed');
