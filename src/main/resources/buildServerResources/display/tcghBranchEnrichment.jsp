<%--
  TeamCity GitHub Bridge - branch enrichment client-side overlay.
  Injected into every TC page footer.

  Purpose: TC 2026.1 has no public extension point for the Branch
  column display (verified via SDK introspection). This fragment
  upgrades the rendering of the `draft` and `ready` tags placed on
  promotions by PrPromotionTagger so the human eye can pick them up
  in busy build lists.

  No GitHub API calls. No DOM dependencies beyond TC's stock tag
  markup. Safe to ship — if TC's tag markup changes in a future
  release the selectors simply find nothing and the page renders as
  before.
--%>
<style>
    /* TC renders tags as elements with class "buildTag" (queue page,
       build configuration overview) or "tag" (build summary). We hit
       both selectors with attribute matches on the visible text. */
    .buildTag[data-tag-name="draft"],
    .tag[data-tag-name="draft"],
    a[title="draft"],
    span.buildTag:where(:is(:contains("draft"))) {
        --tcgh-bg: #fff8e1;
        --tcgh-fg: #ad8400;
        --tcgh-bd: #ffe082;
    }
    .buildTag[data-tag-name="ready"],
    .tag[data-tag-name="ready"],
    a[title="ready"] {
        --tcgh-bg: #e8f5e9;
        --tcgh-fg: #1b5e20;
        --tcgh-bd: #a5d6a7;
    }

    .tcgh-pill {
        display: inline-block;
        padding: 1px 8px;
        border-radius: 10px;
        font-size: 11px;
        font-weight: 600;
        line-height: 16px;
        vertical-align: middle;
        margin-right: 4px;
        background: var(--tcgh-bg, transparent);
        color: var(--tcgh-fg, inherit);
        border: 1px solid var(--tcgh-bd, transparent);
    }
</style>

<script>
(function () {
    'use strict';

    // Walk all TC-rendered tag elements (anchors that link to a
    // tag-filtered list) and apply the .tcgh-pill class when the
    // text matches one of ours. Idempotent; safe to call multiple
    // times (TC re-renders parts of the page via AJAX).
    function enrich() {
        try {
            var nodes = document.querySelectorAll(
                '.buildTag, .tag, a.tagLabel, a[href*="tag:"]'
            );
            nodes.forEach(function (el) {
                var text = (el.textContent || '').trim().toLowerCase();
                if (text === 'draft' || text === 'ready') {
                    if (!el.classList.contains('tcgh-pill')) {
                        el.classList.add('tcgh-pill');
                        el.setAttribute('data-tag-name', text);
                    }
                }
            });
        } catch (e) {
            // Never break the host page over a cosmetic enhancement.
            if (window.console && console.warn) {
                console.warn('teamcity-github-bridge: enrichment skipped', e);
            }
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', enrich);
    } else {
        enrich();
    }

    // TC mutates parts of the DOM after AJAX. Re-run enrichment on a
    // throttled MutationObserver. Bounded; if mutations get too noisy
    // we back off.
    var rerunPending = false;
    var observer = new MutationObserver(function () {
        if (rerunPending) return;
        rerunPending = true;
        setTimeout(function () {
            rerunPending = false;
            enrich();
        }, 250);
    });
    try {
        observer.observe(document.body, { childList: true, subtree: true });
    } catch (e) { /* document.body may not exist yet; DOMContentLoaded covers it */ }
})();
</script>
