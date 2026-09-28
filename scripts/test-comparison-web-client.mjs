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
    'compareOnlyA', 'compareOnlyB', 'comparePreviewSection', 'comparePreviewTitle',
    'comparePreviewStatus', 'comparePreviewExcerpt', 'comparePreviewFrame', 'comparePreviewText',
    'comparePreviewClose'];
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
        this.src = undefined;
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
    removeAttribute(name) {
        if (name === 'src') this.src = undefined;
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

function fileResponse(blob, status = 200) {
    return { ok: status >= 200 && status < 300, status, blob: async () => blob };
}

function setup(handler) {
    const elements = Object.fromEntries(ids.map(id => [id, new Element()]));
    const calls = [];
    const revokedUrls = [];
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
        Blob,
        URL: {
            createObjectURL: () => 'blob:comparison-fixture',
            revokeObjectURL: url => revokedUrls.push(url)
        },
        fetch: async (url, options) => {
            calls.push({ url, options });
            return handler(url, options);
        }
    });
    vm.runInContext(script, context);
    return { elements, calls, revokedUrls, getToken: () => token, getReloads: () => reloads };
}

const success = setup(url => {
    if (url.startsWith('/api/documents?')) return response({ content: documents, totalPages: 1 });
    if (url === '/api/documents/11/download') return fileResponse(new Blob(['<script>original text</script>']));
    if (url === '/api/documents/12/download') return fileResponse(new Blob(['%PDF-original bytes']));
    return response(comparison);
});
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
assert.equal(success.elements.compareOnlyA.children[0].children[2].textContent, '查看原文');
assert.equal(success.elements.compareOnlyB.children[0].children[2].textContent, '查看原文第 2 页');

await success.elements.compareOnlyB.children[0].children[2].listeners.click();
assert.equal(success.calls[2].url, '/api/documents/12/download', 'B evidence must preview document B');
assert.equal(success.calls[2].options.headers.Authorization, 'Bearer fixture-token');
assert.equal(success.elements.comparePreviewFrame.src, 'blob:comparison-fixture#page=2');
assert.equal(success.elements.comparePreviewExcerpt.textContent, 'new policy');
assert.match(success.elements.comparePreviewStatus.textContent, /PDF 第 2 页/);

await success.elements.compareOnlyA.children[0].children[2].listeners.click();
assert.equal(success.calls[3].url, '/api/documents/11/download', 'A evidence must preview document A');
assert.equal(success.elements.comparePreviewText.textContent, '<script>original text</script>');
assert.equal(success.elements.comparePreviewText.children.length, 0, 'original text must not parse HTML');
assert.equal(success.elements.comparePreviewFrame.src, undefined);
assert.deepEqual(success.revokedUrls, ['blob:comparison-fixture'], 'superseding a PDF preview must revoke its object URL');
success.elements.comparePreviewClose.listeners.click();
assert.ok(success.elements.comparePreviewSection.className.includes('d-none'));

const callsBeforeInvalidSelection = success.calls.length;
success.elements.compareDocumentB.value = '11';
await success.elements.compareRunButton.listeners.click();
assert.match(success.elements.compareStatus.textContent, /两份不同/);
assert.equal(success.calls.length, callsBeforeInvalidSelection, 'same-document selection must not call an API');

const pageLessPdfComparison = {
    ...comparison,
    onlyA: [{ documentId: 11, chunkIndex: 0, pageNumber: null, text: 'page-less PDF evidence' }]
};
const pageLessPdf = setup(url => {
    if (url.startsWith('/api/documents?')) return response({ content: documents, totalPages: 1 });
    if (url === '/api/documents/11/download') {
        return fileResponse(new Blob(['x'.repeat(600), '%PDF-page-less original']));
    }
    return response(pageLessPdfComparison);
});
await pageLessPdf.elements.compareRefreshButton.listeners.click();
await pageLessPdf.elements.compareRunButton.listeners.click();
await pageLessPdf.elements.compareOnlyA.children[0].children[2].listeners.click();
assert.equal(pageLessPdf.elements.comparePreviewFrame.src, 'blob:comparison-fixture',
    'a PDF header within the first 1024 bytes should open as a PDF without a page fragment');
assert.match(pageLessPdf.elements.comparePreviewStatus.textContent, /尚无页码/);
assert.ok(pageLessPdf.elements.comparePreviewText.className.includes('d-none'));

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

const previewExpired = setup(url => {
    if (url.startsWith('/api/documents?')) return response({ content: documents, totalPages: 1 });
    if (url === '/api/documents/12/download') return response(null, 403);
    return response(comparison);
});
await previewExpired.elements.compareRefreshButton.listeners.click();
await previewExpired.elements.compareRunButton.listeners.click();
await previewExpired.elements.compareOnlyB.children[0].children[2].listeners.click();
assert.equal(previewExpired.getToken(), null, 'expired preview authentication must clear the token');
assert.equal(previewExpired.getReloads(), 1);
assert.match(previewExpired.elements.comparePreviewStatus.textContent, /登录已过期/);

const previewFailure = setup(url => {
    if (url.startsWith('/api/documents?')) return response({ content: documents, totalPages: 1 });
    if (url === '/api/documents/12/download') return response(null, 500);
    return response(comparison);
});
await previewFailure.elements.compareRefreshButton.listeners.click();
await previewFailure.elements.compareRunButton.listeners.click();
await previewFailure.elements.compareOnlyB.children[0].children[2].listeners.click();
assert.match(previewFailure.elements.comparePreviewStatus.textContent, /HTTP 500/);

let releasePreview;
const stalePreview = setup(url => {
    if (url.startsWith('/api/documents?')) return response({ content: documents, totalPages: 1 });
    if (url === '/api/documents/12/download') return new Promise(resolve => { releasePreview = resolve; });
    return response(comparison);
});
await stalePreview.elements.compareRefreshButton.listeners.click();
await stalePreview.elements.compareRunButton.listeners.click();
const pendingPreview = stalePreview.elements.compareOnlyB.children[0].children[2].listeners.click();
await Promise.resolve();
stalePreview.elements.comparePreviewClose.listeners.click();
releasePreview(fileResponse(new Blob(['%PDF-late response'])));
await pendingPreview;
assert.ok(stalePreview.elements.comparePreviewSection.className.includes('d-none'));
assert.equal(stalePreview.elements.comparePreviewFrame.src, undefined,
    'closing a preview must ignore a late original-file response');
assert.deepEqual(stalePreview.revokedUrls, [], 'a response closed before URL creation has nothing to revoke');

const closeOnChange = setup(url => url.startsWith('/api/documents?')
    ? response({ content: documents, totalPages: 1 })
    : url.endsWith('/download') ? fileResponse(new Blob(['%PDF-change'])) : response(comparison));
await closeOnChange.elements.compareRefreshButton.listeners.click();
await closeOnChange.elements.compareRunButton.listeners.click();
await closeOnChange.elements.compareOnlyB.children[0].children[2].listeners.click();
closeOnChange.elements.compareDocumentB.value = '11';
closeOnChange.elements.compareDocumentB.listeners.change();
assert.ok(closeOnChange.elements.comparePreviewSection.className.includes('d-none'),
    'changing the selection must close and clear the preview');
assert.deepEqual(closeOnChange.revokedUrls, ['blob:comparison-fixture']);

const closeOnRefresh = setup(url => url.startsWith('/api/documents?')
    ? response({ content: documents, totalPages: 1 })
    : url.endsWith('/download') ? fileResponse(new Blob(['%PDF-refresh'])) : response(comparison));
await closeOnRefresh.elements.compareRefreshButton.listeners.click();
await closeOnRefresh.elements.compareRunButton.listeners.click();
await closeOnRefresh.elements.compareOnlyB.children[0].children[2].listeners.click();
await closeOnRefresh.elements.compareRefreshButton.listeners.click();
assert.ok(closeOnRefresh.elements.comparePreviewSection.className.includes('d-none'),
    'refreshing the document list must close and clear the preview');
assert.deepEqual(closeOnRefresh.revokedUrls, ['blob:comparison-fixture']);

const closeOnCompare = setup(url => url.startsWith('/api/documents?')
    ? response({ content: documents, totalPages: 1 })
    : url.endsWith('/download') ? fileResponse(new Blob(['%PDF-compare'])) : response(comparison));
await closeOnCompare.elements.compareRefreshButton.listeners.click();
await closeOnCompare.elements.compareRunButton.listeners.click();
await closeOnCompare.elements.compareOnlyB.children[0].children[2].listeners.click();
await closeOnCompare.elements.compareRunButton.listeners.click();
assert.ok(closeOnCompare.elements.comparePreviewSection.className.includes('d-none'),
    'starting another comparison must close and clear the preview');
assert.deepEqual(closeOnCompare.revokedUrls, ['blob:comparison-fixture']);

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

let releaseRefresh;
let refreshCount = 0;
const refreshRace = setup(url => {
    if (url.startsWith('/api/documents?')) {
        refreshCount++;
        return refreshCount === 1 ? response({ content: documents, totalPages: 1 })
            : new Promise(resolve => { releaseRefresh = resolve; });
    }
    return response(comparison);
});
await refreshRace.elements.compareRefreshButton.listeners.click();
const pendingRefresh = refreshRace.elements.compareRefreshButton.listeners.click();
assert.equal(refreshRace.elements.compareDocumentA.disabled, true);
assert.equal(refreshRace.elements.compareRunButton.disabled, true);
refreshRace.elements.compareDocumentA.value = '12';
refreshRace.elements.compareDocumentB.value = '11';
refreshRace.elements.compareDocumentA.listeners.change();
assert.equal(refreshRace.elements.compareRunButton.disabled, true,
    'changing a selection during refresh must not enable comparison');
await refreshRace.elements.compareRunButton.listeners.click();
assert.equal(refreshRace.calls.filter(call => call.url === '/api/documents/compare').length, 0,
    'comparison must reject a click during document refresh');
releaseRefresh(response({ content: documents, totalPages: 1 }));
await pendingRefresh;
assert.equal(refreshRace.elements.compareDocumentA.value, '11');
assert.equal(refreshRace.elements.compareDocumentB.value, '12');
assert.equal(refreshRace.elements.compareDocumentA.disabled, false);
assert.equal(refreshRace.elements.compareRunButton.disabled, false);

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
