import SwiftUI
import UIKit

// Design tokens mirror the Android app (design/Tokens.kt). Change visuals here, never inside screens.
extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB,
                  red: Double((hex >> 16) & 0xFF) / 255,
                  green: Double((hex >> 8) & 0xFF) / 255,
                  blue: Double(hex & 0xFF) / 255,
                  opacity: alpha)
    }
}

enum Tmi {
    static let background = Color(hex: 0x0B0F1A)
    static let backgroundDeep = Color(hex: 0x070A12)
    static let surface = Color(hex: 0x141A2B)
    static let surfaceHigh = Color(hex: 0x1B2338)
    static let outline = Color(hex: 0x2B3552)
    static let primary = Color(hex: 0x5EEAD4)      // teal
    static let onPrimary = Color(hex: 0x04201B)
    static let accent = Color(hex: 0x8B7CFF)       // violet
    static let warm = Color(hex: 0xFFB86B)
    static let success = Color(hex: 0x6EE7A0)
    static let error = Color(hex: 0xFF6B6B)
    static let textHigh = Color(hex: 0xF3F6FF)
    static let textMedium = Color(hex: 0xB4BED8)
    static let textLow = Color(hex: 0x7D88A8)

    static let screenPadding: CGFloat = 20
    static let cardRadius: CGFloat = 22
    static let gap: CGFloat = 12
    static let minTouch: CGFloat = 48
    static let albumArt: CGFloat = 200
}

/// Accent colors extracted from album artwork. Falls back to brand tokens when art is unavailable.
struct ArtworkAccents: Equatable {
    var primary: Color = Tmi.primary
    var secondary: Color = Tmi.accent
    var onPrimary: Color = Tmi.onPrimary

    static let `default` = ArtworkAccents()

    static func extract(from image: UIImage?) -> ArtworkAccents {
        guard let cg = image?.cgImage else { return .default }
        let size = 32
        guard let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: size * 4,
                                  space: CGColorSpaceCreateDeviceRGB(),
                                  bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue) else { return .default }
        ctx.draw(cg, in: CGRect(x: 0, y: 0, width: size, height: size))
        guard let raw = ctx.data else { return .default }
        let px = raw.bindMemory(to: UInt8.self, capacity: size * size * 4)

        var bestVibrant: (score: CGFloat, h: CGFloat, s: CGFloat, b: CGFloat)?
        var bestMuted: (score: CGFloat, h: CGFloat, s: CGFloat, b: CGFloat)?
        for i in 0..<(size * size) {
            let ui = UIColor(red: CGFloat(px[i * 4]) / 255, green: CGFloat(px[i * 4 + 1]) / 255,
                             blue: CGFloat(px[i * 4 + 2]) / 255, alpha: 1)
            var h: CGFloat = 0, s: CGFloat = 0, b: CGFloat = 0, a: CGFloat = 0
            ui.getHue(&h, saturation: &s, brightness: &b, alpha: &a)
            if s > 0.35 && b > 0.35 {
                let score = s * (0.4 + b)
                if score > (bestVibrant?.score ?? 0) { bestVibrant = (score, h, s, b) }
            }
            if s > 0.10 && s < 0.55 && b > 0.30 && b < 0.85 {
                let score = (1 - abs(s - 0.3)) * b
                if score > (bestMuted?.score ?? 0) { bestMuted = (score, h, s, b) }
            }
        }
        // Keep accents readable on the dark UI: lift dark picks, tame washed-out ones.
        func color(_ t: (score: CGFloat, h: CGFloat, s: CGFloat, b: CGFloat)?, fallback: Color) -> (Color, Bool) {
            guard let t else { return (fallback, false) }
            let b = max(t.b, 0.72)
            let ui = UIColor(hue: t.h, saturation: min(t.s, 0.85), brightness: b, alpha: 1)
            return (Color(ui), b > 0.6)
        }
        let (primary, light) = color(bestVibrant, fallback: Tmi.primary)
        let (secondary, _) = color(bestMuted, fallback: Tmi.accent)
        return ArtworkAccents(primary: primary, secondary: secondary, onPrimary: light ? Color.black : Color.white)
    }
}

/// Small in-memory artwork cache. Artwork URLs come from ShazamKit and are public Apple CDN images.
@MainActor
final class ArtworkLoader {
    static let shared = ArtworkLoader()
    private let cache = NSCache<NSString, UIImage>()
    private let session: URLSession = {
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 10
        return URLSession(configuration: cfg)
    }()

    func image(for urlString: String?) async -> UIImage? {
        guard let urlString, let url = URL(string: urlString), url.scheme == "https" else { return nil }
        if let hit = cache.object(forKey: urlString as NSString) { return hit }
        guard let (data, resp) = try? await session.data(from: url),
              (resp as? HTTPURLResponse)?.statusCode == 200,
              let img = UIImage(data: data) else { return nil }
        cache.setObject(img, forKey: urlString as NSString)
        return img
    }
}

/// Full-screen blurred + darkened album artwork background, or the branded gradient when there is no artwork.
struct AlbumArtBackground: View {
    let image: UIImage?
    @Environment(\.accessibilityReduceTransparency) private var reduceTransparency

    var body: some View {
        ZStack {
            LinearGradient(colors: [Tmi.backgroundDeep, Tmi.background, Color(hex: 0x111B33)], startPoint: .top, endPoint: .bottom)
            if let image, !reduceTransparency {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFill()
                    .blur(radius: 48)
                    .overlay(
                        LinearGradient(colors: [.black.opacity(0.55), .black.opacity(0.72), .black.opacity(0.85)],
                                       startPoint: .top, endPoint: .bottom)
                    )
                    .transition(.opacity)
            }
        }
        .ignoresSafeArea()
        .animation(.easeInOut(duration: 0.28), value: image != nil)
    }
}

struct GlassCard<Content: View>: View {
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 10) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .background(Tmi.surface.opacity(0.72))
            .overlay(RoundedRectangle(cornerRadius: Tmi.cardRadius).stroke(Tmi.outline.opacity(0.6), lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: Tmi.cardRadius))
    }
}

struct TmiButton: View {
    let title: String
    var systemImage: String?
    var primary: Bool = true
    var compact: Bool = false
    var enabled: Bool = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                if let systemImage { Image(systemName: systemImage) }
                Text(title).multilineTextAlignment(.center)
            }
            .font(compact ? .footnote.weight(.semibold) : .subheadline.weight(.semibold))
            .frame(maxWidth: .infinity, minHeight: compact ? 40 : Tmi.minTouch)
            .padding(.horizontal, 10)
            .foregroundColor(primary ? Tmi.onPrimary : Tmi.textHigh)
            .background(primary ? Tmi.primary : Tmi.surfaceHigh.opacity(0.9))
            .overlay(RoundedRectangle(cornerRadius: 16).stroke(primary ? Color.clear : Tmi.outline, lineWidth: 1))
            .clipShape(RoundedRectangle(cornerRadius: 16))
            .opacity(enabled ? 1 : 0.5)
        }
        .disabled(!enabled)
    }
}

struct Pill: View {
    let text: String
    let color: Color
    var body: some View {
        Text(text)
            .font(.caption.weight(.semibold))
            .foregroundColor(color)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(color.opacity(0.16))
            .clipShape(Capsule())
    }
}

/// Inline notice with an icon, a title, a message and optional actions.
struct CompactNotice<Actions: View>: View {
    let systemImage: String
    let title: String
    let message: String
    @ViewBuilder var actions: Actions

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 10) {
                Image(systemName: systemImage).foregroundColor(Tmi.warm)
                Text(title).font(.headline).foregroundColor(Tmi.textHigh)
            }
            Text(message).font(.subheadline).foregroundColor(Tmi.textMedium)
            HStack(spacing: 8) { actions }
        }
    }
}

/// Album artwork tile with a branded placeholder. Decorative: the title and artist carry the meaning.
struct AlbumArtView: View {
    let url: String?
    var size: CGFloat = Tmi.albumArt
    var radius: CGFloat = 20
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: radius).fill(Tmi.primary.opacity(0.15))
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                Image(systemName: "music.note").font(.system(size: size * 0.33)).foregroundColor(Tmi.textMedium)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: radius))
        .accessibilityHidden(true)
        .task(id: url) { image = await ArtworkLoader.shared.image(for: url) }
    }
}

struct SectionTitle: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text).font(.headline).foregroundColor(Tmi.textHigh).accessibilityAddTraits(.isHeader)
    }
}

/// Shared screen chrome: artwork/brand background behind the content, dark translucent bars.
struct TmiScreen: ViewModifier {
    @EnvironmentObject private var model: AppModel
    func body(content: Content) -> some View {
        content
            .background(AlbumArtBackground(image: model.artwork))
            .toolbarBackground(Tmi.backgroundDeep.opacity(0.92), for: .tabBar)
            .toolbarBackground(.visible, for: .tabBar)
            // Opaque-ish bar so scrolling content doesn't show through behind the title.
            .toolbarBackground(Tmi.backgroundDeep.opacity(0.92), for: .navigationBar)
            .toolbarBackground(.visible, for: .navigationBar)
            .toolbarColorScheme(.dark, for: .navigationBar, .tabBar)
    }
}

extension View {
    func tmiScreen() -> some View { modifier(TmiScreen()) }
}
