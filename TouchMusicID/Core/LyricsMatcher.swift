import Foundation

/*
 * Candidate scoring follows the shape used by LiveLyrics (https://github.com/Nurzhigit-dev/LiveLyrics, MIT), as ported on Android:
 * title similarity >= 0.78 and artist similarity >= 0.6 or reject; reject duration drift above 10 s; weights 0.40/0.35/0.25;
 * +0.2 for synchronized lyrics; -0.3 for instrumental records. Version-mismatch rejection (live/remix/...) is added because
 * LiveLyrics only excludes those indirectly. Independent Swift implementation.
 */

public struct LrclibRecord: Equatable {
    public let id: Int64
    public let trackName: String?
    public let artistName: String?
    public let albumName: String?
    public let durationSec: Double?
    public let instrumental: Bool
    public let plain: String?
    public let synced: String?

    public init(id: Int64, trackName: String?, artistName: String?, albumName: String?, durationSec: Double?,
                instrumental: Bool, plain: String?, synced: String?) {
        self.id = id; self.trackName = trackName; self.artistName = artistName; self.albumName = albumName
        self.durationSec = durationSec; self.instrumental = instrumental; self.plain = plain; self.synced = synced
    }
}

public struct ScoredCandidate: Equatable {
    public let record: LrclibRecord
    public let score: Double
    public let rejectedReason: String?
    public let titleSim: Double
    public let artistSim: Double
    public let durationGapSec: Double?
}

public enum LyricsMatcher {
    public static let minTitle = 0.78
    public static let minArtist = 0.6
    public static let maxDurationDriftSec = 10.0

    private static let versionWordSet: Set<String> = [
        "live", "remix", "acoustic", "instrumental", "karaoke", "cover", "demo", "slowed", "sped", "reverb", "nightcore",
        "rework", "bootleg", "unplugged", "piano", "orchestral", "stripped", "reprise",
    ]

    private static func re(_ pattern: String) -> NSRegularExpression {
        // Patterns are constants below; a typo would be a programming error caught by the unit tests.
        try! NSRegularExpression(pattern: pattern, options: [.caseInsensitive])
    }

    private static let noiseGroup = re(#"\s*[(\[][^)\]]*(remaster\w*|\bversion\b|\bmix\b|\bedit\b|\bdeluxe\b|\bfeat\b|\bft\b|\bfeaturing\b|\bfrom\b|\bbonus\b|\bmono\b|\bstereo\b|\banniversary\b|\b(19|20)\d{2}\b)[^)\]]*[)\]]"#)
    private static let noiseSuffix = re(#"\s+-\s+[^-]*\b(remaster\w*|version|mono|stereo|edit|mix)\b.*$"#)
    /// Separators that introduce a secondary artist. "AC/DC", "Jay-Z" and "Florence + the Machine" must stay intact.
    private static let artistStrong = re(#"\s*(?:,|;|\bfeat\.?\b|\bft\.?\b|\bfeaturing\b|\bwith\b)\s*"#)
    /// Separators that may join two artists OR belong to one name ("Simon & Garfunkel"): used for matching only.
    private static let artistWeak = re(#"\s*(?:&|\band\b|\bx\b)\s*"#)
    private static let whitespace = re(#"\s+"#)

    private static func replacing(_ regex: NSRegularExpression, in s: String, with template: String) -> String {
        regex.stringByReplacingMatches(in: s, range: NSRange(s.startIndex..., in: s), withTemplate: template)
    }

    /// Splits like Kotlin's `String.split(Regex)`: pieces between matches, empties kept.
    static func split(_ s: String, _ regex: NSRegularExpression) -> [String] {
        let ns = s as NSString
        var out: [String] = []
        var last = 0
        for m in regex.matches(in: s, range: NSRange(location: 0, length: ns.length)) {
            out.append(ns.substring(with: NSRange(location: last, length: m.range.location - last)))
            last = m.range.location + m.range.length
        }
        out.append(ns.substring(from: last))
        return out
    }

    private static func isBlank(_ s: String) -> Bool { s.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }

    /// Words that make a recording a different VERSION (live, remix...), taken from the raw, uncleaned title.
    public static func versionWords(_ raw: String) -> Set<String> {
        Set(normalizeText(raw).split(separator: " ").map(String.init).filter { versionWordSet.contains($0) })
    }

    /// Removes remaster/feature/year decorations but keeps version words such as "Live" and "Remix".
    public static func cleanTitle(_ raw: String) -> String {
        var s = replacing(noiseGroup, in: raw, with: "")
        s = replacing(noiseSuffix, in: s, with: "")
        s = replacing(whitespace, in: s, with: " ").trimmingCharacters(in: .whitespaces)
        return s.isEmpty ? raw.trimmingCharacters(in: .whitespaces) : s
    }

    /// The lead artist for LRCLIB field searches (strong separators only).
    public static func primaryArtist(_ raw: String) -> String {
        (split(raw, artistStrong).first { !isBlank($0) } ?? raw).trimmingCharacters(in: .whitespaces)
    }

    /// Artist strings worth searching with: the lead artist and, when the name contains "&"/"and"/"x", its first part too.
    public static func searchArtists(_ raw: String) -> [String] {
        let lead = primaryArtist(raw)
        var out = [lead]
        if let firstWeak = split(lead, artistWeak).first(where: { !isBlank($0) })?.trimmingCharacters(in: .whitespaces),
           !out.contains(firstWeak) {
            out.append(firstWeak)
        }
        return out
    }

    private static func artistNames(_ raw: String) -> [String] {
        let strong = split(raw, artistStrong)
        let parts = strong + strong.flatMap { split($0, artistWeak) }
        var seen = Set<String>()
        return ([raw] + parts).map { normalizeText($0) }.filter { !$0.isEmpty && seen.insert($0).inserted }
    }

    private static func bigrams(_ s: String) -> [String: Int] {
        let chars = Array(s)
        var m: [String: Int] = [:]
        if chars.count < 2 { return m }
        for i in 0..<(chars.count - 1) { m[String(chars[i...(i + 1)]), default: 0] += 1 }
        return m
    }

    /// Sorensen-Dice similarity of two already-normalised strings, 0..1.
    public static func dice(_ a: String, _ b: String) -> Double {
        if a.isEmpty || b.isEmpty { return 0 }
        if a == b { return 1 }
        let la = a.count, lb = b.count
        if la < 2 || lb < 2 { return 0 }
        let ma = bigrams(a), mb = bigrams(b)
        var inter = 0
        for (k, v) in ma { inter += min(v, mb[k] ?? 0) }
        return 2.0 * Double(inter) / Double((la - 1) + (lb - 1))
    }

    public static func titleSimilarity(_ a: String, _ b: String) -> Double {
        dice(normalizeText(cleanTitle(a)), normalizeText(cleanTitle(b)))
    }

    public static func artistSimilarity(_ query: String, _ candidate: String) -> Double {
        var best = 0.0
        for q in artistNames(query) { for c in artistNames(candidate) { best = max(best, dice(q, c)) } }
        return best
    }

    /// Whether two (title, artist) pairs describe the same recording: used to tell if the music being heard is the open song.
    public static func sameTrack(titleA: String, artistA: String, titleB: String, artistB: String) -> Bool {
        versionWords(titleA) == versionWords(titleB)
            && titleSimilarity(titleA, titleB) >= minTitle
            && artistSimilarity(artistA, artistB) >= minArtist
    }

    public static func score(_ q: TrackQuery, _ r: LrclibRecord) -> ScoredCandidate {
        let name = r.trackName ?? ""
        let t = titleSimilarity(q.title, name)
        let a = artistSimilarity(q.artist, r.artistName ?? "")
        var gap: Double?
        if let qd = q.durationSec, let rd = r.durationSec { gap = abs(qd - rd) }
        func reject(_ why: String) -> ScoredCandidate {
            ScoredCandidate(record: r, score: -1, rejectedReason: why, titleSim: t, artistSim: a, durationGapSec: gap)
        }

        let qv = versionWords(q.title), cv = versionWords(name)
        if qv != cv { return reject("version mismatch: \(qv.symmetricDifference(cv).sorted())") }
        if t < minTitle { return reject(String(format: "title similarity %.2f < %.2f", t, minTitle)) }
        if a < minArtist { return reject(String(format: "artist similarity %.2f < %.2f", a, minArtist)) }
        if let g = gap, g > maxDurationDriftSec { return reject(String(format: "duration differs by %.1f s", g)) }

        let d: Double
        if let g = gap { d = g <= 2 ? 1.0 : (g <= 5 ? 0.6 : 0.25) } else { d = 0.5 }
        var score = 0.4 * t + 0.35 * a + 0.25 * d
        if let s = r.synced, !isBlank(s) { score += 0.2 }
        if let p = r.plain, !isBlank(p) { score += 0.02 }
        if r.instrumental { score -= 0.3 }
        if let qa = q.album, let ra = r.albumName, titleSimilarity(qa, ra) >= 0.8 { score += 0.05 }
        return ScoredCandidate(record: r, score: score, rejectedReason: nil, titleSim: t, artistSim: a, durationGapSec: gap)
    }

    /// Distinct candidates, best accepted first, rejected ones last (kept for diagnostics).
    public static func rank(_ q: TrackQuery, _ records: [LrclibRecord]) -> [ScoredCandidate] {
        var seen = Set<Int64>()
        let scored = records.filter { seen.insert($0.id).inserted }.map { score(q, $0) }
        return scored.enumerated().sorted { l, r in
            let lr = l.element.rejectedReason != nil, rr = r.element.rejectedReason != nil
            if lr != rr { return !lr }
            if l.element.score != r.element.score { return l.element.score > r.element.score }
            return l.offset < r.offset
        }.map { $0.element }
    }

    public static func best(_ ranked: [ScoredCandidate]) -> ScoredCandidate? { ranked.first { $0.rejectedReason == nil } }
}
