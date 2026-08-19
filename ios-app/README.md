# iOS — honest status & instructions

**Good news first:** on iPhone/iPad you do not need an app file at all —
Safari → Share → **Add to Home Screen** on https://posinduswok.netlify.app/
installs the real logo icon and runs full-screen like an app. This free route
works today and is what most staff devices should use.

**Why there is no ready-made .ipa in this repo:**
  Apple requires every iOS app install to be code-signed with an Apple
  Developer account. There is no legitimate path around it (any "free IPA"
  service would be signing your code with someone else's key = instant ban
  + nobody's orders should run through that).
  - Free Apple ID + Xcode on a Mac → app installs but expires after 7 days. Fine for testing.
  - Apple Developer Program ($99/year) → TestFlight + App Store + permanent installs.

**What's provided here:**
  `MainViewController.swift` — a complete single-file WKWebView app.
  Header comments contain the exact 4-step Xcode recipe (create project,
  paste file, sign, build). Build time ~10 minutes once you have Xcode.

If/when you get an Apple Developer account, message me and I'll add a proper
`xcodeproj` + GitHub Actions macOS build (TestFlight publishing included).
