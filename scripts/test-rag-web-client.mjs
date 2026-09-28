import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const html = readFileSync(new URL('../src/main/resources/templates/index.html', import.meta.url), 'utf8');
const script = html.match(/<script id="ragWebClient">([\s\S]*?)<\/script>/)?.[1];
assert.ok(script, 'dedicated RAG client script must exist');
assert.ok(!script.includes('innerHTML'), 'RAG renderer must not use innerHTML');
new vm.Script(script, { filename: 'ragWebClient' });
for (const id of ['ragQuestionInput', 'ragTopKInput', 'ragThresholdInput', 'ragSearchButton',
    'ragAskButton', 'ragStatus', 'ragDocumentScopeAll', 'ragDocumentScopeSelected',
    'ragDocumentScopePicker', 'ragDocumentRefreshButton', 'ragDocumentListStatus', 'ragDocumentList',
    'ragDocumentCount', 'ragAnswer', 'ragSources', 'ragPreviewSection',
    'ragPreviewTitle', 'ragPreviewStatus', 'ragPreviewExcerpt', 'ragPreviewFrame', 'ragPreviewText', 'ragPreviewClose']) {
    assert.ok(html.includes(`id="${id}"`), `${id} must exist in the page`);
}

class Element {
    constructor(tagName = 'div') {
        this.tagName = tagName;
        this.children = [];
        this.listeners = {};
        this.className = '';
        this.disabled = false;
        this.value = '';
        this.checked = false;
        this.type = '';
        this.htmlFor = '';
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
    removeAttribute(name) {
        delete this[name];
    }
}

function setup(fetchResponse) {
    const ids = ['ragQuestionInput', 'ragTopKInput', 'ragThresholdInput', 'ragSearchButton',
        'ragAskButton', 'ragStatus', 'ragAnswerSection', 'ragAnswer', 'ragSourcesSection', 'ragSources',
        'ragDocumentScopeAll', 'ragDocumentScopeSelected', 'ragDocumentScopePicker',
        'ragDocumentRefreshButton', 'ragDocumentListStatus', 'ragDocumentCount', 'ragDocumentList',
        'ragPreviewSection', 'ragPreviewTitle', 'ragPreviewStatus', 'ragPreviewFrame',
        'ragPreviewExcerpt', 'ragPreviewText', 'ragPreviewClose'];
    const elements = Object.fromEntries(ids.map(id => [id, new Element(id.endsWith('Button') ? 'button' : 'div')]));
    elements.ragQuestionInput.value = '  quarterly plan  ';
    elements.ragTopKInput.value = '4';
    elements.ragThresholdInput.value = '0.42';
    elements.ragDocumentScopeAll.checked = true;
    const calls = [];
    let token = 'fixture-token';
    let reloads = 0;
    const revokedUrls = [];
    const context = vm.createContext({
        document: {
            getElementById: id => elements[id],
            createElement: tag => new Element(tag)
        },
        localStorage: {
            getItem: key => key === 'jwtToken' ? token : null,
            removeItem: key => { if (key === 'jwtToken') token = null; }
        },
        window: { location: { reload: () => { reloads++; } } },
        Blob,
        URL: {
            createObjectURL: () => 'blob:source-fixture',
            revokeObjectURL: url => revokedUrls.push(url)
        },
        fetch: async (url, options) => {
            calls.push({ url, options });
            return fetchResponse(url, options);
        }
    });
    vm.runInContext(script, context);
    return { elements, calls, context, revokedUrls, getToken: () => token, getReloads: () => reloads };
}

function jsonResponse(data, status = 200) {
    return { ok: status >= 200 && status < 300, status, json: async () => ({ data }) };
}

function documentListResponse(documents, status = 200) {
    return jsonResponse(documents, status);
}

function setSelectedMode(client) {
    client.elements.ragDocumentScopeAll.checked = false;
    client.elements.ragDocumentScopeSelected.checked = true;
    return client.elements.ragDocumentScopeSelected.listeners.change();
}

function checkboxes(client) {
    return client.elements.ragDocumentList.children.map(row => row.children[0]);
}

const searchResult = {
    documentId: 19,
    documentTitle: '<img src=x onerror=alert(1)>.pdf',
    chunkIndex: 3,
    pageNumber: 4,
    content: '<script>alert("chunk")</script> useful text',
    score: 0.876
};
const search = setup(url => url.endsWith('/download')
    ? { ok: true, status: 200, blob: async () => new Blob(['%PDF-fixture']) }
    : jsonResponse([searchResult]));
await search.elements.ragSearchButton.listeners.click();
assert.equal(search.calls.length, 1);
assert.equal(search.calls[0].url, '/api/search/query');
assert.equal(search.calls[0].options.method, 'POST');
assert.equal(search.calls[0].options.headers.Authorization, 'Bearer fixture-token');
assert.deepEqual(JSON.parse(search.calls[0].options.body), {
    query: 'quarterly plan', topK: 4, similarityThreshold: 0.42
});
assert.ok(!Object.hasOwn(JSON.parse(search.calls[0].options.body), 'documentIds'),
    'all-my-documents is the default and keeps the legacy request body');
const sourceCard = search.elements.ragSources.children[0];
assert.match(sourceCard.textContent, /<img src=x onerror=alert\(1\)>\.pdf · Chunk 3 · 第 4 页/);
assert.match(sourceCard.textContent, /相似度分数: 0\.876/);
assert.match(sourceCard.textContent, /<script>alert\("chunk"\)<\/script> useful text/);
assert.equal(sourceCard.children.length, 4, 'source should be built from text nodes, with no parsed HTML children');
await sourceCard.children[3].listeners.click();
assert.equal(search.calls[1].url, '/api/documents/19/download');
assert.equal(search.calls[1].options.headers.Authorization, 'Bearer fixture-token');
assert.equal(search.elements.ragPreviewFrame.src, 'blob:source-fixture#page=4');
assert.match(search.elements.ragPreviewStatus.textContent, /第 4 页/);
assert.equal(search.elements.ragPreviewExcerpt.textContent, searchResult.content);
search.elements.ragPreviewClose.listeners.click();
assert.equal(search.elements.ragPreviewFrame.src, undefined);
assert.equal(search.elements.ragPreviewExcerpt.textContent, '');
assert.deepEqual(search.revokedUrls, ['blob:source-fixture']);
assert.match(search.elements.ragStatus.textContent, /检索完成/);

const textSource = { ...searchResult, pageNumber: null, documentTitle: 'notes.txt' };
const textPreview = setup(url => url.endsWith('/download')
    ? { ok: true, status: 200, blob: async () => new Blob(['<script>alert(1)</script>']) }
    : jsonResponse([textSource]));
await textPreview.elements.ragSearchButton.listeners.click();
await textPreview.elements.ragSources.children[0].children[3].listeners.click();
assert.equal(textPreview.elements.ragPreviewText.textContent, '<script>alert(1)</script>');
assert.equal(textPreview.elements.ragPreviewText.children.length, 0, 'original text must not parse HTML');
assert.equal(textPreview.elements.ragPreviewFrame.src, undefined);

const pageLessPdfPreview = setup(url => url.endsWith('/download')
    ? { ok: true, status: 200, blob: async () => new Blob(['x'.repeat(600), '%PDF-page-less original']) }
    : jsonResponse([textSource]));
await pageLessPdfPreview.elements.ragSearchButton.listeners.click();
await pageLessPdfPreview.elements.ragSources.children[0].children[3].listeners.click();
assert.equal(pageLessPdfPreview.elements.ragPreviewFrame.src, 'blob:source-fixture',
    'a PDF header within the first 1024 bytes should open as a PDF without a page fragment');
assert.match(pageLessPdfPreview.elements.ragPreviewStatus.textContent, /尚无页码/);
assert.equal(pageLessPdfPreview.elements.ragPreviewText.className.includes('d-none'), true);

let releaseOldText;
const delayedText = setup(url => url.endsWith('/download')
    ? { ok: true, status: 200, blob: async () => ({
        slice: () => new Blob(['plain']),
        text: () => new Promise(resolve => { releaseOldText = resolve; })
    }) }
    : jsonResponse([textSource]));
await delayedText.elements.ragSearchButton.listeners.click();
const pendingPreview = delayedText.elements.ragSources.children[0].children[3].listeners.click();
await new Promise(setImmediate);
delayedText.elements.ragPreviewClose.listeners.click();
releaseOldText('stale original text');
await pendingPreview;
assert.equal(delayedText.elements.ragPreviewText.textContent, '',
    'a closed preview must not render a late original-file response');

const askAnswer = '<b>Answer</b> with context';
const ask = setup(() => jsonResponse({ answer: askAnswer, sources: [searchResult] }));
await ask.elements.ragAskButton.listeners.click();
assert.equal(ask.calls[0].url, '/api/search/ask');
assert.deepEqual(JSON.parse(ask.calls[0].options.body), { question: 'quarterly plan', topK: 4 });
assert.match(ask.elements.ragStatus.textContent, /范围：全部我的文档/);
assert.equal(ask.elements.ragAnswer.textContent, askAnswer);
assert.equal(ask.elements.ragAnswer.children.length, 0, 'answer must render as text');
assert.equal(ask.elements.ragSources.children.length, 1);

const empty = setup(() => jsonResponse([]));
await empty.elements.ragSearchButton.listeners.click();
assert.match(empty.elements.ragStatus.textContent, /没有找到相关片段/);
assert.equal(empty.elements.ragSources.children.length, 0);

const failed = setup(() => jsonResponse(null, 500));
await failed.elements.ragAskButton.listeners.click();
assert.match(failed.elements.ragStatus.textContent, /HTTP 500/);
assert.ok(failed.elements.ragStatus.className.includes('alert-danger'));

const unauthorized = setup(() => jsonResponse(null, 401));
await unauthorized.elements.ragSearchButton.listeners.click();
assert.match(unauthorized.elements.ragStatus.textContent, /登录已过期/);
assert.equal(unauthorized.getToken(), null, '401 should clear the stale token');
assert.equal(unauthorized.getReloads(), 1, '401 should reopen the login flow');

const forbidden = setup(() => jsonResponse(null, 403));
await forbidden.elements.ragAskButton.listeners.click();
assert.match(forbidden.elements.ragStatus.textContent, /登录已过期/);
assert.equal(forbidden.getToken(), null, '403 should clear the stale token');
assert.equal(forbidden.getReloads(), 1, '403 should reopen the login flow');

let releaseOldSearch;
const stale = setup(url => url === '/api/search/query'
    ? new Promise(resolve => { releaseOldSearch = resolve; })
    : Promise.resolve(jsonResponse({ answer: 'new response', sources: [searchResult] })));
const oldRequest = stale.elements.ragSearchButton.listeners.click();
const newRequest = stale.elements.ragAskButton.listeners.click();
await newRequest;
releaseOldSearch(jsonResponse([{ ...searchResult, content: 'stale response' }]));
await oldRequest;
assert.equal(stale.elements.ragAnswer.textContent, 'new response');
assert.ok(!stale.elements.ragSources.textContent.includes('stale response'),
    'a late response must not replace newer results');

const scopedSearch = setup((url) => url === '/api/documents/me'
    ? documentListResponse([
        { id: 31, fileName: '<img src=x onerror=alert(1)>.pdf' },
        { id: 31, fileName: 'duplicate should be ignored.pdf' },
        { id: 32, fileName: 'notes.txt' }
    ])
    : jsonResponse([searchResult]));
await setSelectedMode(scopedSearch);
assert.equal(scopedSearch.calls[0].url, '/api/documents/me');
assert.equal(scopedSearch.calls[0].options.headers.Authorization, 'Bearer fixture-token');
assert.equal(scopedSearch.elements.ragDocumentList.children.length, 2, 'document IDs are deduplicated');
assert.equal(scopedSearch.elements.ragDocumentList.children[0].children[1].textContent,
    '<img src=x onerror=alert(1)>.pdf · #31', 'document filenames render as text');
assert.equal(scopedSearch.elements.ragDocumentList.children[0].children[1].children.length, 0,
    'document titles must not parse HTML');
assert.equal(scopedSearch.elements.ragSearchButton.disabled, true, 'selected scope requires at least one document');
const scopedCheckboxes = checkboxes(scopedSearch);
scopedCheckboxes[0].checked = true;
scopedCheckboxes[0].listeners.change();
assert.equal(scopedSearch.elements.ragSearchButton.disabled, false);
await scopedSearch.elements.ragSearchButton.listeners.click();
assert.deepEqual(JSON.parse(scopedSearch.calls[1].options.body), {
    query: 'quarterly plan', topK: 4, similarityThreshold: 0.42, documentIds: [31]
});
assert.match(scopedSearch.elements.ragStatus.textContent, /范围：已选择 1 份文档/);

const scopedAsk = setup(url => url === '/api/documents/me'
    ? documentListResponse([{ id: 41, fileName: 'one.pdf' }, { id: 42, fileName: 'two.pdf' }])
    : jsonResponse({ answer: 'scoped answer', sources: [searchResult] }));
await setSelectedMode(scopedAsk);
for (const checkbox of checkboxes(scopedAsk)) {
    checkbox.checked = true;
    checkbox.listeners.change();
}
await scopedAsk.elements.ragAskButton.listeners.click();
assert.deepEqual(JSON.parse(scopedAsk.calls[1].options.body), {
    question: 'quarterly plan', topK: 4, documentIds: [41, 42]
});
assert.match(scopedAsk.elements.ragStatus.textContent, /范围：已选择 2 份文档/);

const invalidScope = setup(url => url === '/api/documents/me'
    ? documentListResponse([{ id: 51, fileName: 'one.pdf' }])
    : jsonResponse([searchResult]));
await setSelectedMode(invalidScope);
assert.equal(invalidScope.elements.ragAskButton.disabled, true, 'no selected documents disables ask');
const invalidCheckbox = checkboxes(invalidScope)[0];
invalidCheckbox.checked = true;
invalidCheckbox.value = '0';
invalidCheckbox.listeners.change();
assert.equal(invalidScope.elements.ragAskButton.disabled, true, 'invalid IDs disable selected-scope actions');
await invalidScope.elements.ragAskButton.listeners.click();
assert.equal(invalidScope.calls.filter(call => call.url === '/api/search/ask').length, 0,
    'invalid selected IDs must not reach the request body');

const listFailure = setup(url => url === '/api/documents/me'
    ? jsonResponse(null, 500)
    : jsonResponse([searchResult]));
await setSelectedMode(listFailure);
assert.match(listFailure.elements.ragDocumentListStatus.textContent, /HTTP 500/);
assert.equal(listFailure.elements.ragSearchButton.disabled, true, 'failed document loading disables selected scope');
assert.equal(listFailure.elements.ragDocumentRefreshButton.disabled, false, 'failed list can be retried');

const listUnauthorized = setup(url => url === '/api/documents/me'
    ? jsonResponse(null, 401)
    : jsonResponse([searchResult]));
await setSelectedMode(listUnauthorized);
assert.match(listUnauthorized.elements.ragDocumentListStatus.textContent, /登录已过期/);
assert.equal(listUnauthorized.getToken(), null, '401 while loading documents clears the stale token');
assert.equal(listUnauthorized.getReloads(), 1, '401 while loading documents reopens the login flow');
assert.equal(listUnauthorized.elements.ragAskButton.disabled, true);

let releaseOldScopedSearch;
const staleScope = setup(url => url === '/api/search/query'
    ? new Promise(resolve => { releaseOldScopedSearch = resolve; })
    : url === '/api/documents/me'
        ? documentListResponse([{ id: 61, fileName: 'scoped.pdf' }])
        : jsonResponse([searchResult]));
const oldScopeRequest = staleScope.elements.ragSearchButton.listeners.click();
await setSelectedMode(staleScope);
const staleCheckbox = checkboxes(staleScope)[0];
staleCheckbox.checked = true;
staleCheckbox.listeners.change();
releaseOldScopedSearch(jsonResponse([{ ...searchResult, content: 'stale all-documents result' }]));
await oldScopeRequest;
assert.ok(!staleScope.elements.ragSources.textContent.includes('stale all-documents result'),
    'changing scope suppresses a response started under the previous scope');
assert.equal(staleScope.elements.ragSearchButton.disabled, false,
    'changing scope recovers the busy state after the stale request');

let releaseOldAskBeforeRefresh;
let refreshedListCount = 0;
const refreshInvalidation = setup(url => {
    if (url === '/api/documents/me') {
        refreshedListCount++;
        return documentListResponse([{ id: 65, fileName: 'refreshed.pdf' }]);
    }
    if (url === '/api/search/ask') return new Promise(resolve => { releaseOldAskBeforeRefresh = resolve; });
    return jsonResponse([searchResult]);
});
await setSelectedMode(refreshInvalidation);
const beforeRefreshCheckbox = checkboxes(refreshInvalidation)[0];
beforeRefreshCheckbox.checked = true;
beforeRefreshCheckbox.listeners.change();
const oldAskBeforeRefresh = refreshInvalidation.elements.ragAskButton.listeners.click();
await refreshInvalidation.elements.ragDocumentRefreshButton.listeners.click();
assert.equal(refreshedListCount, 2);
releaseOldAskBeforeRefresh(jsonResponse({ answer: 'stale answer before refresh', sources: [searchResult] }));
await oldAskBeforeRefresh;
assert.equal(refreshInvalidation.elements.ragAnswer.textContent, '',
    'refreshing the document list invalidates an in-flight answer for the previous scope snapshot');
assert.equal(refreshInvalidation.elements.ragAskButton.disabled, false,
    'refreshing the list recovers the busy state after the stale ask');

const listResolvers = [];
const listRace = setup(url => url === '/api/documents/me'
    ? new Promise(resolve => { listResolvers.push(resolve); })
    : jsonResponse([searchResult]));
const firstList = setSelectedMode(listRace);
assert.equal(listRace.elements.ragSearchButton.disabled, true, 'list loading disables selected-scope actions');
listRace.elements.ragDocumentScopeAll.checked = true;
listRace.elements.ragDocumentScopeSelected.checked = false;
listRace.elements.ragDocumentScopeAll.listeners.change();
const secondList = setSelectedMode(listRace);
assert.equal(listResolvers.length, 2);
listResolvers[1](documentListResponse([{ id: 72, fileName: 'newer.pdf' }]));
await secondList;
listResolvers[0](documentListResponse([{ id: 71, fileName: 'older.pdf' }]));
await firstList;
assert.equal(listRace.elements.ragDocumentList.children.length, 1);
assert.equal(listRace.elements.ragDocumentList.children[0].children[1].textContent, 'newer.pdf · #72',
    'a late document-list response cannot replace the newer list');
assert.match(listRace.elements.ragDocumentListStatus.textContent, /已加载 1 份文档/);
assert.equal(listRace.elements.ragSearchButton.disabled, true, 'new list remains invalid until a document is checked');

console.log('RAG web client scope selection, request, rendering, empty/error, race, and text-safety contracts passed');
