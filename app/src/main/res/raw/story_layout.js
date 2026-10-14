// The desktop story creator (www.facebook.com/stories/create, in the Messages layer) laid out
// like the Facebook app's story editor, at the phone's own width so its text is a normal size:
// the story fills the screen, the creator's tools (text, music, time, alternative text) are round
// buttons along the bottom of the story, and Facebook's own privacy / Discard / Share to story
// row sits under it. Stickers and text can be dragged with a finger.
//
// Facebook builds the creator from a sidebar (role=navigation, the tools) beside the preview
// (role=main); at phone width it already moves Discard and Share under the story. Everything is
// found by its role and its place in the page, never its (translated) label. Run by the app on the
// story creator page; window.__astryxStoryLayout.stop() undoes it (for live debugging).
// ES5 on purpose, like the PiP scripts.
(function () {
  if (window.__astryxStoryLayout) return;

  // A story open in the editor: the phone's own width. The page choosing a story (before the
  // first one, after Discard): Facebook's desktop layout, 600 px wide, fitted to the screen.
  var EDITING = 'width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no';
  var CHOOSER_WIDTH = 600;
  function wantedViewport() {
    if (storyFrame()) return EDITING;
    var scale = Math.min(1, (window.outerWidth || screen.width) / CHOOSER_WIDTH).toFixed(3);
    return 'width=' + CHOOSER_WIDTH + ', initial-scale=' + scale + ', minimum-scale=' + scale + ', user-scalable=yes';
  }
  var BAR_HEIGHT = 64; // the bottom bar: privacy, Discard, Share to story
  var TOOLS_GAP = 40; // between the tools (their labels hang below them) and the bottom of the story

  var timers = [];
  var observers = [];
  var listeners = [];
  var style = null;

  function on(target, type, handler, options) {
    target.addEventListener(type, handler, options);
    listeners.push([target, type, handler, options]);
  }
  function observe(target, options, callback) {
    var observer = new MutationObserver(callback);
    observer.observe(target, options);
    observers.push(observer);
  }

  // Phone width, kept when another script (pinch_to_zoom.js) rewrites the viewport.
  function viewport() {
    var content = wantedViewport();
    var metas = document.querySelectorAll('meta[name="viewport"]');
    if (!metas.length && document.head) {
      var meta = document.createElement('meta');
      meta.name = 'viewport';
      document.head.appendChild(meta);
      metas = [meta];
    }
    for (var i = 0; i < metas.length; i++) {
      if (metas[i].getAttribute('content') !== content) metas[i].setAttribute('content', content);
    }
  }

  // The story's frame: sized by Facebook with an inline width in px and a height relative to its
  // box ("calc(100% - Npx)"), around the photo or video.
  function storyFrame() {
    var boxes = document.querySelectorAll('div[style*="calc(100% - "]');
    for (var i = 0; i < boxes.length; i++) {
      var css = boxes[i].getAttribute('style') || '';
      if (/width:\s*[\d.]+px/.test(css) && boxes[i].querySelector('img, video')) return boxes[i];
    }
    return null;
  }

  // Facebook sizes the frame from the window's height: width = (innerHeight - a constant) x 9/16,
  // height = its box's height - a constant, centred and clipped in the preview. Report a window
  // height that gives the largest 9:16 frame that fits the screen, make its box that tall, and
  // let Facebook resize the frame. The constants are read from the page.
  var ownInnerHeight = Object.getOwnPropertyDescriptor(window, 'innerHeight');
  var reported = 0;
  var margin = 0; // innerHeight - the frame's height, in Facebook's sizing
  var lastWidth = 0;
  var steady = 0;
  function realHeight() {
    return ownInnerHeight && ownInnerHeight.get ? ownInnerHeight.get.call(window) : document.documentElement.clientHeight;
  }
  try {
    Object.defineProperty(window, 'innerHeight', {
      configurable: true,
      get: function () { return reported || realHeight(); }
    });
  } catch (e) { /* the frame keeps Facebook's size */ }

  function fitFrame(frame) {
    if (!frame) {
      lastWidth = 0;
      if (reported) {
        reported = 0;
        window.dispatchEvent(new Event('resize'));
      }
      return;
    }
    var box = frame.parentElement;
    var width = parseFloat(frame.style.width);
    var inset = parseFloat(frame.style.top) || 0;
    var below = parseFloat((/calc\(100% - ([\d.]+)px\)/.exec(frame.style.height) || [])[1]) || 0;
    if (!width) return;
    // Facebook's constant, from the window height it was last told and the width it gave the
    // frame for it, once that width has held for a moment (the page may be mid-layout: a new
    // viewport, a resize not handled yet).
    if (width !== lastWidth) {
      lastWidth = width;
      steady = 0;
      return;
    }
    if (++steady < 2) return;
    margin = (reported || realHeight()) - width * 16 / 9;
    var byWidth = box.clientWidth - 2 * inset;
    var byHeight = (realHeight() - box.getBoundingClientRect().top - inset - below - BAR_HEIGHT) * 9 / 16;
    var wanted = Math.floor(Math.min(byWidth, byHeight));
    if (wanted <= 0) return;
    var height = Math.round(wanted * 16 / 9 + below) + 'px';
    if (box.style.height !== height) box.style.height = height;
    var next = Math.round(wanted * 16 / 9 + margin);
    if (next === reported || Math.abs(width - wanted) < 1) return;
    reported = next;
    steady = 0;
    window.dispatchEvent(new Event('resize'));
  }

  var ATTRIBUTES = ['data-astryx-story', 'data-astryx-story-hide', 'data-astryx-story-path', 'data-astryx-story-popup',
    'data-astryx-story-caption', 'data-astryx-story-captioned'];
  function untag() {
    var tagged = document.querySelectorAll('[' + ATTRIBUTES.join('], [') + ']');
    for (var n = 0; n < tagged.length; n++) {
      for (var a = 0; a < ATTRIBUTES.length; a++) tagged[n].removeAttribute(ATTRIBUTES[a]);
    }
  }

  function mark(el, value) {
    if (el && el.getAttribute('data-astryx-story') !== value) el.setAttribute('data-astryx-story', value);
  }

  // Tags the preview, the sidebar and its tools for the style sheet, and hides the rest of the
  // sidebar (its header, the AI label, its own Discard / Share).
  function layout() {
    try {
      arrange();
    } catch (e) {
      window.__astryxStoryLayoutError = String(e && e.stack || e);
    }
  }

  // Facebook's small floating panels (a music sticker's styles, the song clip bar with Pause,
  // Undo and Done) are rows of round icon buttons, named only for screen readers. Each gets its
  // name (Facebook's own, in the user's language) written under it. A row wider than the screen
  // (the clip bar: its 300 px waveform pushed Done off the edge) shrinks to fit. Panels with many
  // icon buttons (the song list's play and save buttons) are left alone.
  function labelControls(layer) {
    var buttons = layer.querySelectorAll('[role="button"][aria-label]');
    var icons = [];
    for (var i = 0; i < buttons.length; i++) {
      var b = buttons[i];
      var box = b.getBoundingClientRect();
      if (/\S/.test(b.innerText || '') || !b.querySelector('svg, i, img')) continue;
      if (box.width < 24 || box.width > 64 || box.height < 24 || box.height > 64) continue;
      icons.push(b);
    }
    if (icons.length < 3 || icons.length > 5) return;
    var row = icons[0].parentElement;
    for (var r = 1; r < icons.length; r++) {
      while (row && !row.contains(icons[r])) row = row.parentElement;
    }
    if (!row || row === layer.parentElement) return;
    for (var c = 0; c < icons.length; c++) {
      if (!icons[c].hasAttribute('data-astryx-story-caption')) icons[c].setAttribute('data-astryx-story-caption', '');
      // Inline: some of Facebook's button styles outrank the style sheet's.
      if (icons[c].style.overflow !== 'visible') icons[c].style.setProperty('overflow', 'visible', 'important');
    }
    if (!row.hasAttribute('data-astryx-story-captioned')) row.setAttribute('data-astryx-story-captioned', '');
    // A panel (one with text, like the music sticker's: song, Remove, styles) closes only on a tap
    // elsewhere on the story, which nothing says: it gets a Done button that does that.
    if (layer.hasAttribute('data-astryx-story-popup')) addDone(row);
  }

  var DONE_LABEL = window.__astryxStoryDone || 'Done';
  function addDone(row) {
    var next = row.nextElementSibling;
    if (next && next.hasAttribute('data-astryx-story-done')) return;
    var done = document.createElement('div');
    done.setAttribute('role', 'button');
    done.setAttribute('tabindex', '0');
    done.setAttribute('data-astryx-story-done', '');
    done.textContent = DONE_LABEL;
    done.addEventListener('click', function (e) {
      e.stopPropagation();
      tapStoryBackground();
    });
    row.parentElement.insertBefore(done, next);
  }

  // A mouse tap on a corner of the story's photo: what closes Facebook's panels.
  function tapStoryBackground() {
    var frame = storyFrame();
    if (!frame) return;
    var box = frame.getBoundingClientRect();
    var x = box.left + 12;
    var y = box.top + 12;
    var target = document.elementFromPoint(x, y);
    if (!target || !frame.contains(target)) return;
    var init = { bubbles: true, cancelable: true, composed: true, view: window, clientX: x, clientY: y, button: 0 };
    function fire(Type, type, buttons, pointer) {
      var o = {};
      for (var k in init) o[k] = init[k];
      o.buttons = buttons;
      if (pointer) { o.pointerId = 1; o.pointerType = 'mouse'; o.isPrimary = true; }
      if (Type === window.PointerEvent && !window.PointerEvent) return;
      target.dispatchEvent(new Type(type, o));
    }
    fire(window.PointerEvent, 'pointerdown', 1, true);
    fire(MouseEvent, 'mousedown', 1, false);
    fire(window.PointerEvent, 'pointerup', 0, true);
    fire(MouseEvent, 'mouseup', 0, false);
    fire(MouseEvent, 'click', 0, false);
  }

  function arrange() {
    viewport();
    // Facebook's popups (the music list, text style, a sticker's options): fixed layers placed
    // with an inline translate() beside the sidebar's tool, off the screen now. The ones showing
    // something are centred under the header; empty ones (closed popups' hosts) stay where they
    // are, so they don't sit over the story.
    var layers = document.querySelectorAll('div[style*="position: fixed"][style*="translate("]');
    for (var l = 0; l < layers.length; l++) {
      if (layers[l].closest('[role="main"]')) continue;
      // innerText skips hidden content; a closed popup keeps its (hidden) images.
      var shown = layers[l].firstElementChild && getComputedStyle(layers[l].firstElementChild).visibility !== 'hidden';
      var showing = shown && (/\S/.test(layers[l].innerText || '') || !!layers[l].querySelector('input, textarea, [contenteditable]'));
      if (showing) layers[l].setAttribute('data-astryx-story-popup', '');
      else layers[l].removeAttribute('data-astryx-story-popup');
      if (shown) labelControls(layers[l]);
    }

    var frame = storyFrame();
    fitFrame(frame);
    var main = frame && frame.closest('[role="main"]');
    if (!main) {
      untag(); // choosing a story: Facebook's own page
      return;
    }
    var nav = null; // the sidebar beside the preview (the page header is a navigation too)
    for (var sib = main.parentElement.firstElementChild; sib; sib = sib.nextElementSibling) {
      if (sib !== main && sib.getAttribute('role') === 'navigation') nav = sib;
    }
    if (!nav) return;
    // Under Facebook's header bar (56 px on the desktop site).
    var header = document.querySelector('[role="banner"]');
    var top = header ? header.getBoundingClientRect().bottom : 0;
    document.documentElement.style.setProperty('--astryx-story-top', (top > 0 ? top : 56) + 'px');
    mark(main, 'main');
    mark(nav, 'nav');

    // The tools: the sidebar's rows with an icon and a one-line label (the AI label row is
    // taller, with its explanation). Discard and Share to story: its labelled buttons without an
    // icon. The privacy button: its button with an icon and no label (in its header).
    var tools = [];
    var actions = null;
    var privacy = null;
    var buttons = nav.querySelectorAll('[role="button"]');
    for (var i = 0; i < buttons.length; i++) {
      var b = buttons[i];
      var box = b.getBoundingClientRect();
      var labelled = /\S/.test(b.innerText || '');
      var icon = b.querySelector('svg, i, img');
      if (labelled && icon && box.height < 90 && (box.height > 0 || b.getAttribute('data-astryx-story') === 'tool')) {
        tools.push(b);
      } else if (labelled && !icon && !actions) {
        actions = b.parentElement;
        while (actions && actions !== nav && actions.querySelectorAll('[role="button"]').length < 2) {
          actions = actions.parentElement;
        }
        if (actions === nav) actions = null;
      } else if (!labelled && icon && !privacy && b.getAttribute('aria-label')) {
        privacy = b;
      }
    }
    if (!tools.length) return;
    var list = tools[0].parentElement;
    for (var t = 0; t < tools.length; t++) {
      if (tools[t].parentElement === list) mark(tools[t], 'tool');
    }
    mark(list, 'tools');
    if (actions) mark(actions, 'actions');
    if (privacy) mark(privacy, 'privacy');

    // Everything else in the sidebar is hidden (and in the tools' list whatever isn't a tool).
    // The boxes on the way to what's kept lose their clipping: the sidebar's scroller (overflow
    // hidden, will-change: transform) would clip them and hold them as their fixed position's frame.
    var keep = [list];
    if (actions) keep.push(actions);
    if (privacy) keep.push(privacy);
    function holdsKept(el) {
      for (var k = 0; k < keep.length; k++) if (el.contains(keep[k])) return true;
      return false;
    }
    (function hide(el) {
      for (var c = el.firstElementChild; c; c = c.nextElementSibling) {
        if (c === list) {
          for (var item = list.firstElementChild; item; item = item.nextElementSibling) {
            if (item.getAttribute('data-astryx-story') !== 'tool' && !item.querySelector('[data-astryx-story="tool"]')) {
              item.setAttribute('data-astryx-story-hide', '');
            }
          }
        } else if (keep.indexOf(c) !== -1) {
          continue;
        } else if (holdsKept(c)) {
          if (!c.hasAttribute('data-astryx-story-path')) c.setAttribute('data-astryx-story-path', '');
          hide(c);
        } else if (!c.hasAttribute('data-astryx-story-hide')) {
          c.setAttribute('data-astryx-story-hide', '');
        }
      }
    })(nav);

    // The tools along the bottom of the story (measured once the rest of their list is hidden).
    var frameBox = frame.getBoundingClientRect();
    document.documentElement.style.setProperty('--astryx-tools-top',
      Math.max(0, frameBox.bottom - list.getBoundingClientRect().height - TOOLS_GAP) + 'px');

    // Facebook's narrow layout repeats privacy / Discard / Share in a row of its own (outside the
    // sidebar): it gives way to the bottom bar. Found by the bottom bar's own labels, so other
    // text buttons (a panel's "See all") stay; never a dialog's buttons ("Discard story?").
    var privacyLabel = privacy && privacy.getAttribute('aria-label');
    var actionLabels = [];
    if (actions) {
      var actionButtons = actions.querySelectorAll('[role="button"]');
      for (var a = 0; a < actionButtons.length; a++) actionLabels.push((actionButtons[a].innerText || '').replace(/^\s+|\s+$/g, ''));
    }
    var all = document.querySelectorAll('[role="button"]');
    for (var o = 0; o < all.length; o++) {
      var other = all[o];
      if (nav.contains(other) || other.closest('[role="dialog"]') || other.closest('[data-astryx-story-hide]')) continue;
      if (privacyLabel && other.getAttribute('aria-label') === privacyLabel) {
        other.setAttribute('data-astryx-story-hide', ''); // its privacy button, kept in the bottom bar
        continue;
      }
      if (actionLabels.indexOf((other.innerText || '').replace(/^\s+|\s+$/g, '')) === -1) continue;
      var row = other.parentElement;
      while (row && row !== document.body && row.querySelectorAll('[role="button"]').length < 2) row = row.parentElement;
      if (row && row !== document.body && !row.contains(frame) && !row.contains(nav)) {
        row.setAttribute('data-astryx-story-hide', '');
      }
    }
  }

  function addStyle() {
    style = document.createElement('style');
    style.textContent =
      '[data-astryx-story="main"] { position: fixed !important; left: 0 !important; right: 0 !important;' +
      ' top: var(--astryx-story-top, 56px) !important; bottom: ' + BAR_HEIGHT + 'px !important; width: auto !important;' +
      ' max-width: none !important; min-width: 0 !important; margin: 0 !important; z-index: 1 !important;' +
      ' overflow: hidden auto !important; touch-action: none !important; }' +
      '[data-astryx-story="nav"] { position: fixed !important; left: 0 !important; right: 0 !important;' +
      ' bottom: 0 !important; top: auto !important; width: auto !important; max-width: none !important;' +
      ' height: 0 !important; min-height: 0 !important; overflow: visible !important; z-index: 2 !important;' +
      ' background: none !important; border: 0 !important; }' +
      '[data-astryx-story-hide] { display: none !important; }' +
      '[data-astryx-story="actions"] { position: fixed !important; left: 64px !important; right: 12px !important;' +
      ' bottom: 0 !important; top: auto !important; height: ' + BAR_HEIGHT + 'px !important; width: auto !important;' +
      ' display: flex !important; align-items: center !important; justify-content: flex-end !important;' +
      ' gap: 8px !important; padding: 0 !important; margin: 0 !important; background: none !important; }' +
      '[data-astryx-story="actions"] > * { flex: 1 1 0 !important; max-width: 50% !important; margin: 0 !important; }' +
      '[data-astryx-story="privacy"] { position: fixed !important; left: 12px !important;' +
      ' bottom: ' + ((BAR_HEIGHT - 40) / 2) + 'px !important; top: auto !important; width: 40px !important;' +
      ' height: 40px !important; margin: 0 !important; }' +
      // Facebook's popups (the music list, text style, a sticker's options) open beside the
      // sidebar's tool, off the screen now: centred under the header instead, clear of the
      // keyboard. They're fixed layers placed with an inline translate().
      '[data-astryx-story-popup] { left: 50% !important; top: calc(var(--astryx-story-top, 56px) + 8px) !important;' +
      ' transform: translateX(-50%) !important; max-width: calc(100vw - 16px) !important; }' +
      // The app's own Copy and Download buttons (for media pages) don't belong on the story.
      'html body #materialbook-clipboard-copier, html body #materialbook-global-downloader,' +
      ' html body #materialbook-clipboard-copier.visible, html body #materialbook-global-downloader.visible' +
      ' { display: none !important; }' +
      'html body [data-astryx-story-done] { display: block !important; margin: 0 12px 12px !important;' +
      ' padding: 8px 0 !important; border-radius: 8px !important; background: #0866ff !important;' +
      ' color: #fff !important; font: 600 15px/20px system-ui, sans-serif !important;' +
      ' text-align: center !important; cursor: pointer !important; }' +
      // Labelled icon buttons in Facebook's panels (see labelControls).
      // (html body: Facebook's own classes clip the buttons otherwise.) Long names keep two lines.
      'html body [data-astryx-story-caption] { position: relative !important; overflow: visible !important; }' +
      'html body [data-astryx-story-caption]::after { content: attr(aria-label); position: absolute;' +
      ' top: calc(100% + 3px); left: 50%; transform: translateX(-50%); width: 68px;' +
      ' font: 600 10px/12px system-ui, sans-serif; color: #fff; text-align: center;' +
      ' text-shadow: 0 1px 2px rgba(0, 0, 0, .9); white-space: normal; pointer-events: none;' +
      ' display: -webkit-box; -webkit-box-orient: vertical; -webkit-line-clamp: 2; overflow: hidden; }' +
      'html body [data-astryx-story-captioned] { margin-bottom: 28px !important;' +
      ' max-width: calc(100vw - 16px) !important; height: auto !important; overflow: visible !important; }' +
      'html body [data-astryx-story-captioned] > * { flex-shrink: 1 !important; min-width: 0 !important;' +
      ' align-self: flex-start !important; }' +
      'html body [data-astryx-story-captioned] > :not(:has([data-astryx-story-caption])) { overflow: hidden !important; }' +
      '[data-astryx-story-path] { overflow: visible !important; will-change: auto !important;' +
      ' transform: none !important; contain: none !important; filter: none !important;' +
      ' perspective: none !important; backdrop-filter: none !important; transform-style: flat !important; }' +
      '[data-astryx-story="tools"] { position: fixed !important; left: 0 !important; right: 0 !important;' +
      ' top: var(--astryx-tools-top, 50vh) !important; bottom: auto !important; display: flex !important;' +
      ' justify-content: center !important; gap: 4px !important; padding: 0 !important; margin: 0 !important;' +
      ' width: auto !important; height: auto !important; background: none !important; }' +
      '[data-astryx-story="tool"] { display: flex !important; flex-direction: column !important;' +
      ' align-items: center !important; justify-content: flex-start !important; width: 76px !important;' +
      ' height: auto !important; padding: 4px 0 !important; margin: 0 !important; background: none !important; }' +
      '[data-astryx-story="tool"] > * { margin: 0 !important; padding: 0 !important; max-width: 76px !important; }' +
      // Icon above label: Facebook's rows inside the button stack, and the boxes around the
      // label (sized for the sidebar) take the label's width.
      '[data-astryx-story="tool"] div { flex-direction: column !important; align-items: center !important;' +
      ' margin-left: 0 !important; margin-right: 0 !important; padding-left: 0 !important;' +
      ' padding-right: 0 !important; max-width: 76px !important; }' +
      '[data-astryx-story="tool"] div:not(:has(svg, i, img)) { width: auto !important; min-width: 0 !important;' +
      ' flex: none !important; height: auto !important; }' +
      '[data-astryx-story="tool"] span { font-size: 12px !important; line-height: 15px !important;' +
      ' text-align: center !important; white-space: normal !important; color: #fff !important;' +
      ' text-shadow: 0 1px 3px rgba(0, 0, 0, .9) !important; }';
    (document.head || document.documentElement).appendChild(style);
  }

  // Facebook's editor moves its stickers and text (and pans the photo) with the mouse only; a
  // finger did nothing. Once a finger has moved, its drag is replayed as a mouse drag (pointer and
  // mouse events, aimed at where it started); taps stay the browser's own.
  function dragByTouch() {
    var start = null;
    var dragging = false;
    function fire(touch, mouseType, pointerType, buttons) {
      var init = {
        bubbles: true, cancelable: true, composed: true, view: window,
        clientX: touch.clientX, clientY: touch.clientY, screenX: touch.screenX, screenY: touch.screenY,
        button: 0, buttons: buttons
      };
      if (pointerType && window.PointerEvent) {
        var p = {};
        for (var k in init) p[k] = init[k];
        p.pointerId = 1;
        p.pointerType = 'mouse';
        p.isPrimary = true;
        start.target.dispatchEvent(new PointerEvent(pointerType, p));
      }
      start.target.dispatchEvent(new MouseEvent(mouseType, init));
    }
    on(document, 'touchstart', function (e) {
      var main = document.querySelector('[data-astryx-story="main"]');
      if (e.touches.length !== 1 || !main || !main.contains(e.target)) { start = null; return; }
      var t = e.touches[0];
      start = { target: e.target, x: t.clientX, y: t.clientY, touch: t };
      dragging = false;
    }, { capture: true, passive: true });
    on(document, 'touchmove', function (e) {
      if (!start || e.touches.length !== 1) return;
      var t = e.touches[0];
      if (!dragging) {
        if (Math.abs(t.clientX - start.x) + Math.abs(t.clientY - start.y) < 8) return;
        dragging = true;
        fire(start.touch, 'mousedown', 'pointerdown', 1);
      }
      e.preventDefault();
      fire(t, 'mousemove', 'pointermove', 1);
    }, { capture: true, passive: false });
    on(document, 'touchend', function (e) {
      if (!start) return;
      if (dragging) {
        fire(e.changedTouches[0], 'mouseup', 'pointerup', 0);
        e.preventDefault(); // no click at the end of a drag
      }
      start = null;
      dragging = false;
    }, { capture: true, passive: false });
  }

  viewport();
  observe(document.head || document.documentElement,
    { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] }, viewport);
  addStyle();
  layout();
  var pending = null;
  observe(document.body, { childList: true, subtree: true }, function () {
    if (!pending) pending = setTimeout(function () { pending = null; layout(); }, 150);
  });
  timers.push(setInterval(layout, 500));
  dragByTouch();

  window.__astryxStoryLayout = {
    stop: function () {
      for (var i = 0; i < timers.length; i++) clearInterval(timers[i]);
      for (var j = 0; j < observers.length; j++) observers[j].disconnect();
      for (var k = 0; k < listeners.length; k++) {
        var l = listeners[k];
        l[0].removeEventListener(l[1], l[2], l[3]);
      }
      if (style) style.remove();
      untag();
      var added = document.querySelectorAll('[data-astryx-story-done]');
      for (var d = 0; d < added.length; d++) added[d].remove();
      try {
        if (ownInnerHeight) Object.defineProperty(window, 'innerHeight', ownInnerHeight);
        else delete window.innerHeight;
      } catch (e) { /* stays reported */ }
      reported = 0;
      window.dispatchEvent(new Event('resize'));
      delete window.__astryxStoryLayout;
    }
  };
})();
