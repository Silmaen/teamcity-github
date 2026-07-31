# Architecture

This page describes how the plugin is structured internally, how
Spring DI wires the components, and where the extension points to
TeamCity sit.

## High-level component diagram

```mermaid
flowchart TB
    classDef io fill:#e3f2fd,stroke:#1976d2
    classDef logic fill:#fff3e0,stroke:#f57c00
    classDef cache fill:#f3e5f5,stroke:#7b1fa2
    classDef sdk fill:#eceff1,stroke:#455a64
    classDef ui fill:#e8f5e9,stroke:#43a047

    subgraph TCSDK["TeamCity SDK"]
        OCM[OAuthConnectionsManager]:::sdk
        PCCM[ProjectConnection<br/>CredentialsManager]:::sdk
        PM[ProjectManager]:::sdk
        WCM[WebControllerManager]:::sdk
        SBS[SBuildServer]:::sdk
        SP[ServerPaths]:::sdk
        PP[PagePlaces]:::sdk
        SBP[StartBuildPrecondition<br/>SPI]:::sdk
        BSA[BuildServerAdapter<br/>SPI]:::sdk
    end

    subgraph Plugin["teamcity-github-bridge"]
        TCB[TeamCityGitHubBridgePlugin<br/>lifecycle bean]:::logic
        WC[WebhookConfig]:::logic
        LPR[LogPathResolver]:::logic
        TR[TokenResolver<br/>self-mint primary]:::logic
        ATM[AppTokenMinter<br/>JWT + installation token]:::logic
        ATC[AppTokenCache<br/>per-installation TTL]:::cache
        GC[GitHubClient<br/>REST + Jackson<br/>getPr / postCheckRun /<br/>listInstallations / mintToken]:::io
        PIC[PrInfoCache<br/>TTL 60s]:::cache
        REL[RecentEventsLog<br/>ring buffer N=100]:::cache

        RFR[PullRequestEventListener]:::logic
        DAF[DraftAwareBuildFilter]:::logic
        PBE[PrBuildEnricher<br/>buildStarted]:::logic
        PPT[PrPromotionTagger<br/>queue tag]:::logic
        DCR[DraftCheckRunReporter<br/>skipped Check Run]:::logic
        BSCRP[BuildStatusCheckRunPublisher<br/>queued / in_progress / cancelled / completed]:::logic

        PWC[PluginWebhookController]:::io
        WIC[WebhookInfoController]:::io
        ACP[AdminConsolePage<br/>TC admin tab]:::ui
        BEPE[BranchEnrichmentPageExtension<br/>draft/ready pills CSS+JS]:::ui
        WPP[WebhookPayloadParser]:::logic
        SV[SignatureVerifier]:::logic
    end

    OCM --> TR
    PCCM --> TR
    TR --> ATM
    ATM --> ATC
    ATM --> GC
    TR --> DAF
    TR --> PBE
    TR --> PPT
    TR --> DCR
    TR --> BSCRP

    GC --> PIC
    GC --> DCR
    GC --> BSCRP
    PIC --> DAF
    PIC --> PBE
    PIC --> PPT
    PIC --> DCR

    PM --> RFR
    PIC --> RFR

    SBS --> RFR
    SBS --> PBE
    SBS --> PPT
    SBS --> DCR
    SBS --> BSCRP

    SP --> LPR
    LPR --> WIC
    LPR --> ACP

    WCM --> PWC
    WCM --> WIC
    PP --> ACP
    PP --> BEPE

    WC --> PWC
    WC --> WIC
    WC --> ACP
    SBS --> WIC
    SBS --> ACP

    PWC --> SV
    PWC --> WPP
    PWC --> REL
    REL --> ACP
    WPP --> RFR

    DAF -.implements.-> SBP
    PBE -.extends.-> BSA
    PPT -.extends.-> BSA
    DCR -.extends.-> BSA
    BSCRP -.extends.-> BSA
```

## Packages

```text
io.github.dlachouette.teamcity.github
├── TeamCityGitHubBridgePlugin       main lifecycle bean
├── api/
│   ├── GitHubClient                 open class, HTTP + Jackson: getPr, prsForCommit, listPrFiles,
│   │                                postCheckRun, issue comments, installations, tokens
│   ├── PrInfoCache                  TTL-based, ConcurrentHashMap over the calls above
│   ├── PrInfo, RepoCoords           PR snapshot (draft, head sha/ref, head repo, title, body,
│   │                                labels) and the owner/repo parser
│   ├── TokenResolver                self-mint primary, credentials-manager fallback → ResolvedAccess
│   ├── AppTokenMinter, AppJwt       RS256 JWT signing and ghs_* installation-token minting
│   ├── AppTokenCache                per-installation TTL cache of minted tokens
│   ├── AppManager                   managed-App credentials, GET /app verification (AppVerification)
│   ├── RsaKeyParser                 PEM PKCS#1/PKCS#8 private-key parsing, pure + own tests
│   └── CheckRunRequest / Status / Conclusion / Annotation / AnnotationLevel, InstallationInfo,
│                                    CreatedToken, IssueComment, AppInfo, ManifestConversion, HttpResponse
├── config/
│   ├── WebhookConfig                webhook secret: plugin file → internal.properties fallback
│   ├── PluginSettingsStorage        reads/writes the plugin-owned settings file
│   ├── BridgeServerSettings         typed accessor for every server-global setting and flag
│   ├── PluginLogConfigurator        attaches the RollingFileAppender at startup
│   ├── LogPathResolver              state of the dedicated log file
│   ├── PluginSelfTester             the checks behind the admin button (TestResult, Status)
│   └── PublisherConflictReporter    one startup WARN listing double-publisher BuildTypes
├── enrich/
│   ├── PrBuildEnricher              buildStarted: PR parameters, draft/ready + pr-N tags
│   │                                (EnrichmentPlan is the pure, tested core)
│   ├── PrPromotionTagger            buildTypeAddedToQueue: tags the promotion (TagPlan)
│   └── PrParameterProvider          publishes teamcity.github.bridge.isdraft
├── feature/
│   ├── GitHubBridgeBuildFeature     the per-BuildType opt-in build feature
│   ├── BridgeFeatureConfig          BridgeFeatureConfig + BridgeFeatureReader + BridgeProjectParams,
│   │                                BridgeGate.decide → GateDecision, PrBuildRef (pull | branch)
│   ├── GateContextResolver          GateContext: branch, PR number, PrInfo, trigger source
│   ├── BridgeTrigger                AUTO | COMMAND | MANUAL + BridgeTriggerMarker (promotion stamp)
│   ├── BridgeRefs                   pull/N ref building and parsing
│   ├── BranchSpecMatcher            +:/-: branch-list matching
│   └── BundledPublisherDetector     spots a bundled commitStatusPublisher on the same BuildType
├── queue/
│   ├── DraftBuildQueueCleaner       removes out-of-scope automatic builds, reuses a passed commit;
│   │                                QueueCleanupPolicy is the pure removal rule
│   ├── DraftAwareBuildFilter        StartBuildPrecondition, last line of defence
│   └── ObsoleteBuildPolicy          pure rule: may the bridge stop this running build?
├── report/
│   ├── BuildStatusCheckRunPublisher Check Run lifecycle (queued/started/interrupted/finished/removed),
│   │                                artifact links, annotations; drives the sticky PR comment
│   ├── DraftCheckRunReporter        skip and reused-success rows (SkipReason)
│   ├── BuildProblemAnnotations      compiler diagnostics → output.annotations, max 50
│   ├── FailureClassifier            problem types → code / infrastructure / dependency failure
│   ├── BuildTimeline                where the build's wall-clock went (run, wait, causes)
│   ├── TestReport                   test counts and failing tests, as GitHub should read them
│   ├── PrSummaryCommenter           single sticky per-PR comment, one row per check
│   └── ReportHelpers                shared formatting used by the reporters
└── web/
    ├── PullRequestEventListener     PR/review/comment/re-run events → addToQueue; fork guard,
    │                                path filtering, retro-association, closed-PR cancellation
    ├── PluginWebhookController      POST /webhook: HMAC, replay guard, RecentEventsLog, fan-out
    ├── WebhookPayloadParser         Jackson over pull_request, pull_request_review,
    │                                pull_request_review_comment, issue_comment, check_run, check_suite
    ├── SignatureVerifier            HMAC SHA-256 + constant-time equality
    ├── DeliveryReplayGuard          bounded LRU + TTL of X-GitHub-Delivery ids
    ├── RecentEventsLog              ring buffer, capacity 100 (RecentEvent, Outcome)
    ├── BridgeMetrics                in-process counters, Prometheus text + JSON
    ├── RequestUrlBuilder            X-Forwarded-* absolute-URL reconstruction
    ├── WebhookInfoController        GET /info, /info.md (WebhookInfo, WebhookEvents)
    ├── HealthController             GET /health, anonymous liveness JSON
    ├── MetricsController            GET /metrics, anonymous Prometheus text
    ├── ApiController                external API: GET status|events|metrics, POST trigger
    ├── AdminConsolePage             AdminPage → admin/bridgeAdmin.jsp
    ├── AdminSettingsController      POST saveSecret.html, CSRF-protected
    ├── AdminTestController          POST runTests.html, CSRF-protected
    ├── AppManifestController        the App-manifest create/convert flow
    ├── BridgeProjectSettingsTab     EditProjectTab for the project-level params
    ├── BridgeProjectSettingsController  POST behind that tab, requires EDIT_PROJECT
    ├── BridgeBuildsTab              ProjectTab "Branches & PRs" (BridgeBuildRow, search + sort)
    └── BranchEnrichmentPageExtension    draft/ready pill CSS + JS on every page
```

## Spring DI wiring

Declared in
`src/main/resources/META-INF/build-server-plugin-teamcity-github-bridge.xml`:

```xml
<beans default-autowire="constructor">
    <bean class="...TeamCityGitHubBridgePlugin"/>

    <bean class="...config.PluginSettingsStorage"/>
    <bean class="...config.PluginLogConfigurator"/>
    <bean class="...config.WebhookConfig"/>
    <bean class="...config.LogPathResolver"/>
    <bean class="...config.BridgeServerSettings"/>

    <bean class="...api.GitHubClient"/>
    <bean class="...api.AppTokenCache"/>
    <bean class="...api.AppTokenMinter"/>
    <bean class="...api.TokenResolver"/>
    <bean class="...cache.PrInfoCache"/>

    <bean class="...feature.GitHubBridgeBuildFeature"/>
    <bean class="...retrigger.PullRequestEventListener"/>
    <bean class="...filter.DraftAwareBuildFilter"/>
    <bean class="...parameters.PrParameterProvider"/>
    <bean class="...enrich.PrBuildEnricher"/>
    <bean class="...enrich.PrPromotionTagger"/>
    <bean class="...report.DraftCheckRunReporter"/>
    <bean class="...report.PrSummaryCommenter"/>
    <bean class="...report.BuildStatusCheckRunPublisher"/>
    <bean class="...queue.DraftBuildQueueCleaner"/>

    <bean class="...web.RecentEventsLog"/>
    <bean class="...web.DeliveryReplayGuard"/>
    <bean class="...web.BridgeMetrics"/>

    <bean class="...web.PluginWebhookController"/>
    <bean class="...web.WebhookInfoController"/>
    <bean class="...web.HealthController"/>
    <bean class="...web.MetricsController"/>
    <bean class="...web.ApiController"/>
    <bean class="...web.AdminConsolePage"/>
    <bean class="...web.AdminSettingsController"/>
    <bean class="...web.AdminTestController"/>
    <bean class="...web.BridgeProjectSettingsTab"/>
    <bean class="...web.BridgeProjectSettingsController"/>
    <bean class="...web.BranchEnrichmentPageExtension"/>

    <bean class="...selftest.PluginSelfTester"/>
</beans>
```

`default-autowire="constructor"` makes Spring resolve every
constructor parameter against the available beans. TC's own beans
(`OAuthConnectionsManager`, `ProjectConnectionCredentialsManager`,
`ProjectManager`, `WebControllerManager`, `SBuildServer`,
`AuthorizationInterceptor`, `PagePlaces`, `ServerPaths`) are
exposed by the TC core context, which our XML inherits from.

## Webhook events handled

`PluginWebhookController` verifies the HMAC signature, drops
replays via `DeliveryReplayGuard` (keyed on `X-GitHub-Delivery`),
then dispatches by `X-GitHub-Event` to `PullRequestEventListener`:

| Event | Action(s) | Handler | Effect |
|---|---|---|---|
| `pull_request` | `opened`, `reopened`, `ready_for_review`, `synchronize` | `handle` | Gate + path-filter, then enqueue matching BuildTypes. On `synchronize`, then stop the builds still running on the previous head (`ObsoleteBuildPolicy`). |
| `pull_request` | `labeled`, `unlabeled`, `edited` | `handle` | Re-evaluate the same commit and enqueue what became eligible. Never posts a `Skipped` row: the commit has not changed, so it would overwrite a result already published for it (`PrAction.reportsSkips`). |
| `pull_request` | `closed` (incl. merged) | `handle` -> `cancelBuildsForClosedPr` | Remove the builds still queued for the PR head, and stop the ones still running (`ObsoleteBuildPolicy`). |
| `pull_request_review` | `submitted` / `state=approved` | `handleReviewApproved` | Enqueue run-on-approval BuildTypes. |
| `pull_request_review_comment` | `created` | `handleCommentCommand` | Default comment-trigger event (inline PR diff comment): enqueue BuildTypes whose comment trigger phrase matches, if the author association is allowed. |
| `issue_comment` | `created` | `handleCommentCommand` | Same, for PR *conversation* comments. **Opt-in**: only delivered when the App has the **Issues** permission, which the plugin does not request by default. |
| `check_run` | `rerequested` | `handleRerun` | Re-run the BuildType behind the clicked Check Run (ignoring finished builds). |
| `check_suite` | `rerequested` | `handleRerunAll` | "Re-run all checks": re-run every opted-in BuildType for that head, optionally only those whose last build there failed (`rerunAll.onlyFailed`). |

## Server settings applied live

`BridgeServerSettings` is the single typed accessor for every
server-global tuning value and feature flag, resolving each key in
order: plugin-owned settings file (set from the admin page) ->
legacy `teamcity.github.bridge.*` internal property -> compiled-in
default. Per-operation values (API version, HTTP retry budget, PR
info cache TTL and stale grace) are pushed into the live
`GitHubClient` and `PrInfoCache` beans by
`BridgeServerSettings.applyTo(...)`, which is called at startup and
again every time the admin saves settings - so edits take effect
without a TeamCity restart. Feature flags it gates include
`dryRun`, `metrics.enabled`, `webhook.replay.enabled`,
`prComment.enabled`, the external-API `api.token`, the
`repo.allowlist`, and `comment.allowedAssociations`.

## Data flow: inbound (GitHub -> TC)

```mermaid
sequenceDiagram
    autonumber
    participant GH as GitHub
    participant PWC as PluginWebhookController
    participant SV as SignatureVerifier
    participant WC as WebhookConfig
    participant WPP as WebhookPayloadParser
    participant RFR as PullRequestEventListener
    participant PM as ProjectManager
    participant Q as BuildQueue

    GH->>PWC: POST /webhook<br/>X-GitHub-Event, X-Hub-Signature-256
    PWC->>WC: secret()
    WC-->>PWC: secret value or null
    PWC->>SV: verify(payload, header, secret)
    SV-->>PWC: true | false
    alt false
        PWC-->>GH: 401 Invalid signature
    else true and event=pull_request
        PWC->>WPP: parsePullRequestEvent(payload)
        WPP-->>PWC: PrEventPayload | null<br/>(opened, ready_for_review, synchronize)
        alt payload != null
            PWC->>RFR: handle(payload)
            Note over RFR: skip if draft<br/>(opened/synchronize only)
            RFR->>PM: activeBuildTypes
            PM-->>RFR: List<SBuildType>
            loop matching build types
                RFR->>Q: addToQueue(promotion, "teamcity-github-bridge")
            end
        end
        PWC-->>GH: 200 OK
    end
```

## Data flow: outbound (TC -> GitHub)

```mermaid
sequenceDiagram
    autonumber
    participant Q as Build queue
    participant DAF as DraftAwareBuildFilter
    participant TR as TokenResolver
    participant OCM as OAuthConnectionsManager
    participant ATM as AppTokenMinter
    participant ATC as AppTokenCache
    participant PIC as PrInfoCache
    participant GC as GitHubClient
    participant API as GitHub REST

    Q->>DAF: canStart(queuedBuild)
    DAF->>TR: resolveAccessToken(project, connectionId, repo)
    TR->>OCM: findConnectionById(...)
    OCM-->>TR: OAuthConnectionDescriptor (params: appId, private key, ownerUrl)
    TR->>ATM: mint(appId, key, params, repo, apiBase)
    ATM->>ATC: get(installationId)
    alt cache hit
        ATC-->>ATM: cached ghs_* token
    else cache miss
        ATM->>GC: listInstallations(JWT, apiBase)
        GC->>API: GET /app/installations<br/>Authorization: Bearer <JWT>
        API-->>GC: [InstallationInfo...]
        GC-->>ATM: matching installation
        ATM->>GC: createInstallationToken(JWT, installationId, apiBase)
        GC->>API: POST /app/installations/{id}/access_tokens<br/>Authorization: Bearer <JWT>
        API-->>GC: { token: "ghs_*", expires_at: ... }
        GC-->>ATM: CreatedToken
        ATM->>ATC: put(installationId, token, expiresAt - safety margin)
    end
    ATM-->>TR: ghs_* token
    TR-->>DAF: ResolvedAccess { token, apiBase }
    DAF->>PIC: get(repo, prNumber, token, apiBase)
    alt cache miss
        PIC->>GC: getPr(token, repo, prNumber, apiBase)
        GC->>API: GET /repos/{owner}/{name}/pulls/{N}<br/>Authorization: Bearer ghs_*<br/>X-GitHub-Api-Version: 2022-11-28
        API-->>GC: 200 + JSON
        GC-->>PIC: PrInfo
    end
    PIC-->>DAF: PrInfo
    DAF-->>Q: SimpleWaitReason | null
```

## Threading model

- The TeamCity SDK calls our `StartBuildPrecondition` synchronously
  on the build queue thread. `DraftAwareBuildFilter.canStart` must
  return promptly, hence the cache layer in front of the HTTP call
  (a cold cache miss takes ~200 to ~800 ms).
- `PluginWebhookController.doHandle` is invoked on Jetty's
  request-handling thread. Verification and enqueuing complete in
  the same call.
- `PrInfoCache` uses `ConcurrentHashMap` for thread safety. The
  cache currently has no eviction policy beyond TTL on read; on a
  busy server with many PRs it stays bounded by the number of
  currently-open PRs touched by the precondition.

## Extension points

Where we plug into TC:

| TC interface / pattern | Used by |
|---|---|
| `ServerExtension` (lifecycle marker) | `TeamCityGitHubBridgePlugin` |
| `StartBuildPrecondition` | `DraftAwareBuildFilter` |
| `BaseController` + `WebControllerManager.registerController` | `PluginWebhookController`, `WebhookInfoController` |
| `OAuthConnectionsManager` (read-only) | `TokenResolver` |
| `ProjectConnectionCredentialsManager` (read-only, forward-compat) | `TokenResolver` |
| `OAuthConnectionDescriptor.parameters` (App ID + private key + ownerUrl) | `AppTokenMinter` |
| `ProjectManager.activeBuildTypes` | `PullRequestEventListener` |
| `BuildTypeEx.createBuildCustomizer` + `addToQueue` | `PullRequestEventListener` |
| `BuildServerAdapter` (build lifecycle) | `BuildStatusCheckRunPublisher`, `PrBuildEnricher`, `PrPromotionTagger`, `DraftCheckRunReporter` |
| `BuildFeature` (opt-in per BuildType) | `GitHubBridgeBuildFeature` |
| `EditProjectTab` + `AdminPage` (custom UI) | `BridgeProjectSettingsTab`, `AdminConsolePage`, `BranchEnrichmentPageExtension` |
| `ProjectTab` (project-level view) | `BridgeBuildsTab` — the "Branches & PRs" list |
| `SBuildType.resolvedSettings.buildFeatures` (read-only) | `BridgeFeatureReader`, `BundledPublisherDetector` |
| `AuthorizationInterceptor.addPathNotRequiringAuth` (anonymous endpoints) | `HealthController`, `MetricsController`, `ApiController`, `WebhookInfoController`, `PluginWebhookController` |

## Packaging

Build output is a single zip:

```text
teamcity-github-bridge-<version>.zip
├── teamcity-plugin.xml                        descriptor, at the root; its <version>
│                                              is filtered from the POM at build time
├── buildServerResources/                      the JSPs, served by the web layer
│   ├── admin/bridgeAdmin.jsp
│   ├── display/bridgeBranchEnrichment.jsp
│   ├── feature/bridgeFeatureEdit.jsp
│   ├── project/bridgeBuilds.jsp
│   └── project/bridgeProjectSettings.jsp
└── server/
    ├── teamcity-github-bridge-<version>.jar   our code + the Spring XML
    ├── kotlin-stdlib-1.9.25.jar
    ├── kotlin-reflect-1.7.22.jar
    ├── jackson-core-2.17.2.jar
    ├── jackson-databind-2.17.2.jar
    ├── jackson-annotations-2.17.2.jar
    ├── jackson-module-kotlin-2.17.2.jar
    ├── java-jwt-4.4.0.jar
    └── annotations-13.0.jar
```

`teamcity-plugin.xml` declares
`<deployment use-separate-classloader="true"/>` so the bundled
Kotlin runtime and Jackson do not collide with whatever TeamCity
itself loads.

## Forward compatibility considerations

- **Token format**: tokens are treated as opaque strings throughout.
  The new `ghs_*` ~520-character stateless format introduced in 2026
  works without code changes. The opt-in
  `X-GitHub-Stateless-S2S-Token` header applies to the token
  issuance call which TeamCity performs internally - we never touch
  it. See `api/TokenResolver.kt`.
- **GitHub Enterprise**: `teamcity.github.bridge.api.base` is read at every
  call (no caching), so an Enterprise instance can be configured
  via a single internal property.
- **API version**: `X-GitHub-Api-Version` is parameterised as
  `teamcity.github.bridge.api.version`, defaulting to `2022-11-28`. Bumping
  is a config change, not a recompile.

## Cross-references

- See [security.md](security.md) for the trust boundaries and the
  signature-verification path in detail.
- See [development.md](development.md) for how to add a new bean
  or extend a flow.
