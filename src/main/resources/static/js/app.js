// Progressive enhancement only: every action on the page also works without this file.
(function () {
    'use strict';

    // Confirmation for the form-submitted deletes. This lives here rather than in an onsubmit
    // attribute because the Content-Security-Policy the server sets is `script-src 'self'`, under
    // which an inline handler is simply dropped -- the form would post with no confirmation at all,
    // which is worse than having none, because the button still looks guarded.
    document.querySelectorAll('form[data-confirm]').forEach(function (form) {
        form.addEventListener('submit', function (event) {
            if (!window.confirm(form.dataset.confirm)) {
                event.preventDefault();
            }
        });
    });

    // Row-level delete, so clearing a noisy inbox does not need a page load per message.
    document.querySelectorAll('[data-delete]').forEach(function (button) {
        button.addEventListener('click', function (event) {
            event.stopPropagation();
            if (!window.confirm('Delete this message?')) {
                return;
            }
            fetch(button.dataset.delete, { method: 'DELETE' }).then(function () {
                var row = button.closest('tr');
                if (row) {
                    row.remove();
                }
            });
        });
    });

    // Clicking anywhere on a row opens the message; the subject is still a real link.
    document.querySelectorAll('.message-row').forEach(function (row) {
        row.addEventListener('click', function (event) {
            if (event.target.closest('a, button')) {
                return;
            }
            window.location.href = row.dataset.href;
        });
    });

    // Detail-view tabs.
    var tabs = document.querySelector('[data-tabs]');
    if (tabs) {
        tabs.querySelectorAll('.tab').forEach(function (tab) {
            tab.addEventListener('click', function () {
                tabs.querySelectorAll('.tab').forEach(function (t) { t.classList.remove('active'); });
                tabs.querySelectorAll('.panel').forEach(function (p) { p.classList.remove('active'); });
                tab.classList.add('active');
                var panel = tabs.querySelector('[data-panel="' + tab.dataset.tab + '"]');
                if (panel) {
                    panel.classList.add('active');
                }
            });
        });
    }

    // The inbox polls so a message sent from a terminal shows up without a refresh. Paused while
    // the tab is hidden, and never while a filter field has focus, so it cannot eat keystrokes.
    if (document.querySelector('.messages, .empty')) {
        window.setInterval(function () {
            if (document.hidden || document.activeElement.closest('form')) {
                return;
            }
            window.location.reload();
        }, 5000);
    }
}());
