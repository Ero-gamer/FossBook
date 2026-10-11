// Messages layer only. The desktop site moves between its pages in-page (history.pushState)
// rather than loading them, which the layer's request interceptor never sees. Report every
// URL change to the app (AstryxPage, or LayerBridge on older WebViews), which closes the layer when it's one of Facebook's own sections
// (messagesLayerExit). ES5, like the PiP scripts.
(function () {
  if (window.__astryxLayerNavInstalled || !(window.AstryxPage || window.LayerBridge)) return;
  window.__astryxLayerNavInstalled = true;

  var last = location.href;
  function check() {
    if (location.href === last) return;
    last = location.href;
    if (window.AstryxPage) {
      window.AstryxPage.postMessage(JSON.stringify({ call: 'layerUrl', url: last }));
    } else {
      window.LayerBridge.onUrlChanged(last);
    }
  }

  ['pushState', 'replaceState'].forEach(function (name) {
    var original = history[name];
    history[name] = function () {
      var result = original.apply(this, arguments);
      check();
      return result;
    };
  });
  window.addEventListener('popstate', check);
  // In case the page kept a reference to the original pushState.
  setInterval(check, 1000);
})();
