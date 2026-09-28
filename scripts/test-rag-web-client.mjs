import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';

const html = readFileSync(new URL('../src/main/resources/templates/index.html', import.meta.url), 'utf8');
const script = html.match(/<script id="ragWebClient">([\s\S]*?)<\/script>/)?.[1];
assert.ok(script, 'dedicated RAG client script must exist');
assert.ok(!script.includes('innerHTML'), 'RAG renderer must not use innerHTML');
new vm.Script(script, { filename: 'ragWebClient' });
for (const id of ['ragQuestionInput', 'ragTopKInput', 'ragThresholdInput', 'ragSearchButton',
    'ragAskButton', 'ragStatus', 'ragAnswer', 'ragSources', 'ragPreviewSection',
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
        'ragPreviewSection', 'ragPreviewTitle', 'ragPreviewStatus', 'ragPreviewFrame',
        'ragPreviewExcerpt', 'ragPreviewText', 'ragPreviewClose'];
    const elements = Object.fromEntries(ids.map(id => [id, new Element(id.endsWith('Button') ? 'button' : 'div')]));
    elements.ragQuestionInput.value = '  quarterly plan  ';
    elements.ragTopKInput.value = '4';
    elements.ragThresholdInput.value = '0.42';
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

console.log('RAG web client request, rendering, empty/error, and text-safety contracts passed');
