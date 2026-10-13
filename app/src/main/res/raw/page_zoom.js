// Runs at document start (before the page's own scripts), on every page of the main view, with
// window.__astryxPageZoom set to the "Page zoom" setting (1.3 for 130%).
//
// m.facebook.com cancels WebView's text zoom: on load it divides its font-size variables by the
// zoom (its layout is computed on the server, in fixed boxes sized for its own text size). So the
// setting zooms the whole page instead: every viewport <meta> gets a layout width of the window's
// width divided by the zoom, shown at that zoom, and Facebook's "vpd" cookie reports that
// viewport, so the server lays the next pages out to fit it. (Zooming out, below 100%, needs the
// app's wide viewport setting.)
//
// The page has no <html> element yet at document start, so the <meta>s are caught as Facebook
// (and pinch_to_zoom.js, which rewrites the viewport later) add or change them. In a small window
// - PiP - the page keeps Facebook's own viewport: PiP's focus mode is tuned to that layout.
// window.__astryxPageZoomWidth() is the layout width in use (0: Facebook's own).
// ES5 on purpose, like the PiP scripts: it must parse on old WebViews too.
(function () {
  var zoom = window.__astryxPageZoom;
  if (!zoom || zoom === 1 || window.__astryxPageZoomInstalled) return;
  window.__astryxPageZoomInstalled = true;

  // A window narrower than this share of the screen's short side is PiP (or a split screen
  // too small to zoom in).
  var SMALL_WINDOW = 0.6;

  function layoutWidth() {
    var shortSide = Math.min(screen.width, screen.height);
    if (!window.outerWidth || window.outerWidth < shortSide * SMALL_WINDOW) return 0;
    return Math.round(window.outerWidth / zoom);
  }
  window.__astryxPageZoomWidth = layoutWidth;
  // The scale the page rests at (text_wrap.js counts pinch zoom from here).
  window.__astryxPageZoomScale = function () { return layoutWidth() ? zoom : 1; };

  // The page's viewport with our width and scale; the rest (interactive-widget, user-scalable)
  // stays. A maximum-scale below the zoom (pinch_to_zoom.js locks it at 1) is raised to it.
  function zoomed(content, width) {
    var parts = content.split(',');
    var out = [];
    var hasMax = false;
    for (var i = 0; i < parts.length; i++) {
      var part = parts[i].replace(/^\s+|\s+$/g, '');
      var key = part.split('=')[0].replace(/\s+/g, '').toLowerCase();
      if (!part || key === 'width' || key === 'initial-scale') continue;
      if (key === 'maximum-scale') {
        hasMax = true;
        var max = parseFloat(part.split('=')[1]);
        if (!(max >= zoom)) part = 'maximum-scale=' + zoom;
      }
      out.push(part);
    }
    if (!hasMax && /user-scalable\s*=\s*(no|0)/i.test(content)) out.push('maximum-scale=' + zoom);
    return ['width=' + width, 'initial-scale=' + zoom].concat(out).join(', ');
  }

  function apply(meta) {
    if ((meta.getAttribute('name') || '').toLowerCase() !== 'viewport') return;
    var current = meta.getAttribute('content') || '';
    // Anything we didn't write is the page's own viewport.
    if (current !== meta.getAttribute('data-astryx-zoomed')) {
      meta.setAttribute('data-astryx-original', current);
    }
    var original = meta.getAttribute('data-astryx-original') || '';
    var width = layoutWidth();
    var wanted = width ? zoomed(original, width) : original;
    if (current !== wanted) {
      meta.setAttribute('data-astryx-zoomed', wanted);
      meta.setAttribute('content', wanted);
    }
  }

  function applyAll() {
    var metas = document.getElementsByTagName('meta');
    for (var i = 0; i < metas.length; i++) apply(metas[i]);
  }

  new MutationObserver(function (records) {
    for (var i = 0; i < records.length; i++) {
      var record = records[i];
      if (record.type === 'attributes') {
        if (record.target.tagName === 'META') apply(record.target);
        continue;
      }
      for (var j = 0; j < record.addedNodes.length; j++) {
        var node = record.addedNodes[j];
        if (node.tagName === 'META') apply(node);
        else if (node.getElementsByTagName) {
          var metas = node.getElementsByTagName('meta');
          for (var k = 0; k < metas.length; k++) apply(metas[k]);
        }
      }
    }
  }).observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] });

  // Facebook's scripts write the window's size (outerWidth/outerHeight) into its "vpd" cookie,
  // which the server lays the next pages out for; report the zoomed viewport instead. Only
  // that cookie is touched.
  var cookie = Object.getOwnPropertyDescriptor(Document.prototype, 'cookie');
  if (cookie && cookie.get && cookie.set) {
    Object.defineProperty(document, 'cookie', {
      configurable: true,
      get: function () { return cookie.get.call(document); },
      set: function (value) {
        var width = layoutWidth();
        if (width && /^\s*vpd=/.test(value)) {
          var height = Math.round(window.outerHeight / zoom);
          value = String(value).replace(/^(\s*vpd=v1%3B)\d+x\d+x/, '$1' + height + 'x' + width + 'x');
        }
        cookie.set.call(document, value);
      }
    });
  }

  // Any already there (none at document start).
  applyAll();

  // Rotation (fullscreen video), PiP and back: the window's width changes.
  var lastWidth = -1;
  window.addEventListener('resize', function () {
    var width = layoutWidth();
    if (width !== lastWidth) {
      lastWidth = width;
      applyAll();
    }
  });
})();
