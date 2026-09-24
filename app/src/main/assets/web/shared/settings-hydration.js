(function () {
    'use strict';

    var root = document.querySelector('main[aria-busy="true"]');
    if (!root) return;

    var events = ['click', 'change', 'input', 'submit', 'keydown'];
    var block = function (event) {
        event.preventDefault();
        event.stopImmediatePropagation();
    };
    events.forEach(function (type) {
        root.addEventListener(type, block, true);
    });

    window.BYD = window.BYD || {};
    BYD.utils = BYD.utils || {};
    BYD.utils.unlockSettingsHydration = function () {
        events.forEach(function (type) {
            root.removeEventListener(type, block, true);
        });
        root.style.pointerEvents = '';
        root.removeAttribute('inert');
        root.removeAttribute('aria-busy');
    };
})();
