import SwiftUI

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var toast: String?

    private var version: String {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.1"
        let b = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "1"
        return "\(v) (\(b))"
    }

    var body: some View {
        ScrollView {
            VStack(spacing: Tmi.gap) {
                GlassCard {
                    SectionTitle("Recognition")
                    StatusRow(label: "Apple ShazamKit", value: "Built in", good: true)
                    StatusRow(label: "Live recognition", value: model.demoMode ? "Off (demo mode)" : "Available", good: !model.demoMode)
                    Toggle(isOn: $model.demoMode) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Demo mode").font(.headline).foregroundColor(Tmi.textHigh)
                            Text("Returns example songs so you can try the discovery experience. It does not listen and does not identify anything.")
                                .font(.caption).foregroundColor(Tmi.textMedium)
                        }
                    }
                    .tint(Tmi.primary)
                    NavigationLink(value: HelpRoute()) {
                        Text("Setup guide & what's blocked").font(.subheadline.weight(.semibold))
                            .frame(maxWidth: .infinity, minHeight: Tmi.minTouch)
                            .foregroundColor(Tmi.textHigh)
                            .background(Tmi.surfaceHigh.opacity(0.9))
                            .clipShape(RoundedRectangle(cornerRadius: 16))
                    }
                }

                GlassCard {
                    SectionTitle("Lyrics")
                    Toggle(isOn: Binding(
                        get: { model.enableOnlineLyrics && model.onlineLyricsAllowedInThisBuild },
                        set: { if model.onlineLyricsAllowedInThisBuild { model.enableOnlineLyrics = $0 } }
                    )) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Enable Online Lyrics").font(.headline).foregroundColor(Tmi.textHigh)
                            Text("Fetch synced lyrics on demand from LRCLIB when a song is identified. Off by default. Disabled in public release builds pending licensing review. Local LRC files work independently.")
                                .font(.caption).foregroundColor(Tmi.textMedium)
                        }
                    }
                    .tint(Tmi.primary)
                    .disabled(!model.onlineLyricsAllowedInThisBuild)
                    if !model.onlineLyricsAllowedInThisBuild {
                        Text("Online lyrics are disabled in this build.").font(.caption).foregroundColor(Tmi.warm)
                    }
                }

                GlassCard {
                    SectionTitle("Privacy")
                    Text("• The microphone is used only while you've tapped Listen, and stops when you cancel, leave the app, or a match arrives.\n" +
                         "• Raw audio is never saved or uploaded by this app.\n" +
                         "• History and favorites live only on this phone. No account, no analytics, no ads.\n" +
                         "• Looking up a song sends only its title and artist names to Wikipedia and Wikidata.\n" +
                         "• Online lyrics (when enabled) send only title and artist to LRCLIB. No permanent lyrics library is stored.")
                        .font(.subheadline).foregroundColor(Tmi.textMedium)
                }

                GlassCard {
                    SectionTitle("Your data")
                    TmiButton(title: "Clear demo results", primary: false) { model.clearDemoData(); toast = "Demo results removed" }
                    TmiButton(title: "Clear saved Wikipedia facts", primary: false) { model.clearKnowledgeCache(); toast = "Cached facts cleared" }
                    TmiButton(title: "Clear all history", primary: false) { model.clearHistory(); toast = "History cleared (favorites kept)" }
                    if let toast { Text(toast).font(.caption).foregroundColor(Tmi.success) }
                }

                GlassCard {
                    SectionTitle("About")
                    Text("Touch Music ID \(version)").font(.subheadline).foregroundColor(Tmi.textHigh)
                    Text("Mundy Morning Studios.").font(.caption).foregroundColor(Tmi.textMedium)
                    Text(attributionWikipedia).font(.caption).foregroundColor(Tmi.textLow)
                    Text(attributionWikidata).font(.caption).foregroundColor(Tmi.textLow)
                    Text("Song recognition uses Apple's ShazamKit. Touch Music ID is not affiliated with or endorsed by Apple, Shazam, Spotify, YouTube or the Wikimedia Foundation. Names are used only to describe where links lead.")
                        .font(.caption).foregroundColor(Tmi.textLow)
                }
            }
            .padding(.horizontal, Tmi.screenPadding)
            .padding(.vertical, 12)
        }
        .navigationTitle("Settings")
        .tmiScreen()
    }
}

private struct StatusRow: View {
    let label: String
    let value: String
    let good: Bool
    var body: some View {
        HStack {
            Text(label).font(.subheadline).foregroundColor(Tmi.textMedium)
            Spacer()
            Text(value).font(.subheadline).foregroundColor(good ? Tmi.success : Tmi.warm)
        }
        .accessibilityElement(children: .combine)
    }
}

struct HelpView: View {
    var body: some View {
        ScrollView {
            VStack(spacing: Tmi.gap) {
                HelpCard(title: "What works today",
                         body: "• Song recognition with Apple's ShazamKit (microphone)\n" +
                               "• Artist Wiki and Song Wiki from Wikipedia and Wikidata, with sources, caching and offline fallback\n" +
                               "• Lyrics tab: import a local LRC file; online lyrics are debug-only for now\n" +
                               "• History and favorites saved on this phone\n" +
                               "• Apple Music, Spotify and YouTube search links, and sharing\n" +
                               "• Album-art colors and blurred artwork background\n" +
                               "• Demo mode, which returns labelled example songs")
                HelpCard(title: "What is not verified yet",
                         body: "Live recognition has not been measured for accuracy on a physical iPhone by this project. " +
                               "ShazamKit also needs the ShazamKit app service enabled for this app's identifier in the Apple Developer account. " +
                               "If Listen reports a problem matching, check that first, then try Demo mode to confirm the rest of the app.")
                HelpCard(title: "How facts are checked",
                         body: "An artist is shown only if exactly one Wikidata record has the same name and is a musician or group. " +
                               "A song is shown only if its title and performer both match. If several records fit, you choose. " +
                               "If none fit, we say so instead of guessing.")
                HelpCard(title: "Lyrics",
                         body: "Online lyrics (LRCLIB) are switched off in public builds pending a licensing review. " +
                               "You can import your own .lrc or .txt file for a song from the Lyrics tab. Imported lyrics are kept only for this session.")
            }
            .padding(.horizontal, Tmi.screenPadding)
            .padding(.vertical, 8)
        }
        .navigationTitle("Setup guide")
        .navigationBarTitleDisplayMode(.inline)
        .tmiScreen()
    }
}

private struct HelpCard: View {
    let title: String
    let text: String

    init(title: String, body: String) {
        self.title = title
        self.text = body
    }

    var body: some View {
        GlassCard {
            SectionTitle(title)
            Text(text).font(.subheadline).foregroundColor(Tmi.textMedium)
        }
    }
}
