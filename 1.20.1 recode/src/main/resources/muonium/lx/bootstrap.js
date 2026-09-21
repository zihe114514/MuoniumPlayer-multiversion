/*
 * MuoniumPlayer 的 LX Music 自定义源运行时（宿主侧引导脚本）。
 *
 * 对应 lx-music-desktop 的 src/main/modules/userApi/renderer/preload.js：那边是 Electron 的
 * contextBridge，这里是 Rhino。用 JS 写而不是全塞进 Java，有两个实际原因：
 *   1. 返回值校验、needle 的载荷规则、Promise 链这些东西照抄上游的 JS 最不容易走形；
 *   2. Java 侧只需要暴露「发请求 / 算摘要 / 解压」这几个原语，省掉一大堆 Rhino 类型转换。
 *
 * 这个文件本身不经过 Closure 转译（它是宿主代码，不是用户脚本），所以只能用 ES5 写法。
 */
(function () {
  'use strict';

  var host = globalThis.__lxHost;
  var scriptInfo = JSON.parse(globalThis.__lxScriptInfo || '{}');
  // 用完就摘掉：脚本没必要看见宿主桥，留着只会被误用成「反正能拿到 Java」。
  delete globalThis.__lxHost;
  delete globalThis.__lxScriptInfo;

  var EVENT_NAMES = { request: 'request', inited: 'inited', updateAlert: 'updateAlert' };
  var eventNames = ['request', 'inited', 'updateAlert'];
  var events = { request: null };
  var isInited = false;
  var isUpdateAlertShown = false;

  // ════════════════════════════ 宿主环境补齐 ════════════════════════════
  // 上游脚本跑在 Electron 的渲染进程里，随手就会用 console / setTimeout / window。
  // 缺一个就是一次「脚本初始化失败」，而且报错信息还落在 Java 栈里，用户根本看不懂。

  function logTo(level) {
    return function () {
      var parts = [];
      for (var i = 0; i < arguments.length; i++) {
        var value = arguments[i];
        try {
          parts.push(typeof value === 'string' ? value
            : (value instanceof Error ? (value.message || String(value)) : JSON.stringify(value)));
        } catch (circular) {
          parts.push(String(value));
        }
      }
      host.log(level, parts.join(' '));
    };
  }

  if (typeof globalThis.console === 'undefined') {
    globalThis.console = {
      log: logTo('log'), info: logTo('log'), debug: logTo('log'),
      warn: logTo('warn'), error: logTo('error'), trace: logTo('log'),
      group: logTo('log'), groupEnd: function () {}, table: logTo('log'), dir: logTo('log')
    };
  }

  globalThis.setTimeout = function (handler, delay) {
    var extra = Array.prototype.slice.call(arguments, 2);
    return host.setTimer(handler, Number(delay) || 0, false, extra);
  };
  globalThis.setInterval = function (handler, delay) {
    var extra = Array.prototype.slice.call(arguments, 2);
    return host.setTimer(handler, Number(delay) || 0, true, extra);
  };
  globalThis.clearTimeout = function (id) { host.clearTimer(id); };
  globalThis.clearInterval = function (id) { host.clearTimer(id); };
  globalThis.setImmediate = function (handler) { return host.setTimer(handler, 0, false, []); };

  globalThis.window = globalThis;
  globalThis.self = globalThis;
  globalThis.global = globalThis;
  globalThis.navigator = globalThis.navigator || { userAgent: host.userAgent(), platform: 'Win32', language: 'zh-CN' };
  globalThis.location = globalThis.location || { href: 'about:blank', protocol: 'about:', host: '', hostname: '', search: '' };
  // document 只留脚本真的会摸的那一小块：上游的参考实现在拿不到 currentScriptInfo 时会去
  // document.getElementsByTagName('script')[0].innerText 读自己的源码。
  globalThis.document = globalThis.document || {
    getElementsByTagName: function () { return [{ innerText: scriptInfo.rawScript || '', text: scriptInfo.rawScript || '' }]; },
    querySelector: function () { return null; },
    querySelectorAll: function () { return []; },
    addEventListener: function () {}, removeEventListener: function () {},
    createElement: function () { return { style: {}, setAttribute: function () {}, appendChild: function () {} }; },
    head: { appendChild: function () {} }, body: { appendChild: function () {} }
  };
  globalThis.addEventListener = function () {};
  globalThis.removeEventListener = function () {};
  globalThis.atob = function (text) { return host.bufferFrom(String(text), 'base64').toString('binary'); };
  globalThis.btoa = function (text) { return host.bufferFrom(String(text), 'binary').toString('base64'); };
  // ════════════════════════════ Buffer ════════════════════════════
  // 上游只透出 lx.utils.buffer 两个方法，没有全局 Buffer。这里额外给一个全局的：
  // 不少「转发到自建 API」的脚本顺手写了 Buffer.from(x).toString('base64')，
  // 多给一个只会让更多脚本能跑，不会让任何脚本行为变样。
  var bufferApi = {
    from: function (value, encoding) { return host.bufferFrom(value, encoding || 'utf8'); },
    alloc: function (size) { return host.bufferAlloc(Number(size) || 0); },
    concat: function (list) { return host.bufferConcat(list); },
    isBuffer: function (value) { return host.isBuffer(value); },
    byteLength: function (value, encoding) { return host.bufferFrom(value, encoding || 'utf8').length; }
  };
  globalThis.Buffer = bufferApi;

  // ════════════════════════════ HTTP ════════════════════════════

  function queryString(data) {
    var parts = [];
    for (var key in data) {
      if (!Object.prototype.hasOwnProperty.call(data, key)) continue;
      var value = data[key];
      if (value === null || value === undefined) value = '';
      if (Object.prototype.toString.call(value) === '[object Array]') {
        for (var i = 0; i < value.length; i++) {
          parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(value[i]));
        }
      } else {
        parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(value));
      }
    }
    return parts.join('&');
  }

  function hasHeader(headers, name) {
    for (var key in headers) {
      if (Object.prototype.hasOwnProperty.call(headers, key) && key.toLowerCase() === name) return true;
    }
    return false;
  }

  /**
   * 载荷规则照抄 needle（上游用的就是它）：
   *   字符串 / Buffer 原样发；对象在 options.json 为真时发 JSON，否则发 form-urlencoded。
   * 这也是为什么 form 与 formData 在上游其实是同一种编码 —— needle 只在 multipart 选项
   * 被显式打开时才发 multipart，而上游从没打开过。照抄这一点，脚本才不会在两个播放器里
   * 得到不同的请求体。
   */
  function buildPayload(options, headers) {
    var data = null;
    var forceForm = false;
    if (options.body !== undefined && options.body !== null) {
      data = options.body;
    } else if (options.form !== undefined && options.form !== null) {
      data = options.form;
      forceForm = true;
    } else if (options.formData !== undefined && options.formData !== null) {
      data = options.formData;
      forceForm = true;
    }
    if (data === null || data === undefined) return null;
    if (typeof data === 'string') return data;
    if (host.isBuffer(data)) return data;

    var asJson = !forceForm && options.json === true;
    if (asJson) {
      if (!hasHeader(headers, 'content-type')) headers['content-type'] = 'application/json';
      return JSON.stringify(data);
    }
    if (!hasHeader(headers, 'content-type')) {
      headers['content-type'] = 'application/x-www-form-urlencoded';
    }
    return queryString(data);
  }

  function normalizeHeaders(source) {
    var headers = {};
    if (!source) return headers;
    for (var key in source) {
      if (!Object.prototype.hasOwnProperty.call(source, key)) continue;
      var value = source[key];
      if (value === null || value === undefined) continue;
      headers[key] = Object.prototype.toString.call(value) === '[object Array]' ? value.join(', ') : String(value);
    }
    return headers;
  }

  function request(url, options, callback) {
    var settings = options || {};
    var handler = typeof callback === 'function' ? callback : function () {};
    var method = String(settings.method || 'get').toUpperCase();
    var headers = normalizeHeaders(settings.headers);
    var payload = null;
    try {
      payload = buildPayload(settings, headers);
    } catch (badPayload) {
      host.setTimer(function () { handler.call(globalThis, badPayload, null, null); }, 0, false, []);
      return function () {};
    }
    if (payload !== null && (method === 'GET' || method === 'HEAD')) {
      // HttpURLConnection 一旦为 GET 打开输出流就会被悄悄改写成 POST，那比丢掉请求体更难查。
      console.warn('[lx.request] ' + method + ' 请求带了请求体，已忽略：' + url);
      payload = null;
    }
    var id = host.httpSend(url, method, headers, payload, Number(settings.timeout) || 0, handler);
    return function () { host.httpAbort(id); };
  }

  /** Java 侧拿到响应后回调这里，再由这里拼出脚本看到的 resp 对象。 */
  globalThis.__lxOnHttpDone = function (handler, error, status, statusMessage, headersJson, raw) {
    if (error) {
      handler.call(globalThis, new Error(error), null, null);
      return;
    }
    var headers = {};
    try { headers = JSON.parse(headersJson); } catch (badHeaders) { headers = {}; }
    var text = raw.toString('utf8');
    var body = text;
    try { body = JSON.parse(text); } catch (notJson) { body = text; }
    handler.call(globalThis, null, {
      statusCode: status,
      statusMessage: statusMessage,
      headers: headers,
      bytes: raw.length,
      raw: raw,
      body: body
    }, body);
  };
  // ════════════════════════════ inited / updateAlert ════════════════════════════
  // 源 key、每个源允许的 action、每个源允许的音质，三张表都必须和上游一字不差：
  // 脚本声明支持什么不算数，最终能用什么是宿主按这三张表过滤出来的。
  var ALL_SOURCES = ['kw', 'kg', 'tx', 'wy', 'mg', 'local'];
  var SUPPORT_QUALITYS = {
    kw: ['128k', '320k', 'flac', 'flac24bit'],
    kg: ['128k', '320k', 'flac', 'flac24bit'],
    tx: ['128k', '320k', 'flac', 'flac24bit'],
    wy: ['128k', '320k', 'flac', 'flac24bit'],
    mg: ['128k', '320k', 'flac', 'flac24bit'],
    local: []
  };
  var SUPPORT_ACTIONS = {
    kw: ['musicUrl'], kg: ['musicUrl'], tx: ['musicUrl'], wy: ['musicUrl'], mg: ['musicUrl'],
    local: ['musicUrl', 'lyric', 'pic']
  };
  // 宿主扩展开关：__lxExtraActions 为 true 时，非 local 的源也放开 lyric / pic / search。
  // 默认 false，此时这张表与上游 preload.js 的 supportActions 一字不差 —— 上游只给
  // local 这两个动作（lyric / pic），而 local 在洛雪里指本机文件，MuoniumPlayer 的曲目
  // 全来自远端平台，照抄就等于它们永远用不上。search 更是上游没有的动作：那边的搜索
  // 走内置 musicSdk（store/search/music/action.ts 里调 music[source].musicSearch.search），
  // userApi 从头到尾只有 musicUrl / lyric / pic 三个动作，所以只能宿主自己加。
  // 放宽的只是「宿主肯不肯问」：脚本没声明的动作照样会被下面那个 includes 过滤掉。
  var EXTRA_ACTIONS = globalThis.__lxExtraActions === true;
  var EXTRA_ACTION_LIST = ['musicUrl', 'lyric', 'pic', 'search'];

  function allowedActions(source) {
    if (EXTRA_ACTIONS && source !== 'local') return EXTRA_ACTION_LIST;
    return SUPPORT_ACTIONS[source];
  }

  function includes(list, value) {
    if (!list || typeof list.length !== 'number') return false;
    for (var i = 0; i < list.length; i++) {
      if (list[i] === value) return true;
    }
    return false;
  }

  function handleInit(info) {
    if (!info) {
      host.onInitFailed('Missing required parameter init info');
      return;
    }
    if (info.openDevTools === true) {
      console.warn('[lx] openDevTools 在 MuoniumPlayer 里没有对应实现，已忽略');
    }
    var sources = {};
    try {
      for (var i = 0; i < ALL_SOURCES.length; i++) {
        var source = ALL_SOURCES[i];
        var declared = info.sources[source];
        if (!declared || declared.type !== 'music') continue;
        var allowed = allowedActions(source);
        var actions = [];
        for (var a = 0; a < allowed.length; a++) {
          if (includes(declared.actions, allowed[a])) actions.push(allowed[a]);
        }
        var qualitys = [];
        for (var q = 0; q < SUPPORT_QUALITYS[source].length; q++) {
          if (includes(declared.qualitys, SUPPORT_QUALITYS[source][q])) qualitys.push(SUPPORT_QUALITYS[source][q]);
        }
        sources[source] = {
          name: typeof declared.name === 'string' ? declared.name.substring(0, 48) : source,
          type: 'music',
          actions: actions,
          qualitys: qualitys
        };
      }
    } catch (badSources) {
      host.onInitFailed(badSources && badSources.message ? badSources.message : String(badSources));
      return;
    }
    host.onInited(JSON.stringify({ sources: sources }));
  }

  function handleUpdateAlert(data, resolve, reject) {
    if (!data || typeof data !== 'object') return reject(new Error('parameter format error.'));
    if (!data.log || typeof data.log !== 'string') return reject(new Error('log is required.'));
    var log = data.log.length > 1024 ? data.log.substring(0, 1024) + '...' : data.log;
    var updateUrl = typeof data.updateUrl === 'string' && data.updateUrl.length <= 1024
      && /^https?:\/\/[^\s$.?#].[^\s]*$/.test(data.updateUrl) ? data.updateUrl : '';
    host.onUpdateAlert(log, updateUrl);
    resolve();
  }

  // ════════════════════════════ 请求分发 ════════════════════════════
  // 返回值校验与上游 handleRequest 逐条对齐：长度上限、必须是 http(s)、歌词各字段的裁剪规则。
  // 这些限制不是防御性代码，而是协议的一部分 —— 脚本作者靠它们判断自己返回得对不对。

  function verifyLyric(info) {
    if (typeof info !== 'object' || info === null || typeof info.lyric !== 'string') throw new Error('failed');
    if (info.lyric.length > 51200) throw new Error('failed');
    return {
      lyric: info.lyric,
      tlyric: (typeof info.tlyric === 'string' && info.tlyric.length < 5120) ? info.tlyric : null,
      rlyric: (typeof info.rlyric === 'string' && info.rlyric.length < 5120) ? info.rlyric : null,
      lxlyric: (typeof info.lxlyric === 'string' && info.lxlyric.length < 8192) ? info.lxlyric : null
    };
  }

  function verifyUrl(response) {
    if (typeof response !== 'string' || response.length > 2048 || !/^https?:/.test(response)) throw new Error('failed');
    return response;
  }

  // search 是宿主自己加的动作（上游 userApi 只有 musicUrl / lyric / pic）。
  // 载荷形状照抄内置 musicSdk 的返回值 —— 脚本作者按 lx 那份 musicSearch.js 写就行，
  // 不需要再学一套新东西：{ list: [{name, singer, source, songmid, albumId, albumName,
  // interval, img, ...}], allPage, total, limit }。
  function clip(text, max) {
    text = String(text);
    return text.length > max ? text.substring(0, max) : text;
  }

  function optionalText(value, max) {
    return value === null || value === undefined ? '' : clip(value, max);
  }

  function positiveInt(value, fallback) {
    var num = Number(value);
    return isFinite(num) && num > 0 ? Math.min(Math.floor(num), 100000) : fallback;
  }

  function verifySearch(info) {
    if (!info || typeof info !== 'object' || !Array.isArray(info.list)) throw new Error('failed');
    var list = [];
    for (var i = 0; i < info.list.length && list.length < 200; i++) {
      var item = info.list[i];
      if (!item || typeof item.name !== 'string' || !item.name) continue;
      list.push({
        name: clip(item.name, 200),
        singer: optionalText(item.singer, 200),
        // 源 key 以宿主问的那个为准：脚本在这里写错或写别的源，只会让宿主把结果丢回去
        // 按内置映射找平台，白白浪费一条命。
        source: optionalText(item.source, 12),
        songmid: optionalText(item.songmid, 64),
        albumId: optionalText(item.albumId, 64),
        albumName: optionalText(item.albumName, 200),
        // interval 是 "mm:ss"（与内置 musicSearch 一致），不是毫秒数。
        interval: optionalText(item.interval, 12),
        img: (typeof item.img === 'string' && /^https?:/.test(item.img) && item.img.length <= 2048)
          ? item.img : '',
        // 三个平台的私有主键：酷狗看 hash、QQ 看 strMediaMid、咪咕看 copyrightId。
        hash: optionalText(item.hash, 64),
        strMediaMid: optionalText(item.strMediaMid, 64),
        copyrightId: optionalText(item.copyrightId, 64)
      });
    }
    if (!list.length) throw new Error('failed');
    var limit = positiveInt(info.limit, list.length);
    return {
      list: list,
      allPage: positiveInt(info.allPage, 1),
      total: positiveInt(info.total, list.length),
      limit: limit
    };
  }

  globalThis.__lxDispatch = function (requestKey, source, action, infoJson) {
    if (!events.request) {
      host.onResponse(requestKey, false, 'Request event is not defined');
      return;
    }
    var info;
    try {
      info = JSON.parse(infoJson);
    } catch (badInfo) {
      host.onResponse(requestKey, false, 'bad request info');
      return;
    }
    try {
      Promise.resolve(events.request.call(globalThis, { source: source, action: action, info: info }))
        .then(function (response) {
          var payload;
          switch (action) {
            case 'musicUrl':
              payload = JSON.stringify({ type: info.type, url: verifyUrl(response) });
              break;
            case 'lyric':
              payload = JSON.stringify(verifyLyric(response));
              break;
            case 'pic':
              payload = JSON.stringify({ url: verifyUrl(response) });
              break;
            case 'search':
              payload = JSON.stringify(verifySearch(response));
              break;
            default:
              throw new Error('unsupported action: ' + action);
          }
          host.onResponse(requestKey, true, payload);
        }, function (error) {
          host.onResponse(requestKey, false, error && error.message ? error.message : String(error));
        })
        .then(null, function (error) {
          // 校验函数自己抛出来的（返回值不合规），上面那个 onRejected 接不到。
          host.onResponse(requestKey, false, error && error.message ? error.message : String(error));
        });
    } catch (thrown) {
      host.onResponse(requestKey, false, thrown && thrown.message ? thrown.message : String(thrown));
    }
  };
  // ════════════════════════════ globalThis.lx ════════════════════════════

  globalThis.lx = {
    EVENT_NAMES: EVENT_NAMES,
    // 版本号照报 2.0.0：脚本用它区分「新版 API」与更早的那套（旧版没有 currentScriptInfo，
    // 也要求 aesEncrypt 的 mode 只传 cbc/ecb 而不是 aes-128-cbc）。报别的数会走进死路径。
    version: '2.0.0',
    env: 'desktop',
    currentScriptInfo: {
      name: scriptInfo.name || '',
      description: scriptInfo.description || '',
      version: scriptInfo.version || '',
      author: scriptInfo.author || '',
      homepage: scriptInfo.homepage || '',
      rawScript: scriptInfo.rawScript || ''
    },
    request: request,
    send: function (eventName, data) {
      return new Promise(function (resolve, reject) {
        if (!includes(eventNames, eventName)) return reject(new Error('The event is not supported: ' + eventName));
        switch (eventName) {
          case EVENT_NAMES.inited:
            if (isInited) return reject(new Error('Script is inited'));
            isInited = true;
            handleInit(data);
            resolve();
            break;
          case EVENT_NAMES.updateAlert:
            if (isUpdateAlertShown) return reject(new Error('The update alert can only be called once.'));
            isUpdateAlertShown = true;
            handleUpdateAlert(data, resolve, reject);
            break;
          default:
            reject(new Error('Unknown event name: ' + eventName));
        }
      });
    },
    on: function (eventName, handler) {
      if (!includes(eventNames, eventName)) return Promise.reject(new Error('The event is not supported: ' + eventName));
      if (eventName !== EVENT_NAMES.request) return Promise.reject(new Error('The event is not supported: ' + eventName));
      events.request = handler;
      return Promise.resolve();
    },
    utils: {
      buffer: {
        from: function (value, encoding) { return host.bufferFrom(value, encoding || 'utf8'); },
        // 上游是 Buffer.from(buf, 'binary').toString(format)：入参已经是 Buffer 时那一步只是复制。
        bufToString: function (buf, format) { return host.bufferFrom(buf, 'binary').toString(format || 'utf8'); }
      },
      crypto: {
        aesEncrypt: function (buffer, mode, key, iv) { return host.aesEncrypt(buffer, mode, key, iv); },
        rsaEncrypt: function (buffer, key) { return host.rsaEncrypt(buffer, key); },
        randomBytes: function (size) { return host.randomBytes(Number(size) || 0); },
        md5: function (str) { return host.md5(String(str)); }
      },
      zlib: {
        // 上游是 Node 的异步 zlib，签名是 Promise<Buffer>。这边解压本身是同步的，
        // 但签名必须一样：脚本写的是 await lx.utils.zlib.inflate(buf)。
        inflate: function (buf) {
          return new Promise(function (resolve, reject) {
            try { resolve(host.inflate(buf)); } catch (broken) { reject(new Error(String(broken && broken.message || broken))); }
          });
        },
        deflate: function (buf) {
          return new Promise(function (resolve, reject) {
            try { resolve(host.deflate(buf)); } catch (broken) { reject(new Error(String(broken && broken.message || broken))); }
          });
        }
      }
    }
  };
})();