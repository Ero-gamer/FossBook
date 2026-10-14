// Shares photos and videos from another app into Facebook's own composer (ShareMedia.kt).
// Run by the app, with window.__astryxShareTarget set: 'feed' on m.facebook.com's Home, 'story'
// on the desktop site's story creator (www.facebook.com/stories/create, in the Messages layer).
// The user writes the caption and posts; nothing here posts anything.
//
// The files come from the app through the AstryxShare message listener (ShareMediaBridge.kt):
// a header (JSON) and then each file's bytes (an ArrayBuffer, or base64 on WebViews without
// binary messages). Facebook's pages take files through a hidden <input type="file">:
// - Feed: the Photo button sets the input up and clicks it, which would open Android's picker.
//   The click is intercepted and the input gets the shared photos instead. The button is found
//   by its icon (a font glyph, the same in every language), or its English label.
// - Story: the creator's input is ready when the page shows; it gets one file at a time. When
//   the page is back to choosing a story (after Share to story or Discard), the next one comes.
//   (The layer lays that page out wide and fits it to the screen, so its preview shows.)
// ES5 on purpose, like the PiP scripts.
(function () {
  var target = window.__astryxShareTarget;
  var bridge = window.AstryxShare;
  if (!target || !bridge || window.__astryxShareRunning) return;
  window.__astryxShareRunning = true;

  var PHOTO_ICON = String.fromCharCode(0xDB8C, 0xDE3C); // U+F323C, the Photo button's icon
  var FIND_MS = window.__astryxShareFindMs || 20000; // tests shorten it
  var TAKE_MS = 5000;
  var STORY_MIN_WIDTH = 600;
  var STORY_MAX_WIDTH = 1200;

  var inbox = [];
  var waiting = null;
  bridge.onmessage = function (event) {
    inbox.push(event.data);
    flush();
  };
  function flush() {
    if (waiting && inbox.length) {
      var callback = waiting;
      waiting = null;
      callback(inbox.shift());
    }
  }
  function receive(callback) {
    waiting = callback;
    flush();
  }
  function send(message) {
    message.target = target;
    bridge.postMessage(JSON.stringify(message));
  }

  function toBlob(data, type, base64) {
    if (!base64) return new Blob([data], { type: type });
    var binary = atob(data);
    var bytes = new Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
    return new Blob([bytes], { type: type });
  }

  // Asks the app for files ('all' or 'next'); calls back with the File objects and the header.
  function receiveFiles(call, done) {
    send({ call: call });
    receive(function (text) {
      var header;
      try { header = JSON.parse(text); } catch (e) { done([], {}); return; }
      if (header.done) { done([], header); return; }
      var files = [];
      var index = 0;
      (function nextFile() {
        if (index >= header.count) { done(files, header); return; }
        receive(function (data) {
          var meta = header.files[index++];
          var blob = toBlob(data, meta.type, header.base64);
          if (blob.size > 0) files.push(new File([blob], meta.name, { type: meta.type, lastModified: Date.now() }));
          nextFile();
        });
      })();
    });
  }

  function giveFiles(input, files) {
    var transfer = new DataTransfer();
    for (var i = 0; i < files.length; i++) transfer.items.add(files[i]);
    input.files = transfer.files;
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
  }

  // Calls back with what find() returns once it returns something, or null after ms.
  function waitFor(find, ms, callback) {
    var started = Date.now();
    (function check() {
      var found = find();
      if (found) { callback(found); return; }
      if (ms && Date.now() - started > ms) { callback(null); return; }
      setTimeout(check, 200);
    })();
  }

  function photoButton() {
    var buttons = document.querySelectorAll('[role="button"]');
    for (var i = 0; i < buttons.length; i++) {
      var text = buttons[i].textContent || '';
      if (text.indexOf(PHOTO_ICON) === 0 || buttons[i].getAttribute('aria-label') === 'Photo') return buttons[i];
    }
    return null;
  }

  function shareToFeed() {
    waitFor(photoButton, FIND_MS, function (button) {
      if (!button) { send({ call: 'failed', reason: 'noComposer' }); return; }
      receiveFiles('all', function (files) {
        if (!files.length) { send({ call: 'finished' }); return; }
        var nativeClick = HTMLInputElement.prototype.click;
        var taken = false;
        HTMLInputElement.prototype.click = function () {
          if (this.type !== 'file') return nativeClick.apply(this, arguments);
          HTMLInputElement.prototype.click = nativeClick;
          taken = true;
          giveFiles(this, this.multiple ? files : files.slice(0, 1));
        };
        button.click();
        setTimeout(function () {
          HTMLInputElement.prototype.click = nativeClick;
          send(taken ? { call: 'finished' } : { call: 'failed', reason: 'notTaken' });
        }, TAKE_MS);
      });
    });
  }

  // The story creator is choosing a story: its file input is there and hasn't got ours.
  function readyStoryInput() {
    if (window.location.pathname.indexOf('/stories/create') !== 0) return null;
    var input = document.querySelector('input[type="file"]');
    if (!input || input.__astryxShared) return null;
    if (input.files && input.files.length) return null;
    return input;
  }

  // The desktop story creator is wider than a phone (its preview sits right of the controls) and
  // its viewport says device-width: lay it out at its own width and fit that to the screen. The
  // layer allows a wide viewport on this page. Kept up when another script (pinch_to_zoom.js)
  // rewrites the viewport.
  function fitStoryPage() {
    var width = 0;
    var content = '';
    function apply() {
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
    // The page grows when a story opens in the editor (its preview): follow it, widening only.
    function fit() {
      var wanted = Math.min(STORY_MAX_WIDTH, Math.max(STORY_MIN_WIDTH, document.documentElement.scrollWidth));
      if (wanted <= width) return;
      width = wanted;
      var scale = Math.min(1, (window.outerWidth || window.innerWidth) / width).toFixed(3);
      content = 'width=' + width + ', initial-scale=' + scale + ', minimum-scale=' + scale + ', user-scalable=yes';
      apply();
    }
    fit();
    new MutationObserver(apply).observe(document.head || document.documentElement,
      { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] });
    setInterval(fit, 500);
  }

  function shareToStory(first) {
    if (first) fitStoryPage();
    // The first file waits for the page to load; the next ones for the user to finish a story.
    waitFor(readyStoryInput, first ? FIND_MS : 0, function (input) {
      if (!input) { send({ call: 'failed', reason: 'noComposer' }); return; }
      receiveFiles('next', function (files, header) {
        if (!files.length) { send({ call: 'finished' }); return; }
        input.__astryxShared = true;
        giveFiles(input, files.slice(0, 1));
        if (header.remaining > 0) shareToStory(false);
        else send({ call: 'finished' });
      });
    });
  }

  if (target === 'feed') shareToFeed();
  else if (target === 'story') shareToStory(true);
})();
