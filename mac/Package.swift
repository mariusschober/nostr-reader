// swift-tools-version: 5.9
import PackageDescription

let package = Package(
  name: "ReaderMac",
  platforms: [.macOS(.v13)],
  products: [
    .library(name: "ReaderCoreSwift", targets: ["ReaderCoreSwift"]),
  ],
  targets: [
    .target(name: "ReaderCoreSwift", path: "ReaderMac/Sources/Core"),
    .testTarget(name: "ReaderCoreSwiftTests", dependencies: ["ReaderCoreSwift"], path: "ReaderMac/Tests"),
  ]
)
