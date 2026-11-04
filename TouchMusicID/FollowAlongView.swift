import SwiftUI
import UIKit

enum FollowIDs {
    static func line(_ i: Int) -> String { "follow-line-\(i)" }
}

/// The lyrics body: plain text when there is no synchronized version, otherwise the timed lines with Follow along controls.
struct LyricsBody: View {
    let result: LyricsResult
    let song: Song
    @ObservedObject var follow: FollowController
    @EnvironmentObject private var model: AppModel

    var body: some View {
        if result.instrumental {
            Text("Instrumental — no lyrics").font(.subheadline).foregroundColor(Tmi.textMedium)
        } else if result.canFollow {
            followPanel
            lineList
        } else {
            Text(result.displayText).font(.body).foregroundColor(Tmi.textHigh)
            Text("Follow along needs synchronized (timed) lyrics, and this recording has only plain text.")
                .font(.caption).foregroundColor(Tmi.textLow)
        }
        HStack(spacing: 8) {
            Pill(text: result.source == .local ? "Local LRC" : "From LRCLIB", color: Tmi.accent)
            if result.canFollow { Pill(text: "Synced", color: Tmi.success) }
        }
        if !result.info.isEmpty { Text(result.info).font(.caption2).foregroundColor(Tmi.textLow) }
    }

    // MARK: Controls

    @ViewBuilder private var followPanel: some View {
        if follow.running {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 10) {
                    Circle().fill(follow.syncState == .following ? Tmi.success : Tmi.warm).frame(width: 10, height: 10)
                    Text(follow.status).font(.subheadline.weight(.semibold)).foregroundColor(Tmi.textHigh)
                    Spacer()
                    if follow.positionSec >= 0 {
                        Text(String(format: "%d:%02d", follow.positionSec / 60, follow.positionSec % 60))
                            .font(.subheadline.monospacedDigit()).foregroundColor(Tmi.textMedium)
                    }
                }
                .accessibilityElement(children: .combine)

                HStack(spacing: 8) {
                    TmiButton(title: "Stop", systemImage: "stop.fill", primary: false, compact: true) { follow.stop() }
                    TmiButton(title: "Re-sync", systemImage: "arrow.clockwise", primary: false, compact: true) { follow.resync() }
                }

                Text("Lyrics early or late? Nudge the timing (\(follow.nudgeMs >= 0 ? "+" : "")\(follow.nudgeMs) ms). Plus highlights sooner, minus later.")
                    .font(.caption).foregroundColor(Tmi.textLow)
                HStack(spacing: 8) {
                    ForEach([-250, -100, 100, 250], id: \.self) { d in
                        TmiButton(title: d > 0 ? "+\(d)" : "\(d)", primary: false, compact: true) { follow.nudge(d) }
                    }
                    if follow.nudgeMs != 0 {
                        TmiButton(title: "Reset", primary: false, compact: true) { follow.resetNudge() }
                    }
                }

                Picker("Timing method", selection: Binding(get: { follow.estimator }, set: { follow.setEstimator($0) })) {
                    ForEach(Estimator.allCases, id: \.self) { Text("Method \($0.rawValue)").tag($0) }
                }
                .pickerStyle(.segmented)
                Toggle("Keep the current line in view", isOn: $follow.autoScroll).tint(Tmi.primary)
                    .font(.subheadline).foregroundColor(Tmi.textMedium)
                Text(follow.diag).font(.caption2.monospacedDigit()).foregroundColor(Tmi.textLow)
            }
        } else {
            VStack(alignment: .leading, spacing: 8) {
                TmiButton(title: "Follow along", systemImage: "music.note.list") {
                    model.startFollow()
                }
                if follow.micDenied {
                    Text(follow.status).font(.caption).foregroundColor(Tmi.warm)
                    TmiButton(title: "Open Settings", primary: false, compact: true) {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }
                } else {
                    Text("Play the song near your phone. The lyrics highlight as it plays. The microphone is used only while this is on, and audio is never saved.")
                        .font(.caption).foregroundColor(Tmi.textLow)
                }
            }
        }
    }

    // MARK: Lines

    private var lineList: some View {
        VStack(alignment: .leading, spacing: 10) {
            ForEach(Array(result.syncedLines.enumerated()), id: \.offset) { i, line in
                let active = follow.running && i == follow.activeIndex
                let past = follow.running && follow.activeIndex >= 0 && i < follow.activeIndex
                Text(line.text)
                    .font(active ? .title3.weight(.bold) : .body)
                    .foregroundColor(active ? model.accents.primary : (past ? Tmi.textLow : Tmi.textHigh))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .id(FollowIDs.line(i))
                    .accessibilityAddTraits(active ? .isSelected : [])
            }
        }
        .animation(.easeInOut(duration: 0.2), value: follow.activeIndex)
    }
}

/// Wraps the result screen's scroll view so the highlighted lyric line is kept in view while following.
struct FollowScroller<Content: View>: View {
    @ObservedObject var follow: FollowController
    @ViewBuilder var content: Content

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView { content }
                .onChange(of: follow.activeIndex) { idx in
                    guard follow.running, follow.autoScroll, idx >= 0 else { return }
                    withAnimation(.easeInOut(duration: 0.35)) { proxy.scrollTo(FollowIDs.line(idx), anchor: .center) }
                }
        }
    }
}
