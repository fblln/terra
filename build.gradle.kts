// Deliberately empty.
//
// Under Isolated Projects the root build must not configure its children — no
// `subprojects { }`, no `allprojects { }`. Shared decisions live in
// settings.gradle.kts and each project configures itself. The reward is that Gradle
// can configure projects in parallel and cache each one independently, so touching
// one project does not invalidate the configuration of the others.
