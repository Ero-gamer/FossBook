// Text wrap on zoom (opt-in, only with pinch to zoom): when the user pinches in, text
// blocks near the visible area are narrowed to the visible width so their lines wrap on
// screen instead of running off the side, as Opera's "Text wrap" does. Zooming back out
// restores them. Runs once the pinch (or a pan) settles. ES5-style, like the PiP scripts.
//
// Never restyles an element that holds a video: Facebook's reels controller watches the
// video's ancestors and pauses the reel (see PIP_FOCUS_MODE_JS).
(function() {
  if (window.__astryxTextWrap || !window.visualViewport) return;

  var vv = window.visualViewport;
  var ATTR = 'data-astryx-wrap';      // holds the element's own inline max-width, to restore
  var MIN_SCALE = 1.05;               // below this the page counts as not zoomed
  var MIN_TEXT = 15;                  // shorter text (names, buttons, counts) is left alone
  var GUTTER = 8;                     // CSS px kept free on each side of the wrapped text
  var SETTLE_MS = 250;
  var timer = null;
  var pinched = false;
  var run = 0;                        // marks elements already looked at in this pass

  function blockOf(node) {
    var el = node.parentElement;
    while (el && el !== document.body && el !== document.documentElement) {
      var display = getComputedStyle(el).display;
      if (display !== 'inline' && display !== 'contents') return el;
      el = el.parentElement;
    }
    return null;
  }

  function unwrapAll() {
    var wrapped = document.querySelectorAll('[' + ATTR + ']');
    for (var i = 0; i < wrapped.length; i++) {
      wrapped[i].style.maxWidth = wrapped[i].getAttribute(ATTR);
      wrapped[i].removeAttribute(ATTR);
    }
  }

  // Narrows the text blocks from one screen above to one below the visible area to the
  // visible width. Returns the blocks it wrapped.
  function wrapNearby() {
    var width = Math.floor(vv.width) - 2 * GUTTER;
    if (width < 40) return null;
    var top = vv.offsetTop - vv.height;
    var bottom = vv.offsetTop + 2 * vv.height;
    var blocks = [];
    run++;
    // Blocks wrapped earlier follow the new zoom too, even when they're out of range now.
    var earlier = document.querySelectorAll('[' + ATTR + ']');
    for (var j = 0; j < earlier.length; j++) earlier[j].style.maxWidth = width + 'px';
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
    var node;
    while ((node = walker.nextNode())) {
      if (node.nodeValue.replace(/\s+/g, '').length < MIN_TEXT) continue;
      var parent = node.parentElement;
      if (!parent || parent.__astryxWrapRun === run) continue;
      parent.__astryxWrapRun = run;
      var near = parent.getBoundingClientRect();
      if (near.bottom < top || near.top > bottom) continue;
      var block = blockOf(node);
      if (!block || (block !== parent && block.__astryxWrapRun === run)) continue;
      block.__astryxWrapRun = run;
      if (block.querySelector('video')) continue;
      var wrapped = block.hasAttribute(ATTR);
      if (!wrapped && block.getBoundingClientRect().width <= width + 1) continue;
      if (!wrapped) block.setAttribute(ATTR, block.style.maxWidth || '');
      block.style.maxWidth = width + 'px';
      blocks.push(block);
    }
    return blocks;
  }

  // The block nearest the middle of the screen.
  function nearestToMiddle(blocks) {
    var middle = vv.offsetTop + vv.height / 2;
    var nearest = null;
    var best = Infinity;
    for (var i = 0; i < blocks.length; i++) {
      var rect = blocks[i].getBoundingClientRect();
      var distance = rect.top > middle ? rect.top - middle : rect.bottom < middle ? middle - rect.bottom : 0;
      if (distance < best) { best = distance; nearest = blocks[i]; }
    }
    return nearest;
  }

  // Pans the zoomed-in view so the block's text starts at the screen edge (its left edge,
  // or its right one for right-to-left text). A one-pixel marker at the current top of the
  // view is scrolled into view, which moves the visual viewport sideways only.
  function align(block) {
    var rect = block.getBoundingClientRect();
    var rtl = getComputedStyle(block).direction === 'rtl';
    var x = rtl ? rect.right + window.scrollX + GUTTER - 1 : rect.left + window.scrollX - GUTTER;
    var marker = document.createElement('div');
    marker.style.cssText = 'position:absolute;width:1px;height:1px;pointer-events:none;' +
      'visibility:hidden;left:' + Math.max(0, x) + 'px;top:' + vv.pageTop + 'px';
    document.documentElement.appendChild(marker);
    try {
      marker.scrollIntoView({ block: 'start', inline: rtl ? 'end' : 'start' });
    } catch (e) {}
    marker.parentNode.removeChild(marker);
  }

  // The element at the middle of the screen and where it is, so it can be kept in place:
  // wrapping makes text blocks taller and moves everything below them.
  function anchorAt() {
    var el = document.elementFromPoint(vv.offsetLeft + vv.width / 2, vv.offsetTop + vv.height / 2);
    return el ? { el: el, top: el.getBoundingClientRect().top } : null;
  }

  function restore(anchor) {
    if (!anchor || !document.documentElement.contains(anchor.el)) return;
    var moved = anchor.el.getBoundingClientRect().top - anchor.top;
    if (Math.abs(moved) > 1) window.scrollBy(0, moved);
  }

  // After a pinch: wrap and line the text up with the screen. After a pan: only wrap
  // what came into view, so the user can still move around freely.
  function update(afterPinch) {
    if (vv.scale <= MIN_SCALE) {
      var before = anchorAt();
      unwrapAll();
      restore(before);
      return;
    }
    var anchor = anchorAt();
    var blocks = wrapNearby();
    restore(anchor);
    if (!afterPinch) return;
    // The text under the middle of the screen, else the wrapped text closest to it.
    var focus = anchor && anchor.el.closest ? anchor.el.closest('[' + ATTR + ']') : null;
    focus = focus || nearestToMiddle(blocks);
    if (focus) align(focus);
  }

  function settle(isPinch) {
    if (isPinch) pinched = true;
    clearTimeout(timer);
    timer = setTimeout(function() {
      var afterPinch = pinched;
      pinched = false;
      update(afterPinch);
    }, SETTLE_MS);
  }

  vv.addEventListener('resize', function() { settle(true); });
  vv.addEventListener('scroll', function() { settle(false); });

  // update() is exposed for tests: a test WebView can't pinch.
  window.__astryxTextWrap = { update: update };
})();
