import SwiftUI

struct ContentView: View {
    @State private var recognizedTitle = "Listening..."
    @State private var accentColor = Color.blue

    var body: some View {
        ZStack {
            // Full-screen blurred album-art background placeholder
            LinearGradient(colors: [accentColor.opacity(0.6), Color.black], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()

            VStack(spacing: 24) {
                // Prominent album artwork placeholder
                RoundedRectangle(cornerRadius: 16)
                    .fill(accentColor.opacity(0.3))
                    .frame(width: 280, height: 280)
                    .overlay(Text("Album Art").foregroundColor(.white))
                    .shadow(radius: 20)

                Text(recognizedTitle)
                    .font(.title)
                    .fontWeight(.bold)
                    .foregroundColor(.white)

                // Tabs placeholder
                HStack {
                    Text("Artist Wiki")
                    Text("Song Wiki")
                    Text("Lyrics")
                }
                .font(.caption)
                .foregroundColor(.white.opacity(0.8))
            }
            .padding()
        }
        .onAppear {
            // ShazamKit recognition will be wired here
        }
    }
}

#Preview {
    ContentView()
}
