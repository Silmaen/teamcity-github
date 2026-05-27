package io.github.dlachouette.teamcity.github.report

// As of v1.5.0 the reporter is a thin service (no longer a build-
// server listener); the suppression decision lives in
// `BridgeGate.decide` and is tested in BridgeGateTest. The reporter
// itself just turns a (SkipReason, prNumber, headRef) tuple into a
// CheckRunRequest and POSTs it — covered by SkipReasonTest for the
// title/summary template and by integration tests on the sandbox.
