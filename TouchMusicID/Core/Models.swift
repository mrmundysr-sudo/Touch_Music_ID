import Foundation

/// Where a recognition result came from. Demo results are synthetic and must always be labelled as such.
public enum RecognitionSource: String, Codable, Equatable {
    case shazamkit
    case demo
}

/// Pure data about an identified track.
public struct Song: Codable, Equatable, Hashable {
    public var title: String
    public var artist: String
    public var album: String?
    public var appleMusicURL: String?
    public var webURL: String?
    public var isrc: String?
    public var artworkURL: String?

    public init(title: String, artist: String, album: String? = nil, appleMusicURL: String? = nil,
                webURL: String? = nil, isrc: String? = nil, artworkURL: String? = nil) {
        self.title = title
        self.artist = artist
        self.album = album
        self.appleMusicURL = appleMusicURL
        self.webURL = webURL
        self.isrc = isrc
        self.artworkURL = artworkURL
    }

    /// Stable identity used for favorites and knowledge-cache lookups.
    public var key: String { songKey(title: title, artist: artist) }
}

/// Lower-cases, strips diacritics and punctuation noise, collapses whitespace.
public func normalizeText(_ input: String) -> String {
    let marks: [Unicode.GeneralCategory] = [.nonspacingMark, .spacingMark, .enclosingMark]
    var stripped = String.UnicodeScalarView()
    for scalar in input.decomposedStringWithCanonicalMapping.unicodeScalars where !marks.contains(scalar.properties.generalCategory) {
        stripped.append(scalar)
    }
    let lower = String(stripped).lowercased().replacingOccurrences(of: "&", with: " and ")
    var out = String.UnicodeScalarView()
    var lastWasSpace = true
    for scalar in lower.unicodeScalars {
        if CharacterSet.alphanumerics.contains(scalar) {
            out.append(scalar)
            lastWasSpace = false
        } else if !lastWasSpace {
            out.append(" ")
            lastWasSpace = true
        }
    }
    return String(out).trimmingCharacters(in: .whitespaces)
}

public func songKey(title: String, artist: String) -> String {
    normalizeText(title) + "||" + normalizeText(artist)
}

/// A song as it appears in History or Favorites.
public struct LibrarySong: Codable, Equatable, Identifiable {
    public var song: Song
    public var source: RecognitionSource
    public var timestamp: Date
    /// Present for history rows (so one identification can be deleted); nil for favorites.
    public var historyId: UUID?

    public var id: String { "\(historyId?.uuidString ?? "fav")-\(song.key)-\(timestamp.timeIntervalSince1970)" }

    public init(song: Song, source: RecognitionSource, timestamp: Date, historyId: UUID? = nil) {
        self.song = song
        self.source = source
        self.timestamp = timestamp
        self.historyId = historyId
    }
}

public enum FailureKind: String, Equatable {
    case network, token, audio, silence, timeout, unknown
}
