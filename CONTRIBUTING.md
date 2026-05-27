# Contributing to teamcity-github-bridge

Thanks for taking the time to look at the codebase. This document
describes how to develop, test, and ship a change.

## Build, test, package

The whole toolchain runs in Docker; nothing is installed on the
host other than `docker` and `git`.

```bash
./dev test       # JUnit 5 suite (152 tests at v1.5.0)
./dev compile    # mvn compile, no tests
./dev package    # mvn clean package -> target/teamcity-github-bridge-x.y.z.zip
./dev shell      # interactive bash in the maven container
./dev help       # full list
```

The Maven local repository lives in `.cache/m2` (git-ignored,
project-scoped). The first `./dev package` downloads roughly
500 MB of TeamCity SDK jars.

## Project layout

See [doc/development.md](doc/development.md#project-layout) for the
full source tree and [doc/architecture.md](doc/architecture.md) for
the component diagram and data-flow sequences.

## Coding conventions

- **Kotlin**, idiomatic. JVM target 21.
- **Constructor-only Spring injection.** No field injection, no
  setters. Beans are declared in
  `src/main/resources/META-INF/build-server-plugin-teamcity-github-bridge.xml`.
- **No `lateinit`.** `var` properties only when they are
  test-injection seams (e.g. `PrInfoCache.clock`).
- **Logging**: `Logger.getInstance(MyClass::class.java.name)`
  inside a `companion object`. Never log secrets, tokens, or the
  GitHub App private key.
- **Comments**: only when the *why* is non-obvious. Don't restate
  the code; don't reference issue numbers (PR descriptions are
  for that); don't add "TODO" markers without an open issue.
- **Tests**: `@Test` methods named with backticked sentences,
  arrange / act / assert structure. Every test class that loads a
  plugin class with `Logger.getInstance` must invoke
  `LoggerBootstrap.install()` in an `init { ... }` block (see
  [doc/development.md](doc/development.md#the-loggerbootstrap-indirection)).
- **No mocking framework.** Stub interfaces or extract pure
  helpers; see `PrPromotionTagger.computePlan` /
  `DraftCheckRunReporter.buildRequest` for the pattern.

## Adding a feature

1. Open or pick up an issue and sketch the design there first.
2. Branch off `main`.
3. Implement, ideally with the testable surface as a pure helper
   in a `companion object`.
4. Add unit tests.
5. Wire the bean in
   `src/main/resources/META-INF/build-server-plugin-teamcity-github-bridge.xml`
   if you added a new class.
6. Update the relevant `doc/*.md` page in the same change. The
   landing page (`README.md`) and `CHANGELOG.md` are updated at
   release time.
7. Run `./dev test && ./dev package`.
8. Open a PR with the change set in scope (one feature per PR).
   Include the SDK introspection output (`javap`, jar inspection)
   that motivated any new SDK usage so the next maintainer can
   verify the API contract.

## Inspecting the TeamCity SDK

Most surprises in this plugin came from undocumented or moving
parts of TC's SDK. Always check the bytecode before relying on
a method signature:

```bash
./dev shell
# inside the container
cd /workspace/.cache/m2
find . -name "*-2026.1.jar" | xargs -I {} unzip -l {} | grep TheClassYouNeed
javap -cp 'org/jetbrains/teamcity/server-openapi/2026.1/server-openapi-2026.1.jar' \
      -p jetbrains.buildServer.serverSide.SomeClass
```

## Releasing

The release flow is currently manual.

1. Verify `./dev test` is green.
2. Bump three places to the new version:
   - `pom.xml` `<version>`
   - `src/main/resources/teamcity-plugin.xml` `<version>`
   - `README.md` (badge + status section)
3. Add an entry at the top of `CHANGELOG.md`.
4. `./dev package` and smoke-test the zip on a non-prod TC
   instance.
5. Commit: `git commit -m "release vX.Y.Z"`.
6. Tag: `git tag vX.Y.Z && git push --tags`.
7. Create a GitHub Release from the tag, attach the zip.

[doc/roadmap.md#item-7---release-pipeline](doc/roadmap.md#item-7---release-pipeline)
tracks the work to automate this with GitHub Actions.

## Reporting issues

- For functional bugs: open a GitHub issue with the output of the
  admin page's **Run self-tests** button and the relevant excerpt
  from `<TC_DATA_DIR>/logs/teamcity-github-bridge.log`.
- For security issues: do **not** open a public issue. See the
  threat model in [doc/security.md](doc/security.md) for the
  expected reporting channel.

## Code of conduct

Be kind, be precise, and remember the next maintainer (which may
be you in six months) will appreciate clear PR descriptions.

## License

By contributing, you agree your contributions will be licensed
under the [Apache License 2.0](LICENSE).
