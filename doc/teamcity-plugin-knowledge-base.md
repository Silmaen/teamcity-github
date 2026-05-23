# TeamCity 2026.1 Knowledge Base — for the PR Draft Helper Plugin Project

Document de transfert. Tout ce qu'on a appris en refactorant la CI d'`Owl` qui
sert pour construire un plugin TC custom adressant les limites identifiées.

Lis-le une fois, puis reviens piocher quand tu codes. Les sections sont
indépendantes.

---

## Table des matières

1. [TC 2026.1 — architecture quickref](#1-tc-20261--architecture-quickref)
2. [Le système DSL Kotlin](#2-le-système-dsl-kotlin)
3. [Versioned Settings](#3-versioned-settings)
4. [Intégrations GitHub côté TC](#4-intégrations-github-côté-tc)
5. [Limites de TC 2026.1 — les trous fonctionnels](#5-limites-de-tc-20261)
6. [Workarounds DSL qu'on a déployés](#6-workarounds-dsl-quon-a-déployés)
7. [Service messages — la boîte à outils runtime](#7-service-messages-runtime)
8. [Build lifecycle & trigger pipeline](#8-build-lifecycle--trigger-pipeline)
9. [Webhook mechanics](#9-webhook-mechanics)
10. [Auth & tokens — formats et résolution](#10-auth--tokens)
11. [Branch handling & branchSpec](#11-branch-handling--branchspec)
12. [TC Plugin SDK essentials](#12-tc-plugin-sdk-essentials)
13. [Code recipes pour le plugin](#13-code-recipes-pour-le-plugin)
14. [Trapdoors & lessons learned](#14-trapdoors--lessons-learned)
15. [Reference URLs](#15-reference-urls)

---

## 1. TC 2026.1 — architecture quickref

- **Version** : TeamCity Professional 2026.1, build **222521**
- **Tous les plugins bundled** sont à `ver:222521` (synchronisé avec le serveur)
- **Plugins bundled** pertinents : `pull-requests`, `commit-status-publisher`,
  `github-app`, `jetbrains.git`, `recipes`, `investigations-auto-assigner`,
  `perfmon`
- **Plugin externe installé manuellement** : `teamcity-commit-hooks` (JetBrains
  open source, même build number)
- **Mode d'édition de la config** : Versioned Settings en mode "Use settings
  from VCS" (le DSL Kotlin est la source of truth)
- **DSL schema version** déclaré dans `settings.kts` : `version = "2026.1"`
- **Serveur** : déployé en Docker (image `jetbrains/teamcity-server`), volume
  monté sur `/data/teamcity_server/datadir`
- **Agents** : 3 (linux x64 nommés Artemis/Hephaistos + Windows x64 nommé
  Hecate). Pas d'agent ARM natif → ARM build via Docker emulation côté agent
  Linux x64

### IDs et noms

- ID racine projet : `Owl`
- ID VCS root : `Owl_HttpsGithubComSilmaenOwlGitRefsHeadsMain`
- IDs de buildType : préfixés par leur projet parent (ex. `Owl_Build_LinuxX64_Clang`)
- Préserver les IDs lors d'un refactor DSL est **critique** sinon perte
  d'historique de builds (utiliser `id("explicitName")` quand on renomme un
  Kotlin object)

---

## 2. Le système DSL Kotlin

### Comment ça marche

- Le serveur TC peut générer un export `settings.kts` à partir de la config UI
  actuelle (`<Project> → Actions → Download settings in Kotlin format`)
- L'export produit un dossier avec :
  - `settings.kts` (entry point)
  - `pom.xml` (Maven, pour compile validation)
  - `_Self/Project.kt` (projet racine)
  - `_Self/buildTypes/*.kt` (templates)
  - `_Self/vcsRoots/*.kt`
  - `<ProjectId>/Project.kt` + `<ProjectId>/buildTypes/*.kt` (sous-projets)

### Limites de l'export

- **IDs réservés Kotlin** font planter l'export : si un projet a l'ID
  `Owl_Package`, le générateur produit un object `Package` dans `Packaging/Project.kt`
  → `package` étant un mot-clé Kotlin, ça refuse. Fix : renommer l'ID TC ou utiliser un suffix non-keyword
- **Pas de placeholders idiomatiques** : l'export est verbeux et 1-buildType-1-fichier

### Refactor DSL — patterns qu'on a utilisés dans Owl

- **Matrix builds via data classes Kotlin** :
  ```kotlin
  private data class StdVariant(val idSuffix: String, ...)
  private val stdVariants = listOf(StdVariant("Clang", ...), StdVariant("Gcc", ...))
  ```
- **Helper functions** sur `BuildType` ou `Project` pour factor des patterns
- **Extension functions** sur `ScriptBuildStep` pour réutiliser de la
  boilerplate script :
  ```kotlin
  private fun ScriptBuildStep.ciAction(action: String, ...) { ... }
  ```
- **`private val` pour des sous-objets** au lieu de `object` quand on génère
  depuis un loop/data class

### Limites DSL Kotlin

- **`BuildSteps` extension function ne compile pas** dans certaines versions —
  utiliser une extension sur `ScriptBuildStep` à la place
- **`notEquals` n'existe pas** dans le `conditions { }` builder — utiliser
  l'inverse `equals("x", "false")` au lieu de `notEquals("x", "true")`
- **`doesNotEqual` n'existe pas non plus** — pareil
- **`doesNotMatch` existe** pour les regex
- **`ignoreDrafts = true`** dans `pullRequests { provider = github { ... } }` :
  syntaxiquement accepté, **silencieusement ignoré** au runtime quand l'auth
  est via storedToken GitHub App (cf. section 5)

---

## 3. Versioned Settings

- Active : `<Project> → Versioned Settings → Synchronization enabled`
- Modes :
  - **"Use settings from VCS"** : le DSL est la source of truth, l'UI devient
    read-only (ou les modifs UI sont écrasées à la prochaine sync VCS)
  - **"Two-way"** : modifs UI sont commit dans VCS automatiquement par TC
    → fragile, conflits possibles avec branch protection
- **Default branch** des settings : la default branch du VCS root (souvent main)

### Pièges Versioned Settings

- **Branch protection rule** sur main bloque le push automatique de TC quand
  il essaie de bootstrap le `.teamcity/` directory au moment d'activer
  Versioned Settings → solution : push manuellement le `.teamcity/` via PR
  AVANT d'activer
- **Modifs UI quand DSL gouverne** : si tu changes un champ via UI (ex. default
  branch d'un VCS root), TC mémorise la modif mais le DSL la réécrase à la
  prochaine sync → état inconsistant possible. Toujours passer par DSL.
- **Re-export** : tant que tu peux régénérer le DSL via Actions, l'état UI
  reste exportable même si tu ne peux pas le modifier

---

## 4. Intégrations GitHub côté TC

TC a **plusieurs mécanismes** GitHub coexistant. Tous les comprendre est clé
pour le plugin :

### 4.1 Connections (Administration → Connections)

| Type connection | Fait quoi | Utilisé chez nous |
|---|---|---|
| **GitHub.com OAuth App** | Login user via OAuth (chaque user TC se connecte) | Présent (`PROJECT_EXT_8`), peu utilisé |
| **GitHub App** | Identité de service, jetons d'installation | Présent, principale |

La GitHub App donne :
- App ID
- Client ID + Secret
- Private key
- Webhook secret (optionnel)
- Callback URL : `https://<tc>/oauth/githubapp/` (pas `oauth/github.com/` qui est pour OAuth App !)

### 4.2 Build features GitHub-related

- **`commitStatusPublisher`** : poste des statuts sur les commits via API
  GitHub Commit Statuses
- **`pullRequests`** : track les PRs et enrichit les builds avec metadata
- **`teamcity-commit-hooks`** (plugin externe) : install webhooks sur les
  repos GitHub via API, redispatche les events arrivants vers TC

### 4.3 Triggers PR

- Implicite : le `pullRequests` feature ajoute des branches virtuelles
  `pull/N` que les triggers VCS standard peuvent matcher
- Pas de `prTrigger` explicite — c'est `vcsTrigger` + branchFilter

### 4.4 Webhook routing

- `teamcity-commit-hooks` plugin pose un webhook par-repo (Settings → Webhooks
  sur le repo GitHub)
- L'URL pointe vers `<tc>/app/hooks/github/<UUID>` (UUID = handle interne du
  plugin pour ce webhook)
- Quand webhook fire → plugin reçoit → notifie TC de re-check le VCS root
- **Le webhook ne triggge pas de build directement** ; il notifie un sync,
  ensuite TC décide de trigger ou pas

---

## 5. Limites de TC 2026.1

Voici **où le plugin doit intervenir**.

### 5.1 `teamcity.pullRequest.isDraft` non exposé

Le `pullRequests` feature populate :
- `teamcity.pullRequest.number`
- `teamcity.pullRequest.title`
- `teamcity.pullRequest.author`
- `teamcity.pullRequest.source.branch`
- `teamcity.pullRequest.target.branch`
- `teamcity.pullRequest.branch.pullrequests` (= numéro)

**Mais PAS `teamcity.pullRequest.isDraft`** alors que la doc l'évoque. Confirmé
par inspection des paramètres d'un build sur PR draft. Le plugin doit lire
l'état draft via API directement.

### 5.2 `ignoreDrafts = true` silently ignoré

Avec auth `storedToken { tokenId = "tc_token_id:CID_..." }` (= GitHub App
connection), TC fetch les PRs (incluant drafts) et trigge les builds dessus
**même si `ignoreDrafts = true` est set**. Comme si le champ DSL n'existait
pas runtime.

### 5.3 `commitStatusPublisher` envoie une description hardcodée

Source : `ChangeStatusUpdater.java` du plugin `commit-status-publisher` :
```java
return build.getBuildStatus().isSuccessful()
    ? DefaultStatusMessages.BUILD_FINISHED
    : DefaultStatusMessages.BUILD_FAILED;
```

Textes hardcodés possibles selon le state :
- "TeamCity build was queued" (queued)
- "TeamCity build started" (started)
- "TeamCity build finished" (success)
- "TeamCity build failed" (failure)

**Le `##teamcity[buildStatus text='...']` ne propage PAS** vers la description
du commit status GitHub. Le text custom est visible côté UI TC seulement.

### 5.4 Statuts limités côté GitHub

L'API **Commit Statuses** (legacy) supporte 4 états :
`pending`, `success`, `failure`, `error`.

→ Pas de `skipped` state visible côté GitHub.

L'API **Check Runs** (moderne) supporte plus de conclusions, dont
`skipped`, `neutral`, `cancelled`. Mais TC's `commitStatusPublisher` utilise
Commit Statuses, pas Check Runs.

### 5.5 Pas de retrigger automatique sur draft → ready

Quand l'user transitionne une PR de draft à ready :
1. GitHub fire `pull_request.ready_for_review`
2. Webhook arrive à TC via commit-hooks plugin
3. TC re-poll le VCS root, voit le même SHA (pas de nouveau commit)
4. **Rien de plus** — TC ne re-triggge pas les builds existants

→ Conséquence safety : si on a "skipped" 8 buildTypes en draft (en les
marquant SUCCESS via service message), ils restent verts même après le
passage en ready. L'user peut merger en pensant que la CI a tout validé,
alors que 8/12 buildTypes n'ont jamais tourné. **C'est le red flag majeur
qui motive ce plugin.**

### 5.6 Branch display via branchSpec uniquement

Pas d'API publique pour customiser le label de branche au runtime. Limité
à ce que les groupes de capture `(...)` dans la branchSpec donnent.

`+:refs/(pull/*)/head` → display `pull/189`. Pas mieux possible nativement.

### 5.7 `BuildSteps` extension functions

Faute de docs claire sur les classes d'extension, on a découvert empiriquement
que **certaines extension functions sur des classes TC DSL ne compilent pas**.
Ex. `private fun BuildSteps.foo()` → "Unresolved reference: BuildSteps". 
Contournement : extension sur les types concrets comme `ScriptBuildStep`.

---

## 6. Workarounds DSL qu'on a déployés

Ce que le plugin pourrait simplifier ou remplacer.

### 6.1 Le `DRAFT_PR_GUARD` step

Premier step natif de `GlobalBuild` template, avant Docker :
```bash
python3 ci_action.py CheckDraft %cmake_preset% -- \
    --pr=%teamcity.pullRequest.number% \
    --repo=Silmaen/Owl \
    --token=%github_access_token% \
    --allow-draft=%allow_draft_pr% \
    --build-number=%build.number%
```

`CheckDraft` action Python query l'API GitHub `GET /repos/{owner}/{repo}/pulls/{N}`,
récupère `draft`, et selon `allow_draft_pr` :
- Si draft + pas allowed → set `skip_pipeline=true` + `buildStatus SUCCESS`
- Sinon → continue

Échec strict (HTTP 401, network, etc.) → exit 1, build failed.

### 6.2 Le pattern `skip_pipeline`

Tous les steps suivants (Determine docker, Define Remote, Clean, Build, Test,
Coverage, Documentation, Package, Publish*, etc.) ont :
```kotlin
conditions { equals("skip_pipeline", "false") }
```

→ Si CheckDraft a flip à `true`, tous skip, le build finit SUCCESS en ~5s.

### 6.3 Enrichissement UI via service messages

Dans CheckDraft, après récupération des metadata GitHub :
```python
print(f"##teamcity[buildNumber '{build_num} {source_branch}']")
print(f"##teamcity[addBuildTag '{'draft' if is_draft else 'ready'}']")
print("##teamcity[buildStatus status='SUCCESS' text='OK (Skipped: draft PR)']")
```

Donne :
- Build number affiché : `#87 Feature/raycast-shadows`
- Tag : `draft` ou `ready`
- Status text TC : "OK (Skipped: draft PR)"

(Note : status text NE PROPAGE PAS à GitHub.)

### 6.4 `allow_draft_pr` param + helper Kotlin

```kotlin
// In template
checkbox("allow_draft_pr", "false", checked = "true", unchecked = "false")

// In Build.kt
private fun BuildType.allowDraftPR() {
    params { param("allow_draft_pr", "true") }
}
```

Les 3 buildTypes draft-friendly (LinuxX64 Clang, WindowsX64 Clang,
SanitizerAddress) appellent `allowDraftPR()`.

### 6.5 GitHub App connection + storedToken

Pour `commitStatusPublisher` :
```kotlin
authType = storedToken {
    tokenId = "tc_token_id:CID_<hash>:-1:<UUID>"
}
```

Le format `tc_token_id:CID_<hash>:-1:<UUID>` est interne TC. CID = Connection
ID hash. `-1` = user ID (-1 = service token sans user). UUID = token identifier.

---

## 7. Service messages — runtime

Tous émis depuis stdout d'un script step, format `##teamcity[name attr='value']`.
Utilisés par le plugin.

| Service message | Effet |
|---|---|
| `setParameter name='X' value='Y'` | Set runtime un build param. Visible dans tous les steps suivants. |
| `buildNumber 'XYZ'` | Override le build number affiché. TC préfixe automatiquement de `#`. |
| `buildStatus status='SUCCESS' text='...'` | Force le status (SUCCESS / FAILURE / ERROR) + le status text TC (mais pas GitHub). |
| `buildStop comment='...' readdToQueue='false'` | Stop le build. **Marque comme CANCELLED** (red sur GitHub). À éviter pour les skips. |
| `addBuildTag 'tag'` | Ajoute un tag filtrable au build. |
| `addBuildProblem identity='X' description='...'` | Ajoute un problème (cause failure). |
| `notification notifier='slack' message='...' sendTo='...'` | Notif custom. |

---

## 8. Build lifecycle & trigger pipeline

```
1. VCS poll OR webhook arrives
   ↓
2. TC computes new revisions to track
   ↓
3. For each (buildType, revision) needing a build:
   3a. Check trigger filters (branchFilter, triggerRules)
   3b. Check `BuildStartingFilter` chain (plugin hook point)
   3c. Enqueue with priority weight
   ↓
4. Build queue optimizer dedupes (same revision, same buildType)
   ↓
5. Build distributed to agent
   ↓
6. Steps run in order
   6a. Each step's conditions evaluated
   6b. If conditions fail → step status `skipped`
   6c. If service message changes state → applied
   ↓
7. Build finishes
   7a. `commitStatusPublisher` posts final status
   7b. Tags / metadata persisted
```

### Points d'extension plugin

- `BuildStartingFilter` : hook avant enqueue, peut SKIP/CONTINUE
- `BuildPromotionManagerEx` / `BuildQueueEx` : API pour enqueue programmatiquement
- `BuildServerAdapter` : listener for build events (started/finished/etc)
- `RepositoryStateListener` : listener for VCS state changes
- `OAuthTokensStorage` : access aux stored tokens
- `WebControllerManager` : pour exposer endpoints HTTP custom

---

## 9. Webhook mechanics

### Configuration

- **Niveau repo (via commit-hooks plugin)** :
  - URL : `<tc>/app/hooks/github/<UUID>`
  - L'UUID est généré par le plugin par-webhook ; lié à un VCS root
  - Lié à l'identité user qui a cliqué "Install" dans TC UI
- **Niveau App** :
  - URL : configurable dans la page de l'App GitHub
  - **TC 2026.1 n'a PAS d'endpoint natif documenté qui accepte les events
    App-level**. On a testé `/app/oauth/githubapp/webhook` et autres → 404.
  - L'endpoint `commit-hooks` `/app/hooks/github/<UUID>` est lié à un webhook
    SPÉCIFIQUE (UUID), donc déconseillé pour App-level (le UUID devient
    invalide si on supprime le repo webhook)

### Events traités par commit-hooks

- `push` → trigge VCS check-for-changes sur le VCS root concerné
- `pull_request` (varies sub-actions) → notifie le `pullRequests` feature
- `ping` → just ACK 200
- `check_suite`, etc. → "Received unsupported event type, ignoring"

### Polling fallback

Si webhook absent ou échoue, polling kick in à `teamcity.vcsRootCheckingInterval`
(défaut 60s, modifiable via Internal Properties). Pour PRs spécifiquement
via la feature `pullRequests` : `teamcity.pullRequest.checkChangesInterval`
(nom à vérifier — empiriquement réagit en 30s chez Silmaen).

---

## 10. Auth & tokens

### Formats observés

| Format | Origine | Usage |
|---|---|---|
| `ghp_xxxx...` | PAT classic GitHub | Auth API direct |
| `github_pat_11ABCDEFG_xxxx` | PAT fine-grained | Idem, scopes plus précis |
| `tc_token_id:CID_<hash>:-1:<UUID>` | TC stored token via GitHub App | Référencé dans `storedToken { tokenId = "..." }` |
| `credentialsJSON:<UUID>` | TC credentials manager | Référencé dans `token { token = "credentialsJSON:..." }` |

### Sources de tokens dans TC

- **User OAuth tokens** : `<user>/Profile → OAuth Tokens` — par-user, scope OAuth App
- **Stored tokens** : `<project>/Connections → <conn> → Generate Token` — par-connection, scope App
- **VCS root credentials** : RSA key, BASIC auth, anonymous — pour git operations only, **pas pour API REST**

### Flow d'auth GitHub App connection

```
TC stored token request
   ↓
TC signs JWT with App private key
   ↓
TC sends JWT to GitHub: POST /app/installations/<id>/access_tokens
   ↓
GitHub returns short-lived installation token (1h TTL)
   ↓
TC uses token for API calls
   ↓
Token expires → TC renouvelle silencieusement
```

### Permissions GitHub App utilisées dans notre setup

| Permission | Niveau |
|---|---|
| Actions | Read |
| Checks | Read & write |
| Commit statuses | Read & write |
| Contents | Read |
| Issues | Read & write |
| Metadata | Read |
| Pull requests | Read & write |
| Webhooks | Read & write (ajouté plus tard pour commit-hooks plugin) |

---

## 11. Branch handling & branchSpec

### Syntaxe branchSpec

```
+:refs/heads/(main)                   # match exact, display "main"
+:refs/heads/(Feature/*)              # match Feature/*, display "Feature/xxx"
+:refs/pull/(*)/head                  # capture le numéro: display "189"
+:refs/(pull/*)/head                  # capture "pull/*": display "pull/189"
+:refs/(pull/*/head)                  # capture full: display "pull/189/head"
-:refs/heads/Experiment/*             # exclude
```

Règle : ce qui est entre parenthèses devient le "display name" / "logical
branch name". Sans parens, le `*` matched seul est le display.

### Comportement spécifique aux PR refs

- GitHub conserve `refs/pull/N/head` même après merge/close
- Le `pullRequests` feature étend dynamiquement la branchSpec pour les PRs
  open, **mais l'expérience pratique** montre qu'il faut souvent les déclarer
  explicitement pour que TC les fetch
- Quand on ajoute `+:refs/pull/*/head` à branchSpec pour la première fois,
  TC fetch d'un coup TOUS les PR refs historiques → trigge un build par
  PR sur tous les buildTypes éligibles. Effet one-shot, lourd mais ponctuel.

### Filtres par branche au niveau trigger

```kotlin
triggers {
    vcs {
        branchFilter = """
            +:Feature/*
            -:Feature/*-WIP
        """.trimIndent()
    }
}
```

Quand le `pullRequests` feature enrichit, `teamcity.pullRequest.source.branch`
est exposé. Donc on peut filtrer par pattern de **source branch name**, pas
juste le ref `pull/N`.

---

## 12. TC Plugin SDK essentials

### Setup projet

- Maven parent : `org.jetbrains.teamcity:teamcity-sdk-maven-plugin`
- Téléchargement SDK : auto via Maven
- Langage : Java ou Kotlin (Kotlin recommandé pour conciseness)

### Structure d'un plugin server-side

```
plugin.zip
├── teamcity-plugin.xml         # descriptor, declares server/agent modules
└── server/
    └── plugin.jar              # ton code compilé + dépendances
```

### `teamcity-plugin.xml` minimal

```xml
<?xml version="1.0" encoding="UTF-8"?>
<teamcity-plugin xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
    <info>
        <name>pr-draft-helper</name>
        <display-name>PR Draft Helper</display-name>
        <description>Handles GitHub PR draft state for trigger and retrigger</description>
        <version>0.1.0</version>
        <vendor>
            <name>Silmaen</name>
        </vendor>
        <email>...</email>
        <min-build>222521</min-build>
    </info>
    <deployment use-separate-classloader="true"/>
    <parameters>
        <parameter name="prdrafthelper.github.api.base">https://api.github.com</parameter>
    </parameters>
</teamcity-plugin>
```

### Spring DI declaration

`server/src/main/resources/META-INF/build-server-plugin-prdrafthelper.xml` :

```xml
<?xml version="1.0" encoding="UTF-8"?>
<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
       xsi:schemaLocation="http://www.springframework.org/schema/beans
           http://www.springframework.org/schema/beans/spring-beans.xsd"
       default-autowire="constructor">
    <bean class="net.argawaen.tcpr.PrDraftHelperPlugin"/>
    <bean class="net.argawaen.tcpr.github.GitHubClient"/>
    <bean class="net.argawaen.tcpr.github.PrInfoCache"/>
    <bean class="net.argawaen.tcpr.retrigger.ReadyForReviewListener"/>
    <bean class="net.argawaen.tcpr.filter.DraftAwareBuildFilter"/>
</beans>
```

### Hooks utiles

| Interface TC | Pour quoi faire |
|---|---|
| `BuildStartingFilter` | Pre-build filter, peut SKIP/CONTINUE/WAIT |
| `BuildServerAdapter` | Listener for `buildFinished`, `buildStarted`, etc. |
| `RepositoryStateListener` | VCS state change events |
| `PullRequestExtensionFactory` | Ajouter du metadata aux PRs |
| `BuildFeature` | Définir une nouvelle build feature avec UI config |
| `BuildBranchInfoProvider` | Customiser le display branch name |
| `OAuthTokensStorage` | Read tokens stored via connections |

### Logging

```kotlin
private val LOG = Logger.getInstance(PrDraftHelperPlugin::class.java.name)
LOG.info("Plugin initialized")
LOG.warn("Could not reach GitHub: ${e.message}")
```

Logs visibles dans `teamcity-server.log` (Diagnostics → Server Logs).

### Build & install

```bash
mvn package -P deploy-to-teamcity
# OU manuellement:
cp target/plugin.zip <TC Data Dir>/plugins/
# Restart TC
```

---

## 13. Code recipes pour le plugin

### 13.1 Query GitHub API depuis le plugin

```kotlin
class GitHubClient(
    private val tokensStorage: OAuthTokensStorage,
    private val projectManager: ProjectManager,
) {
    fun getPr(repo: String, number: Int, connectionId: String): PrInfo? {
        val token = getInstallationToken(connectionId) ?: return null
        val url = URL("https://api.github.com/repos/$repo/pulls/$number")
        val conn = url.openConnection() as HttpURLConnection
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.connectTimeout = 5000
        conn.readTimeout = 10000
        return when (conn.responseCode) {
            200 -> {
                val json = conn.inputStream.bufferedReader().readText()
                parsePrInfo(json)
            }
            else -> {
                LOG.warn("GitHub API returned ${conn.responseCode} for PR $repo#$number")
                null
            }
        }
    }
    
    private fun getInstallationToken(connectionId: String): String? {
        // TC API to retrieve the installation token for this connection
        // (uses the App's private key under the hood)
        return tokensStorage.getStoredToken(connectionId)?.accessToken
    }
}

data class PrInfo(
    val number: Int,
    val title: String,
    val author: String,
    val headRef: String,
    val baseRef: String,
    val draft: Boolean,
    val state: String,  // "open", "closed"
)
```

### 13.2 Enqueue un build via TC API

```kotlin
class BuildEnqueuer(
    private val buildQueue: BuildQueueEx,
    private val projectManager: ProjectManager,
) {
    fun enqueue(buildTypeId: String, branchName: String, comment: String) {
        val buildType = projectManager.findBuildTypeByExternalId(buildTypeId)
            ?: throw IllegalArgumentException("BuildType not found: $buildTypeId")
        
        val customizer = (buildType as BuildTypeEx).createBuildCustomizer(null)
        customizer.setDesiredBranchName(branchName)
        customizer.setBuildComment(comment)
        
        val promotion = customizer.createPromotion()
        buildQueue.addToQueue(promotion, "PrDraftHelperPlugin")
    }
}
```

### 13.3 Listen for webhook events from commit-hooks plugin

Le plugin `teamcity-commit-hooks` ne fournit **pas** d'API extension directe.
Alternatives :

1. **Polling indirect** via `RepositoryStateListener` : check les changements
   d'état de PR à chaque sync
2. **Webhook secondaire** : configurer un autre webhook GitHub qui pointe
   vers un endpoint custom de ton plugin (via `WebControllerManager`)

```kotlin
class PluginWebhookController(
    webManager: WebControllerManager,
    private val readyHandler: ReadyForReviewListener,
) : BaseController() {
    init {
        webManager.registerController("/app/prdrafthelper/webhook", this)
    }
    
    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        val event = request.getHeader("X-GitHub-Event")
        val payload = request.reader.readText()
        if (event == "pull_request") {
            val action = parseAction(payload)
            if (action == "ready_for_review") {
                readyHandler.handle(parsePayload(payload))
            }
        }
        response.status = HttpServletResponse.SC_OK
        return null
    }
}
```

### 13.4 Filter builds avant enqueue

```kotlin
class DraftAwareBuildFilter(
    private val gitHubClient: GitHubClient,
    private val prInfoCache: PrInfoCache,
) : BuildStartingFilter {
    
    override fun isAllowedToStart(build: SBuild): WaitReason? {
        val branchName = build.branch?.name ?: return null
        if (!branchName.startsWith("pull/")) return null
        val prNumber = branchName.removePrefix("pull/").toIntOrNull() ?: return null
        
        val customIgnoreDrafts = build.buildType?.getParameterValue("customIgnoreDrafts") == "true"
        if (!customIgnoreDrafts) return null
        
        val pr = prInfoCache.get("Silmaen/Owl", prNumber) ?: return null
        if (pr.draft) {
            return CustomWaitReason("PR #$prNumber is draft, suppressing build")
        }
        return null
    }
}
```

### 13.5 Custom build feature avec UI config

```kotlin
class CustomIgnoreDraftsFeature : BuildFeature() {
    override fun getType() = "prDraftHelper.customIgnoreDrafts"
    override fun getDisplayName() = "Ignore Draft PRs (custom)"
    override fun getEditParametersUrl() = "/plugins/prDraftHelper/editFeature.html"
    override fun describeParameters(params: Map<String, String>): String {
        return "Skip builds when PR is in draft state"
    }
}
```

---

## 14. Trapdoors & lessons learned

Pièges concrets qu'on s'est mangés en refactorant Owl :

### 14.1 Versioned Settings bootstrap

Activer Versioned Settings sans `.teamcity/` dans le repo → TC essaie de push
un commit auto-bootstrap vers main → bloqué par branch protection →
TC retry indéfiniment → état coincé.

Fix : commit le `.teamcity/` via PR humaine AVANT d'activer.

### 14.2 IDs réservés Kotlin

Si tu nomme un projet `Owl_Package`, l'export DSL plante car ça génère un
`object Package` (mot-clé Kotlin). Renomme en `Owl_Packaging` ou similaire
AVANT d'exporter.

### 14.3 Webhook App-level URL inexistant

J'ai supposé `<tc>/app/oauth/githubapp/webhook` ou similaire. Tous testés
au curl → 404. TC 2026.1 n'a pas d'endpoint natif pour les webhooks
App-level. Seul `commit-hooks` plugin a un endpoint, mais lié à un UUID
par-repo.

### 14.4 storedToken pour pullRequests vs commitStatusPublisher

Le même `storedToken` marche pour `commitStatusPublisher` mais le
`ignoreDrafts` du `pullRequests` ne le respecte pas. Possible que le code
de `pullRequests` ne sache pas exchanger correctement le token via la
connection (à investiguer si tu veux fixer côté plugin TC officiel).

### 14.5 Le DSL DSL accepte ce que le runtime ignore

`ignoreDrafts = true` : compile et passe la validation TC, mais runtime
silencieux. Aucun warning dans les logs. Diagnostic uniquement par
observation comportementale.

### 14.6 BuildStop = Cancelled = Red on GitHub

`##teamcity[buildStop ...]` marque le build Cancelled. GitHub affiche
Cancelled comme red ❌. **À éviter pour les skips intentionnels**. Utiliser
le pattern `skip_pipeline` + conditions à la place.

### 14.7 PAT expirés invisibles

Un PAT GitHub stocké dans un TC param ne signale pas son expiration. Quand
il expire, les API calls retournent 401, et si ton code "fallback to assume
non-draft", tu ne sais pas que ton garde-fou ne marche plus. **Fail loud,
exit non-zero, sinon tu construis sur une assumption fragile**.

### 14.8 Initial PR refs burst

Premier `+:refs/pull/*/head` dans branchSpec → TC fetch TOUS les PR refs
historiques (188 chez Owl) → trigge un build par PR par buildType
éligible. ~hundreds de builds en queue. À anticiper, et **canceller en masse**
via TC UI (Build Queue → Cancel all).

### 14.9 conditions DSL — `notEquals` ne marche pas

`notEquals(...)` lève "Unresolved reference" à la compile. Utiliser
l'inverse `equals(..., "false")` avec un défaut `"false"`. Confusant car
l'autocomplétion peut le suggérer (selon version IntelliJ).

### 14.10 La branch column de TC est immuable

Pas d'API publique pour la customiser au runtime. Plugin "Pull Request
Extra Information" l'a fait il y a 7 ans, n'est plus maintenu. Si tu veux
customiser dans ton plugin, faudra investiguer `BuildBranchInfoProvider`
ou similaire API interne.

---

## 15. Reference URLs

### TC officiel

- [TeamCity Plugin Development docs](https://plugins.jetbrains.com/docs/teamcity/getting-started-with-plugin-development.html)
- [TC Kotlin DSL Documentation](https://teamcity.jetbrains.com/app/dsl-documentation/index.html)
- [TC API javadoc](https://teamcity.jetbrains.com/javadoc/)
- [TC Help — Pull Requests](https://www.jetbrains.com/help/teamcity/pull-requests.html)
- [TC Help — Commit Status Publisher](https://www.jetbrains.com/help/teamcity/commit-status-publisher.html)
- [TC Help — Configuring Connections](https://www.jetbrains.com/help/teamcity/configuring-connections.html)

### Plugins ref (à lire pour comprendre les patterns)

- [JetBrains/commit-status-publisher](https://github.com/JetBrains/commit-status-publisher) — source du publisher
- [JetBrains/teamcity-commit-hooks](https://github.com/JetBrains/teamcity-commit-hooks) — source du plugin webhook (installé chez nous)
- [JetBrains/teamcity-webhooks](https://github.com/JetBrains/teamcity-webhooks) — différent plugin générique webhook
- [Nicologies/PrExtras](https://github.com/Nicologies/PrExtras) — Pull Request Extra Information (mort mais lecture éducative)

### GitHub API

- [GitHub REST API — Pull Requests](https://docs.github.com/en/rest/pulls/pulls)
- [GitHub REST API — Statuses](https://docs.github.com/en/rest/commits/statuses)
- [GitHub REST API — Check Runs](https://docs.github.com/en/rest/checks/runs)
- [GitHub App authentication](https://docs.github.com/en/apps/creating-github-apps/authenticating-with-a-github-app)

### Notre code Owl (référence)

- `.teamcity/_Self/buildTypes/GlobalBuild.kt` — template avec le pattern skip_pipeline
- `.teamcity/Build/Build.kt` — matrix builds via data classes Kotlin
- `ci/actions/check_draft.py` — current workaround Python, ce que le plugin
  remplacera

---

## Pour démarrer ton plugin demain

1. Lis sections 1, 5, 6 en priorité (ce que tu remplaces)
2. Lis section 12 pour le SDK setup
3. Code la phase 1 (F1 = retrigger sur ready) avec sections 13.2 + 13.3
4. Quand tu galères : `Diagnostics → Server Logs` te dira ce qui se passe
5. Pour debug : `<TC Data Dir>/logs/teamcity-server.log` est ta meilleure friend

Bonne chance. Et n'oublie pas de mettre la license sur le repo dès le jour 1.
