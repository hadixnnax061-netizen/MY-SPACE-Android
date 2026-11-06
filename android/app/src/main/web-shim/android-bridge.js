/* MY SPACE · Android bridge
 * Injected at build time into the packaged copy of index.html only.
 * It keeps the original web app untouched: every call to /api/* is routed to
 * the native GeminiBridge, which talks to Google directly and stores the API
 * key in the Android Keystore instead of a local Python server.
 */
(function () {
  'use strict';

  var NATIVE = window.MYSPACE_NATIVE;
  if (!NATIVE) return;

  window.__MYSPACE_ANDROID__ = true;
  console.log('MYSPACE_BRIDGE_READY');

  /* Assets are baked into the APK; a stale service-worker cache would hide
     updates shipped in a new APK, so registration is disabled here. */
  try {
    if (navigator.serviceWorker) {
      navigator.serviceWorker.register = function () {
        return Promise.reject(new Error('service worker disabled inside APK'));
      };
    }
  } catch (e) {}

  var seq = 0;
  var pending = {};

  window.__myspaceNativeResolve = function (id, raw) {
    var resolve = pending[id];
    if (!resolve) return;
    delete pending[id];
    resolve(raw);
  };

  function errorEnvelope(message, status) {
    return JSON.stringify({
      status: status || 503,
      body: { error: String(message || 'تعذّر الاتصال بالجسر الأصلي.') }
    });
  }

  function call(method, payload) {
    return new Promise(function (resolve) {
      var id = 'r' + (++seq) + '_' + Date.now();
      var timer = setTimeout(function () {
        if (!pending[id]) return;
        delete pending[id];
        resolve(errorEnvelope('انتهت مهلة الاتصال. تحقق من الإنترنت ثم أعد المحاولة.', 504));
      }, 125000);
      pending[id] = function (raw) {
        clearTimeout(timer);
        resolve(raw);
      };
      try {
        NATIVE[method](id, payload == null ? '' : String(payload));
      } catch (e) {
        clearTimeout(timer);
        delete pending[id];
        resolve(errorEnvelope(e && e.message));
      }
    });
  }

  function toResponse(raw) {
    var env;
    try {
      env = JSON.parse(raw);
    } catch (e) {
      env = { status: 502, body: { error: 'استجابة غير صالحة من التطبيق.' } };
    }
    var status = Number(env && env.status) || 200;
    if (status < 200 || status > 599) status = 502;
    var body = env && env.body != null ? env.body : {};
    return new Response(JSON.stringify(body), {
      status: status,
      headers: {
        'Content-Type': 'application/json; charset=utf-8',
        'Cache-Control': 'no-store'
      }
    });
  }

  window.__myspaceSetDailyReminder = function (payload) {
    return call('setDailyReminder', JSON.stringify(payload || {})).then(toResponse);
  };

  var nativeFetch = window.fetch ? window.fetch.bind(window) : null;

  window.fetch = function (input, init) {
    var path = null;
    try {
      var url = typeof input === 'string' ? input : (input && input.url) || '';
      var parsed = new URL(url, location.href);
      if (parsed.origin === location.origin && parsed.pathname.indexOf('/api/') === 0) {
        path = parsed.pathname;
      }
    } catch (e) {}

    if (path) {
      var payload = init && init.body ? String(init.body) : '';
      var method = null;
      if (path === '/api/status') method = 'status';
      else if (path === '/api/config/key') method = 'saveKey';
      else if (path === '/api/test') method = 'test';
      else if (path === '/api/chat') method = 'chat';
      else if (path === '/api/speech') method = 'speech';

      if (method) return call(method, payload).then(toResponse);
      return Promise.resolve(toResponse(errorEnvelope('مسار API غير معروف.', 404)));
    }

    return nativeFetch
      ? nativeFetch(input, init)
      : Promise.reject(new Error('fetch is unavailable'));
  };

  /* The web UI exports a JSON backup with <a download href="blob:…">. WebView
     has no download manager for blob URLs, so hand the bytes to the native
     Storage Access Framework picker instead. */
  var anchorClick = HTMLAnchorElement.prototype.click;
  HTMLAnchorElement.prototype.click = function () {
    var self = this;
    var href = self.getAttribute('href') || '';
    if (!self.download || href.indexOf('blob:') !== 0) {
      return anchorClick.apply(self, arguments);
    }
    fetch(href)
      .then(function (response) { return response.blob(); })
      .then(function (blob) {
        var reader = new FileReader();
        reader.onload = function () {
          var result = String(reader.result || '');
          var comma = result.indexOf(',');
          if (comma < 0) {
            anchorClick.call(self);
            return;
          }
          try {
            NATIVE.saveBlob(self.download, blob.type || 'application/octet-stream', result.slice(comma + 1));
          } catch (e) {
            anchorClick.call(self);
          }
        };
        reader.onerror = function () { anchorClick.call(self); };
        reader.readAsDataURL(blob);
      })
      .catch(function () { anchorClick.call(self); });
    return undefined;
  };

  /* navigator.clipboard is unreliable in WebView; route copies to Android. */
  try {
    var writeText = function (text) {
      try {
        NATIVE.copyText(String(text == null ? '' : text));
        return Promise.resolve();
      } catch (e) {
        return Promise.reject(e);
      }
    };
    if (navigator.clipboard) {
      navigator.clipboard.writeText = writeText;
    } else {
      Object.defineProperty(navigator, 'clipboard', {
        value: { writeText: writeText },
        configurable: true
      });
    }
  } catch (e) {}

  /* target="_blank" links (e.g. Google AI Studio) must open in the real
     browser, not inside the WebView. */
  document.addEventListener('click', function (event) {
    var anchor = event.target && event.target.closest
      ? event.target.closest('a[href]')
      : null;
    if (!anchor) return;
    var href = anchor.getAttribute('href') || '';
    if (!/^https?:\/\//i.test(href)) return;
    try {
      var parsed = new URL(href, location.href);
      if (parsed.host !== location.host) {
        event.preventDefault();
        NATIVE.openUrl(parsed.toString());
      }
    } catch (e) {}
  }, true);

  /* The packaged app initialises its data layer asynchronously. On some older
     WebView builds that promise can stall (IndexedDB) and leave the shell with
     only the star background. Re-render once the script has had time to load. */
  window.addEventListener('error', function (event) {
    console.error('MYSPACE_WINDOW_ERROR', event.message,
      event.filename, event.lineno);
  });
  window.addEventListener('unhandledrejection', function (event) {
    var reason = event.reason;
    console.error('MYSPACE_UNHANDLED_REJECTION',
      reason && (reason.stack || reason.message || String(reason)));
  });

  function forceInitialRender(attempt) {
    try {
      var nav = document.getElementById('nav');
      if (!nav || nav.children.length > 0) return;
      if (typeof render !== 'function') return;
      console.log('MYSPACE_FORCE_RENDER attempt=' + attempt);
      if (typeof ensureWidgets === 'function') ensureWidgets();
      if (typeof applyUIPrefs === 'function') applyUIPrefs();
      render();
    } catch (e) {
      console.error('MYSPACE_FORCE_RENDER_FAILED',
        e && (e.stack || e.message || String(e)));
    }
  }
  setTimeout(function () { forceInitialRender(1); }, 2000);
  setTimeout(function () { forceInitialRender(2); }, 5000);

  /* A tap focuses the interactive canvas and Android draws its native focus
     highlight (a gold frame) around the whole hero. Drop focus after touch so
     the cosmos stays clean; keyboard focus remains available. */
  document.addEventListener('pointerup', function (event) {
    var target = event.target;
    if (target && target.classList && target.classList.contains('interactive-3d')) {
      setTimeout(function () {
        try { target.blur(); } catch (e) {}
      }, 80);
    }
  }, true);

  /* Native passes the system navigation-bar inset (in CSS pixels). The page
     pads the bottom nav itself so its background reaches the screen edge. */
  window.__myspaceInsets = function (bottom) {
    var value = Math.max(0, Number(bottom) || 0);
    try {
      document.documentElement.style.setProperty('--android-nav-bottom', value + 'px');
    } catch (e) {}
  };

  /* Keep the native catalog in sync without tying every widget to the active body. */
  function syncPlanetWidget() {
    try {
      if (typeof S === 'undefined' || !Array.isArray(S.stars) || !S.stars.length
          || typeof avg !== 'function' || typeof eff !== 'function') return;
      var active = (typeof cur === 'function' && cur()) || S.stars[0];
      var defaultId = String(S.widgetStarId || (active && active.id) || S.stars[0].id).slice(0, 80);
      var selected = S.stars.find(function (body) { return String(body.id) === defaultId; });
      var source = S.stars.slice(0, 64);
      if (selected && !source.some(function (body) { return body && String(body.id) === defaultId; })) {
        if (source.length >= 64) source[63] = selected; else source.push(selected);
      }
      var entries = source.map(function (body) {
        if (!body || !body.id) return null;
        var look = eff(body);
        if (!look || !look.c1) return null;
        return {
          id: String(body.id).slice(0, 80),
          name: String(body.name || 'MY SPACE').slice(0, 60),
          kind: body.kind === 'star' ? 'star' : 'planet',
          shape: String(look.shape || 'sphere').slice(0, 32),
          pct: Math.max(0, Math.min(100, Math.round(avg(body)))),
          stage: (typeof STG !== 'undefined' && typeof stage === 'function')
            ? String(STG[stage(body)] || '').slice(0, 40) : '',
          c1: look.c1,
          c2: look.c2,
          c3: look.c3
        };
      }).filter(Boolean);
      if (!entries.length) return;
      if (!entries.some(function (body) { return body.id === defaultId; })) defaultId = entries[0].id;
      NATIVE.widgetData(JSON.stringify({ defaultId: defaultId, stars: entries }));
    } catch (e) {}
  }
  window.__myspaceSyncWidgetNow = syncPlanetWidget;
  setTimeout(syncPlanetWidget, 5000);
  setInterval(syncPlanetWidget, 30000);
  document.addEventListener('visibilitychange', function () {
    if (!document.hidden) syncPlanetWidget();
  });

  /* Let the native back button return to the home view before leaving. */
  window.__myspaceAndroidBack = function () {
    try {
      if (typeof V !== 'undefined' && V !== 'home') {
        setV('home');
        return true;
      }
      if (typeof ST !== 'undefined' && ST.cosmosOpen) {
        ST.cosmosOpen = false;
        render();
        return true;
      }
    } catch (e) {}
    return false;
  };
})();
