// Xstream's Nuvio plugin runtime (our own code). Gives plugins what they expect from Nuvio: fetch, require
// ('cheerio-without-node-native', 'crypto-js', 'axios'), console — all calls go through Xstream's network.
(function () {
  'use strict';
  var pending = {};
  var seq = 0;
  function log(level, args) {
    try { XNative.log(level, Array.prototype.map.call(args, function (a) { return typeof a === 'string' ? a : (function () { try { return JSON.stringify(a); } catch (e) { return String(a); } })(); }).join(' ')); } catch (e) {}
  }
  var con = {
    log: function () { log('log', arguments); }, info: function () { log('info', arguments); },
    warn: function () { log('warn', arguments); }, error: function () { log('error', arguments); }, debug: function () { log('debug', arguments); }
  };
  window.console = con;

  function makeHeaders(h) {
    var m = {};
    Object.keys(h || {}).forEach(function (k) { m[k.toLowerCase()] = h[k]; });
    var o = {
      get: function (k) { k = String(k).toLowerCase(); return m.hasOwnProperty(k) ? m[k] : null; },
      has: function (k) { return m.hasOwnProperty(String(k).toLowerCase()); },
      forEach: function (f) { Object.keys(m).forEach(function (k) { f(m[k], k); }); },
      entries: function () { return Object.keys(m).map(function (k) { return [k, m[k]]; })[Symbol.iterator](); },
      keys: function () { return Object.keys(m)[Symbol.iterator](); },
      raw: function () { return m; }
    };
    o[Symbol.iterator] = o.entries;
    return o;
  }
  function makeResponse(r) {
    return {
      ok: r.status >= 200 && r.status < 300, status: r.status, statusText: r.statusText || '', url: r.url, redirected: !!r.redirected,
      headers: makeHeaders(r.headers),
      text: function () { return Promise.resolve(r.body); },
      json: function () { return Promise.resolve().then(function () { return JSON.parse(r.body); }); },
      arrayBuffer: function () { return Promise.resolve(new TextEncoder().encode(r.body).buffer); },
      clone: function () { return makeResponse(r); }
    };
  }
  window.__xFetchDone = function (id, r) {
    var p = pending[id]; delete pending[id];
    if (!p) return;
    if (r.error) p.rej(new TypeError('Network request failed: ' + r.error)); else p.res(makeResponse(r));
  };
  function headersToObject(h) {
    var out = {};
    if (!h) return out;
    if (Array.isArray(h)) { h.forEach(function (kv) { out[kv[0]] = kv[1]; }); return out; }
    if (typeof h.forEach === 'function' && h.constructor !== Object) { h.forEach(function (v, k) { out[k] = v; }); return out; }
    Object.keys(h).forEach(function (k) { out[k] = h[k]; });
    return out;
  }
  window.fetch = function (input, init) {
    init = init || {};
    var url = typeof input === 'string' ? input : (input && input.url) || String(input);
    var headers = headersToObject(init.headers);
    var body = init.body;
    if (body != null && typeof body !== 'string') {
      if (typeof URLSearchParams !== 'undefined' && body instanceof URLSearchParams) {
        body = body.toString();
        if (!Object.keys(headers).some(function (k) { return k.toLowerCase() === 'content-type'; })) headers['Content-Type'] = 'application/x-www-form-urlencoded';
      } else body = JSON.stringify(body);
    }
    var id = ++seq;
    return new Promise(function (res, rej) {
      pending[id] = { res: res, rej: rej };
      XNative.fetch(id, url, JSON.stringify({ method: (init.method || 'GET').toUpperCase(), headers: headers, body: body == null ? null : body, redirect: init.redirect || 'follow' }));
    });
  };

  // A small axios on top of fetch (the parts plugins use).
  function axios(cfg) {
    if (typeof cfg === 'string') cfg = { url: cfg };
    var url = cfg.url;
    if (cfg.baseURL && !/^https?:/.test(url)) url = cfg.baseURL.replace(/\/$/, '') + '/' + url.replace(/^\//, '');
    if (cfg.params) {
      var q = new URLSearchParams(cfg.params).toString();
      if (q) url += (url.indexOf('?') >= 0 ? '&' : '?') + q;
    }
    var headers = headersToObject(cfg.headers);
    var data = cfg.data;
    if (data != null && typeof data === 'object' && !(data instanceof URLSearchParams)) {
      data = JSON.stringify(data);
      if (!Object.keys(headers).some(function (k) { return k.toLowerCase() === 'content-type'; })) headers['Content-Type'] = 'application/json';
    }
    return fetch(url, { method: cfg.method || 'GET', headers: headers, body: data }).then(function (r) {
      return r.text().then(function (text) {
        var out = text;
        if (cfg.responseType !== 'text') { try { out = JSON.parse(text); } catch (e) {} }
        var hs = {}; r.headers.forEach(function (v, k) { hs[k] = v; });
        var resp = { data: out, status: r.status, statusText: r.statusText, headers: hs, config: cfg, request: { responseURL: r.url } };
        var okFn = cfg.validateStatus || function (s) { return s >= 200 && s < 300; };
        if (!okFn(r.status)) { var err = new Error('Request failed with status code ' + r.status); err.response = resp; err.config = cfg; throw err; }
        return resp;
      });
    });
  }
  axios.request = axios;
  axios.get = function (u, c) { c = c || {}; c.url = u; c.method = 'GET'; return axios(c); };
  axios.head = function (u, c) { c = c || {}; c.url = u; c.method = 'HEAD'; return axios(c); };
  axios.delete = function (u, c) { c = c || {}; c.url = u; c.method = 'DELETE'; return axios(c); };
  axios.post = function (u, d, c) { c = c || {}; c.url = u; c.method = 'POST'; c.data = d; return axios(c); };
  axios.put = function (u, d, c) { c = c || {}; c.url = u; c.method = 'PUT'; c.data = d; return axios(c); };
  axios.create = function (defaults) {
    var inst = function (cfg) { return axios(Object.assign({}, defaults || {}, typeof cfg === 'string' ? { url: cfg } : cfg)); };
    ['get', 'head', 'delete'].forEach(function (m) { inst[m] = function (u, c) { return inst(Object.assign({}, c || {}, { url: u, method: m.toUpperCase() })); }; });
    ['post', 'put'].forEach(function (m) { inst[m] = function (u, d, c) { return inst(Object.assign({}, c || {}, { url: u, method: m.toUpperCase(), data: d })); }; });
    inst.defaults = defaults || {};
    return inst;
  };
  axios.default = axios;

  window.require = function (name) {
    var L = window.__xLibs || {};
    switch (name) {
      case 'cheerio-without-node-native': case 'cheerio': case 'react-native-cheerio': return L.cheerio;
      case 'crypto-js': return L.CryptoJS;
      case 'axios': return axios;
      default: throw new Error('Module not available in Xstream: ' + name);
    }
  };

  /** Runs one plugin's getStreams(...) and reports the result to Xstream. */
  window.__xRun = function (runId, code, args) {
    try {
      var module = { exports: {} };
      var fn = new Function('module', 'exports', 'require', 'fetch', 'console', 'axios',
        code + '\n;return (typeof getStreams === "function" ? getStreams : (module.exports && (module.exports.getStreams || (module.exports.default && module.exports.default.getStreams))));');
      var gs = fn(module, module.exports, window.require, window.fetch, con, axios);
      if (typeof gs !== 'function') throw new Error('This plugin has no getStreams()');
      Promise.resolve(gs.apply(null, args)).then(function (r) {
        XNative.done(runId, JSON.stringify(Array.isArray(r) ? r : (r ? [r] : [])));
      }).catch(function (e) { XNative.fail(runId, String((e && e.stack) || e)); });
    } catch (e) { XNative.fail(runId, String((e && e.stack) || e)); }
  };
  XNative.ready();
})();
