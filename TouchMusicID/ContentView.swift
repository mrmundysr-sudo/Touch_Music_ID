import SwiftUI

/// Root: four tabs (Listen, History, Favorites, Settings). Matches the Android navigation.
struct ContentView: View {
    @EnvironmentObject private var model: AppModel

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
        .tint(model.accents.primary)
        .preferredColorScheme(.dark)
    }
}

/// Navigation value for the Setup guide screen.
struct HelpRoute: Hashable {}

#Preview {
    ContentView().environmentObject(AppModel())
}
