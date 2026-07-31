<!-- Keep this short. The interesting part is usually "why", which the code
cannot say for itself. -->

## What and why

<!-- What changes, and what problem it solves. If it fixes a reported symptom,
say what the symptom looked like on the pull request or in TeamCity. -->

## Checks

- [ ] `./dev package` passes (it runs the whole unit-test suite)
- [ ] New behaviour has a test — the pure decision, not the TeamCity plumbing
- [ ] `CHANGELOG.md` updated under the unreleased version, kept compact
- [ ] Documentation updated if a flag, a default or an observable behaviour moved
      (`doc/configuration.md` for knobs, `doc/architecture.md` if the layout moved)
- [ ] Nothing built on the host: everything through `./dev`

## Verified how

<!-- Unit tests only, or installed on a real TeamCity server? Say which, and on
what — github.com or GitHub Enterprise. Several bugs here were only ever
reproducible against a live server, so "unit tests only" is an honest and useful
answer, not a failing. -->
