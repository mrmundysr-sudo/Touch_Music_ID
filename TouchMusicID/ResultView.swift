import SwiftUI
import UIKit
import UniformTypeIdentifiers

private enum ResultTab: Int, CaseIterable {
    case artistWiki, songWiki, lyrics
    var title: String {
        switch self {
        case .artistWiki: return "Artist Wiki"
        case .songWiki: return "Song Wiki"
        case .lyrics: return "Lyrics"
        }
    }
}

/// Song result: header, "Listen on" links, and three permanent tabs (Artist Wiki, Song Wiki, Lyrics). Mirrors Android's ResultScreen.
struct ResultView: View {
    let songKey: String
    @EnvironmentObject private var model: AppModel
    @State private var tab: ResultTab = .artistWiki

    private var current: ResultUi? {
        guard let r = model.result, r.item.song.key == songKey else { return nil }
        return r
    }

    var body: some View {
        Group {
            if let current {
                content(current)
            } else {
                ProgressView().tint(Tmi.primary).frame(maxWidth: .infinity, minHeight: 200)
            }
        }
        .navigationTitle("Song")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .navigationBarTrailing) {
                if let current {
                    let fav = model.isFavorite(songKey)
                    Button { model.toggleFavorite(current.item) } label: {
                        Image(systemName: fav ? "heart.fill" : "heart").foregroundColor(fav ? Tmi.error : Tmi.textHigh)
                    }
                    .accessibilityLabel(fav ? "Remove from favorites" : "Add to favorites")
                    ShareLink(item: shareText(current.item.song)) { Image(systemName: "square.and.arrow.up") }
                        .accessibilityLabel("Share")
                }
            }
        }
        .tmiScreen()
        .onAppear { model.openSong(songKey) }
        .onDisappear { model.closeResult() }
    }

    private func shareText(_ s: Song) -> String {
        var text = "\(s.title) — \(s.artist)"
        if let u = s.appleMusicURL, ExternalLinks.isAllowed(.appleMusic, u) { text += "\n\(u)" }
        return text
    }

    @ViewBuilder
    private func content(_ r: ResultUi) -> some View {
        FollowScroller(follow: model.follow) {
            VStack(spacing: Tmi.gap) {
                ResultHeader(item: r.item)
                LinkRow(song: r.item.song)

                Picker("Section", selection: $tab) {
                    ForEach(ResultTab.allCases, id: \.self) { Text($0.title).tag($0) }
                }
                .pickerStyle(.segmented)

                switch tab {
                case .artistWiki: artistTab(r)
                case .songWiki: songTab(r)
                case .lyrics: LyricsCard(state: r.lyrics, song: r.item.song)
                }
            }
            .padding(.horizontal, Tmi.screenPadding)
            .padding(.vertical, 8)
            .padding(.bottom, 16)
        }
    }

    @ViewBuilder
    private func artistTab(_ r: ResultUi) -> some View {
        switch r.knowledge {
        case .loading: LoadingCard()
        case .error(let reason): KnowledgeErrorCard(reason: reason) { model.retryKnowledge() }
        case .ready(let bundle, let freshness):
            if freshness == .cacheStaleOffline { OfflineBanner(fetchedAt: bundle.fetchedAt) { model.retryKnowledge() } }
            LookupSection(title: "About the artist", subject: r.item.song.artist, lookup: bundle.artist,
                          onChoose: { model.chooseArtist($0) }, onRetry: { model.retryKnowledge() })
            SourcesCard(bundle: bundle)
        }
    }

    @ViewBuilder
    private func songTab(_ r: ResultUi) -> some View {
        switch r.knowledge {
        case .loading: LoadingCard()
        case .error(let reason): KnowledgeErrorCard(reason: reason) { model.retryKnowledge() }
        case .ready(let bundle, _):
            LookupSection(title: "About the song", subject: r.item.song.title, lookup: bundle.song,
                          onChoose: nil, onRetry: { model.retryKnowledge() })
            SourcesCard(bundle: bundle)
        }
    }
}

// MARK: Header + links

private struct ResultHeader: View {
    let item: LibrarySong
    var body: some View {
        VStack(spacing: 6) {
            AlbumArtView(url: item.song.artworkURL)
                .shadow(color: Tmi.primary.opacity(0.3), radius: 16)
                .padding(.bottom, 8)
            Text(item.song.title).font(.title.weight(.bold)).foregroundColor(Tmi.textHigh).multilineTextAlignment(.center)
            Text(item.song.artist).font(.title3.weight(.semibold)).foregroundColor(Tmi.primary).multilineTextAlignment(.center)
            if let album = item.song.album { Text(album).font(.subheadline).foregroundColor(Tmi.textMedium) }
            if item.source == .demo { Pill(text: "Demo result · not real recognition", color: Tmi.warm) }
            Text(item.timestamp.formatted(date: .abbreviated, time: .shortened)).font(.caption).foregroundColor(Tmi.textLow)
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }
}

private struct LinkRow: View {
    let song: Song
    @Environment(\.openURL) private var openURL
    @State private var notice: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Listen on").font(.caption).foregroundColor(Tmi.textLow)
            HStack(spacing: 8) {
                ForEach(MusicService.allCases, id: \.self) { svc in
                    TmiButton(title: svc.label, primary: false, compact: true) { open(svc) }
                }
            }
            Text(notice ?? "Links open a search for this song. Availability depends on each service.")
                .font(.caption).foregroundColor(Tmi.textLow)
        }
    }

    private func open(_ svc: MusicService) {
        if svc == .spotify {
            // Spotify can drop the query from a search link, so the search text is also copied for pasting.
            UIPasteboard.general.string = ExternalLinks.searchText(song)
            notice = "Copied “\(ExternalLinks.searchText(song))”. If Spotify's search is empty, paste it."
            if let app = ExternalLinks.spotifyAppURL(song) {
                UIApplication.shared.open(app) { ok in
                    if !ok, let web = ExternalLinks.url(.spotify, for: song) { openURL(web) }
                }
                return
            }
        }
        if let url = ExternalLinks.url(svc, for: song) { openURL(url) } else { notice = "Couldn't open \(svc.label)" }
    }
}

// MARK: Knowledge cards

private struct LoadingCard: View {
    var body: some View {
        GlassCard {
            HStack(spacing: 14) {
                ProgressView().tint(Tmi.primary)
                Text("Looking up verified facts on Wikipedia and Wikidata…").font(.subheadline).foregroundColor(Tmi.textMedium)
            }
        }
    }
}

private func failureTitle(_ reason: FailureReason?) -> String {
    switch reason {
    case .offline: return "You're offline"
    case .rateLimited: return "Wikipedia asked us to slow down"
    case .server: return "Wikipedia isn't responding"
    default: return "Couldn't load facts"
    }
}

private struct KnowledgeErrorCard: View {
    let reason: FailureReason
    let onRetry: () -> Void
    var body: some View {
        GlassCard {
            HStack(spacing: 10) {
                Image(systemName: "exclamationmark.triangle.fill").foregroundColor(Tmi.warm)
                Text(failureTitle(reason)).font(.headline).foregroundColor(Tmi.textHigh)
            }
            Text("The song details above are saved. Facts will load when the connection is back.")
                .font(.subheadline).foregroundColor(Tmi.textMedium)
            TmiButton(title: "Retry", systemImage: "arrow.clockwise", action: onRetry)
        }
    }
}

private struct OfflineBanner: View {
    let fetchedAt: Date
    let onRetry: () -> Void
    var body: some View {
        GlassCard {
            Text("Showing saved facts from \(fetchedAt.formatted(date: .abbreviated, time: .omitted)) because Wikipedia couldn't be reached.")
                .font(.subheadline).foregroundColor(Tmi.textMedium)
            TmiButton(title: "Refresh", systemImage: "arrow.clockwise", primary: false, action: onRetry)
        }
    }
}

private struct LookupSection: View {
    let title: String
    let subject: String
    let lookup: Lookup
    let onChoose: ((String) -> Void)?
    let onRetry: () -> Void
    @Environment(\.openURL) private var openURL

    var body: some View {
        switch lookup.status {
        case .verified:
            if let e = lookup.entity { EntityCard(title: title, entity: e) }
        case .ambiguous:
            GlassCard {
                SectionTitle(title)
                if lookup.candidates.isEmpty {
                    Text("More than one record matches “\(subject)”, so nothing is shown rather than guessing.")
                        .font(.subheadline).foregroundColor(Tmi.textMedium)
                } else {
                    Text("Several musicians or groups are called “\(subject)”. Pick the right one. Nothing is shown until you do.")
                        .font(.subheadline).foregroundColor(Tmi.textMedium)
                    ForEach(lookup.candidates, id: \.qid) { c in
                        TmiButton(title: [c.label, c.description].compactMap { $0 }.joined(separator: " · "),
                                  primary: false, enabled: onChoose != nil) { onChoose?(c.qid) }
                    }
                }
            }
        case .notFound:
            GlassCard {
                SectionTitle(title)
                CompactNotice(systemImage: "magnifyingglass",
                              title: "No verified article for “\(subject)”",
                              message: "We checked, and no Wikipedia or Wikidata entry passed our name and type checks. Nothing here is guessed.") {
                    TmiButton(title: "Search Wikipedia", primary: false, compact: true) {
                        if let u = ExternalLinks.wikipediaSearchURL(subject) { openURL(u) }
                    }
                    TmiButton(title: "Check again", systemImage: "arrow.clockwise", primary: false, compact: true, action: onRetry)
                }
            }
        case .lookupFailed:
            GlassCard {
                SectionTitle(title)
                CompactNotice(systemImage: "exclamationmark.triangle.fill",
                              title: lookup.failure == nil ? "Couldn't check right now" : failureTitle(lookup.failure),
                              message: "This says nothing about whether “\(subject)” has an article. Try again in a moment.") {
                    TmiButton(title: "Retry", systemImage: "arrow.clockwise", compact: true, action: onRetry)
                }
            }
        }
    }
}

private struct EntityCard: View {
    let title: String
    let entity: EntityKnowledge
    @Environment(\.openURL) private var openURL

    var body: some View {
        GlassCard {
            SectionTitle(title)
            Text(entity.name).font(.title2.weight(.semibold)).foregroundColor(Tmi.primary)
            if let d = entity.description { Text(d).font(.subheadline).foregroundColor(Tmi.textMedium) }
            if let extract = entity.extract {
                Text(extract).font(.body).foregroundColor(Tmi.textHigh)
                Pill(text: "From Wikipedia", color: Tmi.accent)
            } else {
                Text("No Wikipedia article is linked to this record.").font(.subheadline).foregroundColor(Tmi.textLow)
            }
            if !entity.facts.isEmpty {
                VStack(alignment: .leading, spacing: 8) {
                    ForEach(Array(entity.facts.enumerated()), id: \.offset) { _, f in FactRow(fact: f) }
                }
                .padding(.top, 4)
            }
            Text("How we matched this: \(entity.basis)").font(.caption).foregroundColor(Tmi.textLow)
            if let u = entity.wikipediaURL, ExternalLinks.isSafeWebURL(u), let url = URL(string: u) {
                TmiButton(title: "Read on Wikipedia", systemImage: "arrow.up.right.square", primary: false) { openURL(url) }
            }
        }
    }
}

private struct FactRow: View {
    let fact: Fact
    var body: some View {
        HStack(alignment: .top) {
            Text(fact.label).font(.subheadline).foregroundColor(Tmi.textLow).frame(maxWidth: .infinity, alignment: .leading)
                .layoutPriority(0.38)
            VStack(alignment: .leading, spacing: 2) {
                Text(fact.value).font(.subheadline).foregroundColor(Tmi.textHigh)
                Text(fact.source.label).font(.caption2).foregroundColor(Tmi.accent)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .layoutPriority(0.62)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct SourcesCard: View {
    let bundle: KnowledgeBundle
    @Environment(\.openURL) private var openURL

    var body: some View {
        let sources = bundle.sources()
        GlassCard {
            SectionTitle("Sources & attribution")
            if sources.isEmpty {
                Text("No sources were used because nothing could be verified.").font(.subheadline).foregroundColor(Tmi.textMedium)
            }
            ForEach(sources, id: \.url) { s in
                TmiButton(title: s.label, systemImage: "arrow.up.right.square", primary: false) {
                    if ExternalLinks.isSafeWebURL(s.url), let u = URL(string: s.url) { openURL(u) }
                }
            }
            Text(attributionWikipedia).font(.caption).foregroundColor(Tmi.textLow)
            Text(attributionWikidata).font(.caption).foregroundColor(Tmi.textLow)
            Text("Looked up \(bundle.fetchedAt.formatted(date: .abbreviated, time: .shortened)). Wikipedia and Wikidata are projects of the Wikimedia Foundation; Touch Music ID is not affiliated with them.")
                .font(.caption).foregroundColor(Tmi.textLow)
        }
    }
}

// MARK: Lyrics

private struct LyricsCard: View {
    let state: LyricsUiState
    let song: Song
    @EnvironmentObject private var model: AppModel
    @State private var showImporter = false
    @State private var importError: String?

    var body: some View {
        GlassCard {
            SectionTitle("Lyrics")
            switch state {
            case .idle:
                Text("Lyrics are optional. Load online (if enabled) or import a local LRC file.")
                    .font(.subheadline).foregroundColor(Tmi.textMedium)
                TmiButton(title: "Load lyrics") { model.loadLyrics() }
                importButton
            case .loading:
                HStack(spacing: 14) {
                    ProgressView().tint(Tmi.primary)
                    Text("Loading lyrics…").font(.subheadline).foregroundColor(Tmi.textMedium)
                }
            case .disabled:
                Text(model.onlineLyricsAllowedInThisBuild
                     ? "Online lyrics are off. Turn them on in Settings, or import a local LRC file for this song."
                     : "Online lyrics are not available in this build. You can still import a local LRC file for this song.")
                    .font(.subheadline).foregroundColor(Tmi.textMedium)
                importButton
            case .unavailable(let message):
                Text(message).font(.subheadline).foregroundColor(Tmi.warm)
                TmiButton(title: "Try again", primary: false) { model.loadLyrics() }
                importButton
            case .ready(let r):
                LyricsBody(result: r, song: song, follow: model.follow)
            }
            if let importError { Text(importError).font(.caption).foregroundColor(Tmi.warm) }
        }
        .fileImporter(isPresented: $showImporter, allowedContentTypes: [.plainText, .text, .data]) { result in
            switch result {
            case .success(let url):
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                if let data = try? Data(contentsOf: url), data.count < 2_000_000,
                   let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) {
                    importError = nil
                    model.importLocalLrc(text)
                } else {
                    importError = "Couldn't read that file. Choose a plain-text .lrc or .txt file."
                }
            case .failure:
                importError = "Couldn't open that file."
            }
        }
    }

    private var importButton: some View {
        TmiButton(title: "Import local LRC", systemImage: "doc.text", primary: false) { showImporter = true }
    }
}
