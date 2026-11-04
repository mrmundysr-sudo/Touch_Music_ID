// swift-tools-version:5.9
// Test harness only. The app itself is built by TouchMusicID.xcodeproj; this package compiles the same
// Foundation-only `Core` sources so their unit tests run with `swift test` (CI: macOS job).
import PackageDescription

let package = Package(
    name: "TouchMusicIDCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [.library(name: "TouchMusicIDCore", targets: ["TouchMusicIDCore"])],
    targets: [
        .target(name: "TouchMusicIDCore", path: "TouchMusicID/Core"),
        .testTarget(name: "TouchMusicIDCoreTests", dependencies: ["TouchMusicIDCore"], path: "Tests/CoreTests"),
    ]
)
