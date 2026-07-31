<%--
  TeamCity GitHub Bridge - branch enrichment client-side overlay.
  Injected into every TC page footer.

  Purpose: TC 2026.1 has no public extension point for the Branch
  column display (verified via SDK introspection). This fragment
  upgrades the rendering of the `draft` and `ready` tags placed on
  promotions by PrPromotionTagger so the human eye can pick them up
  in busy build lists.

  STYLING ONLY. Turning a tag into a link to the pull request was tried
  and removed: in TeamCity a tag *is* a filter, the React pages bind that
  behaviour by delegation on an ancestor -- whose CAPTURE listener runs
  before anything we can attach to the pill -- and a re-render drops
  attributes set on a node React owns. Both are properties of TC's own UI,
  not bugs to work around. Putting the link on the build page instead
  (BUILD_SUMMARY / BUILD_ACTIONS) was tried too and also removed: those
  containers only render on the CLASSIC build page. The `pr-189` tag stays
  what it is good at, a search/filter key.

  No GitHub API calls. No DOM dependencies beyond TC's stock tag
  markup. Safe to ship -- if TC's tag markup changes in a future
  release the selectors simply find nothing and the page renders as
  before.
--%>
<style>
    /* The palette is keyed on the class the JS adds, NOT on TC's own tag
       class. TC renders a tag as `.buildTag` (queue, configuration
       overview), `.tag` (build summary) or `a.tagLabel` depending on the
       page; keying the colours on those classes left every tag the JS
       matched through a selector the CSS did not repeat (`a.tagLabel`,
       `a[href*="tag:"]`) with no custom property at all -- hence a pill
       with a transparent background, indistinguishable from TC's stock
       grey chip. One class per tag name, and the mismatch cannot recur. */
    .bridge-pill--draft {
        /* Neutral on purpose: a draft is "not ready yet", not a warning. But
           cool-tinted and firmly outlined, so it still reads as a deliberate
           state and not as Ring UI's default grey chip sitting there unstyled. */
        --bridge-bg: #e6ebef;
        --bridge-fg: #37474f;
        --bridge-bd: #aebfc9;
    }
    .bridge-pill--ready {
        --bridge-bg: #e4f5e6;
        --bridge-fg: #17601f;
        --bridge-bd: #91cf9a;
    }

    /* TeamCity 2026.1 ships a dark theme. Same hues, inverted weights, so
       the pills stay legible instead of glowing. */
    @media (prefers-color-scheme: dark) {
        .bridge-pill--draft {
            --bridge-bg: #2f363b;
            --bridge-fg: #b3bfc7;
            --bridge-bd: #47525a;
        }
        .bridge-pill--ready {
            --bridge-bg: #1d3b23;
            --bridge-fg: #8fd39b;
            --bridge-bd: #2f6a3c;
        }
    }

    /* The media query only catches an OS-level preference, and TeamCity's theme
       is a per-user setting that can disagree with it. These are the markers
       TeamCity/Ring put on the document for its dark theme -- whichever exists
       here wins, and the ones that do not simply never match. `:where` keeps
       the specificity at zero so ordering alone decides, hence: after the
       media query. */
    :where(html.ring-ui-theme-dark, html[data-theme="dark"], body.ring-ui-theme-dark, body.dark-theme) .bridge-pill--draft {
        --bridge-bg: #2f363b;
        --bridge-fg: #b3bfc7;
        --bridge-bd: #47525a;
    }
    :where(html.ring-ui-theme-dark, html[data-theme="dark"], body.ring-ui-theme-dark, body.dark-theme) .bridge-pill--ready {
        --bridge-bg: #1d3b23;
        --bridge-fg: #8fd39b;
        --bridge-bd: #2f6a3c;
    }

    /* The chip, whichever UI drew it. Only the colours are set here, with
       `!important` because Ring UI's own `.ring-tag-default` paints the same
       properties from a stylesheet we do not control and cannot reorder. */
    .bridge-pill {
        background: var(--bridge-bg, transparent) !important;
        color: var(--bridge-fg, inherit) !important;
        border-color: var(--bridge-bd, transparent) !important;
    }

    /* Classic pages: no chip exists, so we draw the whole pill. Excluded on the
       React pages, where Ring already provides the padding, the radius and the
       border -- adding ours there would double its geometry. */
    .bridge-pill:not(.ring-tag-container) {
        display: inline-block;
        padding: 1px 8px;
        border-radius: 10px;
        border: 1px solid var(--bridge-bd, transparent);
        font-size: 11px;
        font-weight: 600;
        line-height: 16px;
        vertical-align: middle;
        margin-right: 4px;
    }

    /* A PR chip the operator chose not to display. Hidden, not removed: the tag
       stays on the build, so TeamCity's own tag filter and search still find it
       -- it simply stops competing for the narrow Tags column, where a second
       tag costs the draft/ready pill its legibility. */
    .bridge-pill--hidden {
        display: none !important;
    }

    /* Ring stacks three painted layers -- container, link, content -- and the
       inner two would cover the colour we just put on the chip, which is
       exactly what made a green label sit inside a grey block. Let ours show
       through, and keep the label's own weight. */
    .bridge-pill > a.ring-tag-tag,
    .bridge-pill .ring-tag-content {
        background: transparent !important;
        color: inherit !important;
        font-weight: 600;
    }
</style>

<script>
(function () {
    'use strict';

    // TeamCity renders a tag differently per page and per UI generation: the
    // classic pages use `.buildTag` / `.tag` / `a.tagLabel`, while the React
    // (Sakura) pages use CSS-module classes whose names are hashed
    // (`Tag__tag_a1b2c3`) -- which is why an explicit class list found nothing
    // there and the pills stayed uncoloured. Hence the substring matches, case
    // insensitive, plus `data-test` which the React UI sets.
    var TAG_SELECTOR = '.buildTag, .tag, a.tagLabel, a[href*="tag:"], ' +
        '[class*="tag" i], [data-test*="tag" i]';

    // The configured PR tag prefix (`prTag.prefix`, default `pr-`), sanitised
    // server-side by BranchEnrichmentPageExtension. Not used to build
    // anything -- it is reported in the diagnostic below so a support question
    // can be answered with what the page actually received.
    var PR_PREFIX = '${bridgePrTagPrefix}';

    // Whether to hide the `<prefix><n>` chip: `prTag.display` off, with a usable
    // prefix. Decided server-side.
    //
    // Interpolated as a STRING and compared, never as a bare dollar-brace
    // expression: a missing model attribute renders nothing at all, and
    // `var x = ;` is a syntax error that would take the whole fragment down with
    // it -- pills included. A cosmetic overlay must not be able to do that.
    var HIDE_PR_TAG = '${bridgeHidePrTag}' === 'true';

    function isStateTag(el) {
        var text = (el.textContent || '').trim().toLowerCase();
        return (text === 'draft' || text === 'ready') ? text : null;
    }

    // A PR tag chip: exactly the configured prefix followed by digits. Anchored
    // on both ends so a team's own `pr-review-notes` is never touched.
    function isPrTag(el) {
        if (!HIDE_PR_TAG || !PR_PREFIX) return false;
        var text = (el.textContent || '').trim();
        if (text.length <= PR_PREFIX.length) return false;
        if (text.slice(0, PR_PREFIX.length) !== PR_PREFIX) return false;
        return /^[0-9]+$/.test(text.slice(PR_PREFIX.length));
    }

    // The elements to consider. The tag selector is tried first; when it finds
    // none of our tags -- a TeamCity page whose tag markup we do not recognise --
    // fall back to scanning for LEAF elements whose whole text is exactly
    // "draft" or "ready", which is what a tag chip looks like whatever classes
    // it carries. The leaf restriction keeps the pill on the chip instead of on
    // a container that happens to hold only it.
    function isOurs(el) {
        return isStateTag(el) !== null || isPrTag(el);
    }

    function candidates() {
        var matched = [];
        document.querySelectorAll(TAG_SELECTOR).forEach(function (el) {
            if (isOurs(el) && !el.querySelector(TAG_SELECTOR)) matched.push(el);
        });
        if (matched.length > 0) return matched;
        document.querySelectorAll('span, a, button, div, td').forEach(function (el) {
            if (el.children.length === 0 && isOurs(el)) matched.push(el);
        });
        return matched;
    }

    // WHERE to paint. Finding the tag is not the same as finding the chip: on
    // the React pages TeamCity uses JetBrains' Ring UI, whose chip is
    //
    //     span.ring-tag-container  <- the grey background lives here
    //       a.ring-tag-tag
    //         span.ring-tag-content   <- the deepest thing that looks like a tag
    //           span.MiddleEllipsis...  <- the text
    //
    // and the text match lands on `.ring-tag-content`. Painting that painted a
    // rectangle INSIDE Ring's grey chip -- a green label in a grey block. So
    // climb to the chip and paint the chip.
    function pillHost(el) {
        return el.closest('.ring-tag-container, .buildTag, .tag, a.tagLabel') || el;
    }

    // Apply the .bridge-pill class to our tags. Idempotent; safe to call
    // repeatedly (TC re-renders parts of the page via AJAX).
    function enrich() {
        try {
            var enriched = 0;
            var hidden = 0;
            candidates().forEach(function (el) {
                if (isPrTag(el)) {
                    var prHost = pillHost(el);
                    if (!prHost.classList.contains('bridge-pill--hidden')) {
                        prHost.classList.add('bridge-pill--hidden');
                        hidden++;
                    }
                    return;
                }
                var text = isStateTag(el);
                if (!text) return;
                var host = pillHost(el);
                if (host.classList.contains('bridge-pill')) return;
                // The per-tag class carries the colour; data-tag-name is kept
                // for anyone inspecting the DOM.
                host.classList.add('bridge-pill', 'bridge-pill--' + text);
                host.setAttribute('data-tag-name', text);
                enriched++;
            });
            report(enriched, hidden);
        } catch (e) {
            // Never break the host page over a cosmetic enhancement.
            if (window.console && console.warn) {
                console.warn('teamcity-github-bridge: enrichment skipped', e);
            }
        }
    }

    // A diagnosis anyone can read back to us: how many pills this page got.
    // Written once, so a support question is one console line instead of a DOM
    // safari.
    var reported = false;
    function report(enriched, hidden) {
        if (reported || !window.console || !console.debug) return;
        reported = true;
        console.debug('teamcity-github-bridge: ' + enriched + ' tag pill(s) styled, ' +
            hidden + ' PR tag(s) hidden; prTagPrefix=' + JSON.stringify(PR_PREFIX) +
            ', hidePrTag=' + HIDE_PR_TAG);
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
