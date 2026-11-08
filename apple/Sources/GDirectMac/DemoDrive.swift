#if os(macOS)
import Foundation
import GDirectCore

/// Serves the labelled UI fixture in-process. It never opens a socket or reads the Keychain.
struct DemoDrive: HTTPTransport, Credentials {
    private struct File: Encodable {
        let id: String
        let name: String
        let mimeType: String
        let size: String?
        let capabilities: [String: Bool]

        init(_ id: String, _ name: String, size: Int? = nil) {
            self.id = id
            self.name = name
            mimeType = size == nil ? "application/vnd.google-apps.folder" : "text/plain"
            self.size = size.map(String.init)
            capabilities = ["canDownload": size != nil]
        }
    }

    private struct Listing: Encodable {
        let files: [File]
    }

    private static let text = Data("GDirect Lite — offline sample\n".utf8)
    private static let slowChunks = 48

    func credential() async throws -> CredentialLease {
        CredentialLease(token: "offline-fixture", check: {})
    }

    func execute(_ request: HTTPRequest, receive: @escaping HTTPReceiver) async throws {
        try request.guardCurrent()
        try Task.checkCancellation()
        let items = URLComponents(url: request.url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        switch request.url.path {
        case "/drive/v3/files":
            let query = items.first { $0.name == "q" }?.value ?? ""
            let folder = File("demo_folder", "项目资料")
            let quick = File("demo_quick", "示例说明.txt", size: Self.text.count)
            let slow = File("demo_slow", "慢速下载示例.txt", size: Self.text.count * Self.slowChunks)
            let rows: [File]
            if query.contains("'demo_folder' in parents") {
                rows = [quick]
            } else if query.contains("'root' in parents") {
                rows = [folder, slow]
            } else {
                rows = [quick, slow, folder]
            }
            let filter = query.range(of: " and name contains '").map {
                query[$0.upperBound...].dropLast()
                    .replacingOccurrences(of: "\\'", with: "'")
                    .replacingOccurrences(of: "\\\\", with: "\\")
            } ?? ""
            let body = try JSONEncoder().encode(Listing(files: rows.filter {
                filter.isEmpty || $0.name.localizedCaseInsensitiveContains(filter)
            }))
            try receive(.head(HTTPHead(status: 200, headers: ["content-type": "application/json"])))
            try receive(.body(body))
        case "/drive/v3/files/demo_quick":
            try sendHead(chunks: 1, receive: receive); try receive(.body(Self.text))
        case "/drive/v3/files/demo_slow":
            try await sendSlowFile(receive)
        case "/download":
            guard let id = items.first(where: { $0.name == "id" })?.value else { throw CoreError.invalidURL }
            switch id {
            case "demo_quick": try sendHead(chunks: 1, receive: receive); try receive(.body(Self.text))
            case "demo_slow": try await sendSlowFile(receive)
            default: throw CoreError.http(404)
            }
        default:
            throw CoreError.http(404)
        }
    }

    private func sendHead(chunks: Int, receive: HTTPReceiver) throws {
        try receive(.head(HTTPHead(status: 200, headers: [
            "content-type": "text/plain", "content-length": String(Self.text.count * chunks)
        ])))
    }

    private func sendSlowFile(_ receive: HTTPReceiver) async throws {
        try sendHead(chunks: Self.slowChunks, receive: receive)
        for _ in 0..<Self.slowChunks {
            try await Task.sleep(for: .milliseconds(500))
            try receive(.body(Self.text))
        }
    }
}
#endif
