import SwiftUI

/// Root: four tabs (Listen, History, Favorites, Settings). Matches the Android navigation.
struct ContentView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        TabView {
            NavigationStack(path: $model.listenPath) {
                ListenView()
                    .navigationDestination(for: String.self) { key in ResultView(songKey: key) }
                    .navigationDestination(for: HelpRoute.self) { _ in HelpView() }
            }
            .tabItem { Label("Listen", systemImage: "mic.fill") }

            NavigationStack {
                HistoryView()
                    .navigationDestination(for: String.self) { key in ResultView(songKey: key) }
            }
            .tabItem { Label("History", systemImage: "clock.arrow.circlepath") }

            NavigationStack {
                FavoritesView()
                    .navigationDestination(for: String.self) { key in ResultView(songKey: key) }
            }
            .tabItem { Label("Favorites", systemImage: "heart") }

            NavigationStack {
                SettingsView()
                    .navigationDestination(for: HelpRoute.self) { _ in HelpView() }
            }
            .tabItem { Label("Settings", systemImage: "gearshape.fill") }
        }
        .onChange(of: scenePhase) { phase in
            // The microphone isn't available in the background; stop cleanly instead of silently stalling.
            if phase == .background { model.follow.stop() }
        }
        .tint(model.accents.primary)
        .preferredColorScheme(.dark)
    }
}

/// Navigation value for the Setup guide screen.
struct HelpRoute: Hashable {}

#Preview {
    ContentView().environmentObject(AppModel())
}
