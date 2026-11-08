// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "GDirectSwift", platforms: [.macOS(.v13), .iOS(.v16)],
    products: [.library(name: "GDirectCore", targets: ["GDirectCore"]),
               .library(name: "GDirectApple", targets: ["GDirectApple"]),
               .executable(name: "GDirectMac", targets: ["GDirectMac"])],
    targets: [
        .target(name: "GDirectCore", resources: [.copy("Resources")]),
        .target(name: "GDirectApple", dependencies: ["GDirectCore"]),
        .executableTarget(name: "GDirectMac", dependencies: ["GDirectCore", "GDirectApple"]),
        .testTarget(name: "GDirectCoreTests", dependencies: ["GDirectCore"]),
        .testTarget(name: "GDirectAppleTests", dependencies: ["GDirectCore", "GDirectApple"])
    ], swiftLanguageModes: [.v5]
)
