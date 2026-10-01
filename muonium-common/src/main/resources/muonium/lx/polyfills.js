/*
 * MuoniumPlayer 的 LX 自定义源「现代 JS 垫片」（宿主侧，先于引导脚本装载）。
 *
 * 为什么需要它：用户脚本的语法（const/let、箭头函数、class、async/await、可选链…）由
 * LxTranspiler 里的 Closure 统一降级到 ES5，Rhino 吃得下；但 Closure 只做「语法」这一半，
 * 不负责补「运行期 built-in」。而本 mod 的引擎是 Rhino 1.7.15，它的标准库停在 ES2019 前后，
 * 少了 Promise.any / AggregateError / String.prototype.matchAll / Array.prototype.findLast
 * 这些近几年的新面孔。上游脚本照着 Electron/Chrome 写，随手就会用 —— 现象是脚本一句
 * `Promise.any([...])` 就把初始化打死：`TypeError: Cannot find function any in object function Promise()`。
 * 这正是「野花」上游把版本从 1.0.0 升到 1（改用 Promise.any）后加载报错的原因。
 *
 * 设计约束：
 *   - 只用 ES5 写法（这个文件不经 Closure 转译，得能被 Rhino 原样解析）。
 *   - 每一项都做特性检测：原生已经有的，绝不覆盖。这样对本来就能跑的脚本是零改动、零风险，
 *     只把「缺的那块」补上。
 *   - 语义对齐规范，够脚本用即可；不追求跨 realm 的角例（自定义源里没有那种用法）。
 *   - 装载失败必须不影响脚本本身 —— 由 Java 侧包 try 兜住，这里不抛。
 */
(function (global) {
  'use strict';

  var hasOwnProperty = Object.prototype.hasOwnProperty;
  function define(target, key, value, enumerable) {
    if (!hasOwnProperty.call(target, key)) {
      Object.defineProperty(target, key, {
        value: value, writable: true, configurable: true,
        enumerable: !!enumerable
      });
    }
  }

  // ── AggregateError（ES2021，Promise.any 全失败时抛的就是它） ──────────────
  if (typeof global.AggregateError !== 'function') {
    var AggregateError = function AggregateError(errors, message) {
      if (!(this instanceof AggregateError)) return new AggregateError(errors, message);
      var base = new Error(message === undefined ? '' : String(message));
      this.message = base.message;
      if (base.stack !== undefined) this.stack = base.stack;
      this.errors = errors ? Array.prototype.slice.call(errors) : [];
      this.name = 'AggregateError';
    };
    AggregateError.prototype = Object.create(Error.prototype);
    AggregateError.prototype.constructor = AggregateError;
    define(global, 'AggregateError', AggregateError);
  }

  // ── Promise.allSettled（ES2015 之后普及；多数 Rhino 已带，缺则补） ─────────
  if (typeof global.Promise === 'function') {
    if (typeof Promise.allSettled !== 'function') {
      define(Promise, 'allSettled', function allSettled(iterable) {
        var values = toArray(iterable);
        return new Promise(function (resolve) {
          if (!values.length) return resolve([]);
          var pending = values.length;
          var out = new Array(values.length);
          for (var i = 0; i < values.length; i++) {
            (function (index) {
              Promise.resolve(values[index]).then(
                function (value) { out[index] = { status: 'fulfilled', value: value }; if (--pending === 0) resolve(out); },
                function (reason) { out[index] = { status: 'rejected', reason: reason }; if (--pending === 0) resolve(out); }
              );
            })(i);
          }
        });
      });
    }

    // ── Promise.any（ES2021：第一个「成功」的结果；全失败则 reject AggregateError） ──
    if (typeof Promise.any !== 'function') {
      define(Promise, 'any', function any(iterable) {
        var values = toArray(iterable);
        return new Promise(function (resolve, reject) {
          if (!values.length) { reject(new global.AggregateError([], 'All promises were rejected')); return; }
          var errors = new Array(values.length);
          var rejected = 0;
          for (var i = 0; i < values.length; i++) {
            (function (index) {
              Promise.resolve(values[index]).then(resolve, function (reason) {
                errors[index] = reason;
                if (++rejected === values.length) reject(new global.AggregateError(errors, 'All promises were rejected'));
              });
            })(i);
          }
        });
      });
    }

    // Promise.prototype.finally（ES2018）
    if (typeof Promise.prototype.finally !== 'function') {
      define(Promise.prototype, 'finally', function (onFinally) {
        var C = this.constructor || Promise;
        return this.then(
          function (value) { return C.resolve(onFinally).then(function () { return value; }); },
          function (reason) { return C.resolve(onFinally).then(function () { throw reason; }); }
        );
      });
    }
  }

  // ── Array 静态与实例方法 ──────────────────────────────────────────────────
  if (typeof Array.from !== 'function') {
    define(Array, 'from', function from(arrayLike) {
      var out = [];
      if (arrayLike == null) return out;
      if (typeof arrayLike.length === 'number') {
        for (var i = 0; i < arrayLike.length; i++) out.push(arrayLike[i]);
        return out;
      }
      var it = arrayLike[Symbol.iterator] && arrayLike[Symbol.iterator]();
      if (it) { var step; while (!(step = it.next()).done) out.push(step.value); }
      return out;
    });
  }
  if (typeof Array.of !== 'function') {
    define(Array, 'of', function of() { return Array.prototype.slice.call(arguments); });
  }
  if (typeof Array.prototype.includes !== 'function') {
    define(Array.prototype, 'includes', function includes(searchElement, fromIndex) {
      var len = this.length >>> 0;
      var start = Math.max((fromIndex | 0) || 0, 0);
      if (start >= len) return false;
      for (var i = start; i < len; i++) {
        var v = this[i];
        if (v === searchElement || (v !== v && searchElement !== searchElement)) return true;
      }
      return false;
    });
  }
  if (typeof Array.prototype.flat !== 'function') {
    define(Array.prototype, 'flat', function flat(depth) {
      var d = depth === undefined ? 1 : Math.max(0, depth | 0);
      var out = [];
      (function walk(arr, level) {
        for (var i = 0; i < arr.length; i++) {
          var el = arr[i];
          if (Array.isArray(el) && level > 0) walk(el, level - 1);
          else out.push(el);
        }
      })(this, d);
      return out;
    });
  }
  if (typeof Array.prototype.flatMap !== 'function') {
    define(Array.prototype, 'flatMap', function flatMap(cb, thisArg) {
      var mapped = [];
      for (var i = 0; i < this.length; i++) mapped.push(cb.call(thisArg, this[i], i, this));
      return mapped.flat(1);
    });
  }
  if (typeof Array.prototype.at !== 'function') {
    define(Array.prototype, 'at', function at(index) {
      var i = Math.trunc(index) || 0;
      if (i < 0) i += this.length;
      return (i < 0 || i >= this.length) ? undefined : this[i];
    });
  }
  if (typeof Array.prototype.findLast !== 'function') {
    define(Array.prototype, 'findLast', function findLast(cb, thisArg) {
      for (var i = this.length - 1; i >= 0; i--) if (cb.call(thisArg, this[i], i, this)) return this[i];
      return undefined;
    });
  }
  if (typeof Array.prototype.findLastIndex !== 'function') {
    define(Array.prototype, 'findLastIndex', function findLastIndex(cb, thisArg) {
      for (var i = this.length - 1; i >= 0; i--) if (cb.call(thisArg, this[i], i, this)) return i;
      return -1;
    });
  }

  // ── Object 静态方法 ───────────────────────────────────────────────────────
  if (typeof Object.assign !== 'function') {
    define(Object, 'assign', function assign(target) {
      if (target == null) throw new TypeError('Cannot convert undefined or null to object');
      var to = Object(target);
      for (var i = 1; i < arguments.length; i++) {
        var src = arguments[i];
        if (src == null) continue;
        src = Object(src);
        for (var key in src) if (hasOwnProperty.call(src, key)) to[key] = src[key];
      }
      return to;
    });
  }
  if (typeof Object.entries !== 'function') {
    define(Object, 'entries', function entries(obj) {
      var out = [];
      obj = Object(obj);
      for (var k in obj) if (hasOwnProperty.call(obj, k)) out.push([k, obj[k]]);
      return out;
    });
  }
  if (typeof Object.values !== 'function') {
    define(Object, 'values', function values(obj) {
      var out = [];
      obj = Object(obj);
      for (var k in obj) if (hasOwnProperty.call(obj, k)) out.push(obj[k]);
      return out;
    });
  }
  if (typeof Object.fromEntries !== 'function') {
    define(Object, 'fromEntries', function fromEntries(iterable) {
      var out = {};
      var pairs = toArray(iterable);
      for (var i = 0; i < pairs.length; i++) {
        var pair = pairs[i];
        out[pair[0]] = pair[1];
      }
      return out;
    });
  }
  if (typeof Object.hasOwn !== 'function') {
    define(Object, 'hasOwn', function hasOwn(obj, key) {
      return hasOwnProperty.call(Object(obj), key);
    });
  }

  // ── String 实例方法 ───────────────────────────────────────────────────────
  if (typeof String.prototype.padStart !== 'function') {
    define(String.prototype, 'padStart', function padStart(len, pad) {
      return padGeneric(this, len, pad === undefined ? ' ' : String(pad), true);
    });
  }
  if (typeof String.prototype.padEnd !== 'function') {
    define(String.prototype, 'padEnd', function padEnd(len, pad) {
      return padGeneric(this, len, pad === undefined ? ' ' : String(pad), false);
    });
  }
  if (typeof String.prototype.includes !== 'function') {
    define(String.prototype, 'includes', function includes(search, fromIndex) {
      return String(this).indexOf(search, (fromIndex | 0) || 0) !== -1;
    });
  }
  if (typeof String.prototype.at !== 'function') {
    define(String.prototype, 'at', function at(index) {
      var s = String(this);
      var i = Math.trunc(index) || 0;
      if (i < 0) i += s.length;
      return (i < 0 || i >= s.length) ? undefined : s.charAt(i);
    });
  }
  if (typeof String.prototype.replaceAll !== 'function') {
    define(String.prototype, 'replaceAll', function replaceAll(search, replace) {
      if (search instanceof RegExp) {
        if (!search.global) throw new TypeError('replaceAll must be called with a global RegExp');
        return this.replace(search, replace);
      }
      return String(this).split(search).join(replace);
    });
  }
  if (typeof String.prototype.matchAll !== 'function') {
    define(String.prototype, 'matchAll', function matchAll(regexp) {
      var re;
      if (regexp instanceof RegExp) {
        re = regexp;
        if (!re.global) throw new TypeError('String.prototype.matchAll called with a non-global RegExp argument');
      } else {
        re = new RegExp(regexp, 'g');
      }
      var str = String(this);
      re = new RegExp(re.source, re.flags);
      re.lastIndex = 0;
      var results = [];
      var m;
      while ((m = re.exec(str)) !== null) {
        results.push(m);
        if (m.index === re.lastIndex) re.lastIndex++;
      }
      var idx = 0;
      var iterator = {
        next: function () {
          return idx < results.length
            ? { value: results[idx++], done: false }
            : { value: undefined, done: true };
        }
      };
      iterator[Symbol.iterator] = function () { return iterator; };
      return iterator;
    });
  }

  // ── Number 静态方法 ───────────────────────────────────────────────────────
  if (typeof Number.isNaN !== 'function') {
    define(Number, 'isNaN', function isNaN(value) { return typeof value === 'number' && value !== value; });
  }
  if (typeof Number.isFinite !== 'function') {
    define(Number, 'isFinite', function isFinite(value) { return typeof value === 'number' && value === value && value !== Infinity && value !== -Infinity; });
  }
  if (typeof Number.isInteger !== 'function') {
    define(Number, 'isInteger', function isInteger(value) { return typeof value === 'number' && isFinite(value) && Math.floor(value) === value; });
  }

  // ── 小工具 ────────────────────────────────────────────────────────────────
  function toArray(iterable) {
    if (iterable == null) return [];
    if (Array.isArray(iterable)) return iterable.slice();
    if (typeof iterable.length === 'number') return Array.prototype.slice.call(iterable);
    if (typeof Array.from === 'function' && iterable[Symbol.iterator]) return Array.from(iterable);
    return Array.prototype.slice.call(iterable);
  }

  function padGeneric(self, targetLength, padString, start) {
    var str = String(self);
    targetLength = targetLength >> 0;
    if (str.length >= targetLength || padString === '') return str;
    var repeat = targetLength - str.length;
    var filler = '';
    while (filler.length < repeat) filler += padString;
    filler = filler.slice(0, repeat);
    return start ? (filler + str) : (str + filler);
  }
})(typeof globalThis !== 'undefined' ? globalThis : this);
