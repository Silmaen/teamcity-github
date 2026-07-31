package io.github.dlachouette.teamcity.github.queue

// The cleaner's decision is delegated to BridgeGate in v1.5.0+; see
// BridgeGateTest for the per-branch / per-trigger matrix. The old
// `shouldRemove(pr)` helper is gone (the gate now decides based on
// the full BridgeFeatureConfig instead of just the PR's draft flag).
//
// Integration-level behaviour (queue removal + Skipped Check Run
// posting) requires TC SDK fixtures and is currently exercised
// manually on the Test_CI sandbox per CHANGELOG.
