import Foundation

public struct DriveFile: Decodable, Sendable, Identifiable, Equatable {
    public let id: String
    public let name: String
    public let mimeType: String
    public let size: String?
    public let md5Checksum: String?
    public let resourceKey: String?
    public let modifiedTime: String?
    public let capabilities: Capabilities?
    public struct Capabilities: Decodable, Sendable, Equatable { public let canDownload: Bool? }
    public var isFolder: Bool { mimeType == "application/vnd.google-apps.folder" }
    public var export: (mime: String, suffix: String)? {
        if mimeType == "application/vnd.google-apps.spreadsheet" { return ("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", ".xlsx") }
        if ["application/vnd.google-apps.document", "application/vnd.google-apps.presentation", "application/vnd.google-apps.drawing"].contains(mimeType) { return ("application/pdf", ".pdf") }
        return nil
    }
    public var canDownload: Bool { capabilities?.canDownload == true && !isFolder && (!mimeType.hasPrefix("application/vnd.google-apps.") || export != nil) }
    public var byteCount: Int64? { size.flatMap(Int64.init) }
}
public struct DrivePage: Decodable, Sendable { public let nextPageToken: String?; public let files: [DriveFile] }
public enum DriveScope: Sendable { case folder(String), recent }

public struct DownloadSpec: Sendable {
    public let url: URL
    public let filename: String
    public let expectedSize: Int64?
    public let md5: String?
    public let rejectHTML:Bool
    public let headers: [String: String]
    public let check: @Sendable () throws -> Void
    public init(url: URL, filename: String, expectedSize: Int64? = nil, md5: String? = nil, rejectHTML:Bool=true, headers: [String: String] = [:], check: @escaping @Sendable () throws -> Void = {}) {
        self.url=url; self.filename=filename; self.expectedSize=expectedSize; self.md5=md5;self.rejectHTML=rejectHTML; self.headers=headers; self.check=check
    }
}

public struct DriveClient: Sendable {
    private let transport: any HTTPTransport
    private let credentials: any Credentials
    private static let fields = "id,name,mimeType,size,modifiedTime,md5Checksum,resourceKey,capabilities/canDownload"
    public init(transport: any HTTPTransport, credentials: any Credentials) { self.transport=transport; self.credentials=credentials }
    public static func listURL(scope: DriveScope, search: String = "", page: String? = nil) throws -> URL {
        guard search.count <= 200, (page?.utf8.count ?? 0) < 8192 else { throw CoreError.invalidRequest }
        var query = "trashed = false"
        if case .folder(let id) = scope { query += " and '\(try URLPolicy.id(id))' in parents" }
        if !search.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { query += " and name contains '\(search.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "'", with: "\\'"))'" }
        var parts = URLComponents(string: "https://www.googleapis.com/drive/v3/files")!
        let recent: Bool; if case .recent = scope { recent=true } else { recent=false }
        parts.queryItems = [.init(name: "q", value: query), .init(name: "pageSize", value: "100"),
            .init(name: "fields", value: "nextPageToken,files(\(fields))"), .init(name: "orderBy", value: recent ? "modifiedTime desc" : "folder,name"),
            .init(name: "supportsAllDrives", value: "true"), .init(name: "includeItemsFromAllDrives", value: "true")]
        if let page { parts.queryItems?.append(.init(name: "pageToken", value: page)) }
        return parts.url!
    }
    public func list(scope: DriveScope, search: String = "", page: String? = nil) async throws -> DrivePage {
        let data = try await read(try Self.listURL(scope: scope, search: search, page: page))
        let result = try JSONDecoder().decode(DrivePage.self, from: data)
        for file in result.files { _ = try URLPolicy.id(file.id) }
        return result
    }
    public func file(id: String) async throws -> DriveFile {
        var url = URLComponents(string: "https://www.googleapis.com/drive/v3/files/\(try URLPolicy.id(id))")!
        url.queryItems = [.init(name: "fields", value: Self.fields), .init(name: "supportsAllDrives", value: "true")]
        return try JSONDecoder().decode(DriveFile.self, from: await read(url.url!))
    }
    private func read(_ url: URL) async throws -> Data {
        let lease = try await credentials.credential(); try lease.check()
        let request = try HTTPRequest(url: url, headers: ["Authorization": "Bearer " + lease.token], guardCurrent: lease.check)
        let (head, data) = try await ResponseBuffer.read(request, using: transport); try lease.check()
        if head.status == 401 { throw CoreError.authorization }; if head.status == 403 { throw CoreError.permission }
        guard head.status == 200 else { throw CoreError.http(head.status) }; return data
    }
    public func media(_ file: DriveFile) async throws -> DownloadSpec {
        guard file.canDownload else { throw CoreError.permission }
        let id = try URLPolicy.id(file.id), lease = try await credentials.credential(); try lease.check()
        var parts = URLComponents(string: "https://www.googleapis.com/drive/v3/files/\(id)\(file.export == nil ? "" : "/export")")!
        parts.queryItems = file.export.map { [.init(name: "mimeType", value: $0.mime)] } ?? [.init(name: "alt", value: "media"), .init(name: "supportsAllDrives", value: "true")]
        var headers = ["Authorization": "Bearer " + lease.token]
        if let key = file.resourceKey, !key.isEmpty {
            guard key.utf8.count < 1024, !key.contains("\r"), !key.contains("\n"), !key.contains(",") else { throw CoreError.invalidRequest }
            headers["X-Goog-Drive-Resource-Keys"] = id + "/" + key
        }
        return DownloadSpec(url: parts.url!, filename: file.name + (file.export?.suffix ?? ""), expectedSize: file.export == nil ? file.byteCount : nil,
            md5: file.export == nil ? file.md5Checksum : nil,rejectHTML:file.mimeType != "text/html", headers: headers, check: lease.check)
    }
    public static func publicMedia(link: String) throws -> DownloadSpec {
        guard let url = URL(string: link.trimmingCharacters(in: .whitespacesAndNewlines)), url.scheme == "https",
              url.host == "drive.google.com", url.user == nil, url.password == nil, url.port == nil || url.port == 443,
              let parts = URLComponents(url: url, resolvingAgainstBaseURL: false), link.utf8.count <= 16384 else { throw CoreError.invalidURL }
        let segments = url.path.split(separator: "/"); var value: String?
        if segments.count == 3 && segments[0] == "file" && segments[1] == "d" { value = String(segments[2]) }
        if segments.count >= 4 && segments[0] == "file" && segments[1] == "d" && segments[3] == "view" { value = String(segments[2]) }
        if url.path == "/open" || url.path == "/uc" { let ids=parts.queryItems?.filter { $0.name == "id" } ?? []; if ids.count == 1 { value=ids[0].value } }
        let id = try URLPolicy.id(value ?? ""); var content = URLComponents(string: "https://drive.usercontent.google.com/download")!
        content.queryItems = [.init(name: "id", value: id), .init(name: "export", value: "download"), .init(name: "confirm", value: "t")]
        return DownloadSpec(url: content.url!, filename: "GoogleDrive-" + id)
    }
}
