// ==============================================================
//  IndusWok POS — iOS app (single-file Swift, WKWebView wrapper)
//
//  HOW TO BUILD (needs a Mac with Xcode — there is no way around
//  Apple's signing; details in ios-app/README.md):
//    1. Xcode → File → New → Project → iOS → App
//         Product Name: IndusWokPOS
//         Interface: Storyboard, Language: Swift, no CoreData
//    2. Replace the generated ViewController.swift with THIS file.
//    3. Info.plist → add row "App Transport Security Settings" →
//         keep defaults (our site is HTTPS).
//       Signing & Capabilities → your Apple ID team (free account
//       OK for 7-day sideload; $99/yr Apple Developer for TestFlight/
//       Play-store-style distribution).
//    4. Build →安装 on your iPad/iPhone.
//
//  What it does: full-screen WKWebView of the live POS site.
//  window.print() from bills/KOT previews opens native AirPrint.
//  (Bluetooth thermal printing on iOS needs vendor SDKs per printer
//   model — for iPad billing keep RawBT-free flow = AirPrint to a
//   supported printer, or print from the Android app.)
// ==============================================================

import UIKit
import WebKit

class MainViewController: UIViewController, WKNavigationDelegate, WKUIDelegate {

    private let appURL = URL(string: "https://posinduswok.netlify.app/")!
    private var webView: WKWebView!

    override func viewDidLoad() {
        super.viewDidLoad()

        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        config.preferences.setValue(true, forKey: "allowFileAccessFromFileURLs")

        webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.customUserAgent = "IndusWokPOS/14.5 iOS"
        webView.scrollView.bounces = false
        webView.allowsBackForwardNavigationGestures = true

        view = webView
        webView.load(URLRequest(url: appURL))
    }

    // Keep every navigation inside the POS (bill print windows etc.)
    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        if let host = navigationAction.request.url?.host,
           host.contains("netlify.app") || host.contains("googleapis.com") || host.contains("gstatic.com") || host.contains("jsdelivr.net") {
            decisionHandler(.allow)
        } else {
            // external links (WhatsApp share, etc.) open in Safari
            decisionHandler(.cancel)
            if let url = navigationAction.request.url { UIApplication.shared.open(url) }
        }
    }

    // let window.print() target=_blank receipts open inline
    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction,
                 windowFeatures: WKWindowFeatures) -> WKWebView? {
        if navigationAction.targetFrame == nil { webView.load(navigationAction.request) }
        return nil
    }

    override var preferredStatusBarStyle: UIStatusBarStyle { .lightContent }
}
