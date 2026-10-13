// Runs at document start (before the page's own scripts), on every page of the main view; does
// something only on the page "Start on Feeds" opens the app on (FEEDS_PAGE_URL).
//
// m.facebook.com has no URL for Feeds: its menu entry is an in-page action that shows Feeds at
// "/" (history.replaceState), and every Feeds-looking URL (?sk=h_chr, /home.php?sk=h_chr)
// redirects to Home. So the app opens the menu (/bookmarks/) with #astryx-feeds and this script
// presses the menu's Feeds entry, with the menu hidden meanwhile. The entry is found by its icon,
// the same in every language (its label is translated), or by its English label. If it doesn't
// turn up, Home opens instead; if Facebook shows another page (the login page), that stays.
//
// window.__astryxFeeds marks the page as showing the Feeds it opened, for the Back handler
// (isStartFeeds in scripts.js). On WebViews without document-start scripts the app runs this
// after the page has loaded, with window.__astryxFeedsForce set (the mark may be gone by then).
// ES5 on purpose, like the PiP scripts: it must parse on old WebViews too.
(function () {
  var marked = window.location.hash === '#astryx-feeds' || window.__astryxFeedsForce;
  if (!marked || window.__astryxFeedsOpening) return;
  window.__astryxFeedsOpening = true;

  var FEEDS_ICON = 'FTDJiLwLrcM';
  var FEEDS_LABEL = 'Feeds';
  var GIVE_UP_AFTER_LOAD_MS = 6000;
  var GIVE_UP_MS = 20000;
  var RETRY_CLICK_MS = 700;

  var hide = document.createElement('style');
  hide.textContent = 'body { visibility: hidden !important; }';
  (document.head || document.documentElement).appendChild(hide);

  function reveal() {
    if (hide.parentNode) hide.parentNode.removeChild(hide);
  }

  function feedsEntry() {
    var images = document.querySelectorAll('[role="button"] img');
    for (var i = 0; i < images.length; i++) {
      if ((images[i].getAttribute('src') || '').indexOf(FEEDS_ICON) >= 0) {
        return images[i].closest('[role="button"]');
      }
    }
    return document.querySelector('[role="button"][aria-label="' + FEEDS_LABEL + '"]');
  }

  // A slow connection can take longer than the whole budget to load the menu, so the entry
  // gets a few seconds after the page has loaded, within an overall limit.
  var started = Date.now();
  var loadedAt = document.readyState === 'complete' ? started : 0;
  window.addEventListener('load', function () { loadedAt = Date.now(); });
  var lastClick = 0;
  var timer = setInterval(function () {
    var path = window.location.pathname;
    // Feeds replaced the menu at "/".
    if (lastClick && path === '/') {
      clearInterval(timer);
      window.__astryxFeeds = true;
      reveal();
      return;
    }
    // Facebook sent the menu elsewhere (logged out: the login page): show that.
    if (path.indexOf('/bookmarks') !== 0) {
      clearInterval(timer);
      reveal();
      return;
    }
    var now = Date.now();
    if ((loadedAt && now - loadedAt > GIVE_UP_AFTER_LOAD_MS) || now - started > GIVE_UP_MS) {
      clearInterval(timer);
      reveal();
      window.location.replace('https://m.facebook.com/');
      return;
    }
    // The entry can be there before the page handles taps on it: press it again until
    // Feeds opens.
    var entry = feedsEntry();
    if (entry && Date.now() - lastClick > RETRY_CLICK_MS) {
      lastClick = Date.now();
      entry.click();
    }
  }, 100);
})();
