import Foundation

public enum MusicService: CaseIterable {
    case appleMusic, spotify, youtube
    public var label: String {
        switch self {
        case .appleMusic: return "Apple Music"
        case .spotify: return "Spotify"
        case .youtube: return "YouTube"
        }
    }
}

/// Outbound discovery links. A canonical link is used only if the recognition engine supplied one AND its host is on the
/// allow-list for that service over HTTPS; otherwise a plain search URL is built. We never guess track URLs.
public enum ExternalLinks {
    private static let allowedHosts: [MusicService: Set<String>] = [
        .appleMusic: ["music.apple.com", "itunes.apple.com"],
        .spotify: ["open.spotify.com"],
        .youtube: ["www.youtube.com", "youtube.com", "music.youtube.com", "youtu.be"],
    ]

    public static func url(_ service: MusicService, for song: Song) -> URL? {
        if service == .appleMusic, let c = song.appleMusicURL, isAllowed(service, c), let u = URL(string: c) { return u }
        let q = enc(searchText(song))
        switch service {
        case .appleMusic: return URL(string: "https://music.apple.com/search?term=\(q)")
        case .spotify: return URL(string: "https://open.spotify.com/search/\(q)")
        case .youtube: return URL(string: "https://www.youtube.com/results?search_query=\(q)")
        }
    }

    public static func isAllowed(_ service: MusicService, _ url: String) -> Bool {
        guard let c = URLComponents(string: url), c.scheme == "https", let host = c.host?.lowercased() else { return false }
        return allowedHosts[service]?.contains(host) ?? false
    }

    /// Only https links to Wikipedia/Wikidata (or the services above) may be opened from content we fetched.
    public static func isSafeWebURL(_ url: String) -> Bool {
        guard let c = URLComponents(string: url), c.scheme == "https", let host = c.host?.lowercased() else { return false }
        return host.hasSuffix(".wikipedia.org") || host == "www.wikidata.org" || allowedHosts.values.contains { $0.contains(host) }
    }

    /// Plain "title artist" text used for searches: trimmed, whitespace collapsed.
    public static func searchText(_ song: Song) -> String {
        "\(song.title) \(song.artist)".split(whereSeparator: { $0.isWhitespace }).joined(separator: " ")
    }

    public static func wikipediaSearchURL(_ subject: String) -> URL? {
        URL(string: "https://en.wikipedia.org/w/index.php?search=\(enc(subject))")
    }

    /// Spotify's app-link handling can drop the query from a search URL, so the UI also offers the search text on the clipboard.
    public static func spotifyAppURL(_ song: Song) -> URL? { URL(string: "spotify:search:\(enc(searchText(song)))") }

    private static func enc(_ s: String) -> String {
        var allowed = CharacterSet.alphanumerics
        allowed.insert(charactersIn: "-._~")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }
}
