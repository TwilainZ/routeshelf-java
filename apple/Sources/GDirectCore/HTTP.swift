import Foundation

public enum CoreError: Error, Equatable, LocalizedError {
    case invalidURL, invalidRequest, malformedHTTP, responseTooLarge, timeout
    case authorization, permission, http(Int), integrity, unsafeFile, noRoute, invalidOAuth, unsupported
    public var errorDescription: String? {
        switch self {
        case .invalidURL, .invalidRequest: return "请求地址或参数无效。"
        case .malformedHTTP: return "服务器响应不完整或格式无效。"
        case .responseTooLarge: return "服务器响应超过安全上限。"
        case .timeout: return "连接超时，请重试。"
        case .authorization: return "请重新连接 Google 账户。"
        case .permission: return "Google 拒绝访问，请检查文件权限、Drive API 或配额。"
        case .http(let code): return "Google 服务返回 HTTP \(code)。"
        case .integrity: return "文件大小、版本或校验值不匹配，未保存不完整文件。"
        case .unsafeFile: return "无法安全保存文件，请选择其他文件夹。"
        case .noRoute: return "当前网络没有通过验证的 Google 线路。"
        case .invalidOAuth: return "Google 授权响应无效或已过期，请重新连接。"
        case .unsupported: return "当前平台暂不支持该操作。"
        }
    }
}

public final class CancellationFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var stopped = false
    public init() {}
    public func cancel() { lock.lock(); stopped = true; lock.unlock() }
    public func check() throws {
        lock.lock(); let value = stopped; lock.unlock()
        if value || Task.isCancelled { throw CancellationError() }
    }
}

public struct HTTPRequest: Sendable {
    public let url: URL
    public var method: String
    public var headers: [String: String]
    public var body: Data
    public var deadline: TimeInterval?
    public var guardCurrent: @Sendable () throws -> Void
    public init(url: URL, method: String = "GET", headers: [String: String] = [:], body: Data = Data(),
                deadline: TimeInterval? = 12, guardCurrent: @escaping @Sendable () throws -> Void = {}) throws {
        guard url.scheme == "https", URLPolicy.allowedHost(url.host ?? ""), url.user == nil, url.password == nil,
              url.fragment == nil, url.port == nil || url.port == 443, url.absoluteString.utf8.count <= 16384,
              ["GET", "POST"].contains(method), body.count <= 1024 * 1024 else { throw CoreError.invalidRequest }
        if let deadline {guard deadline.isFinite,deadline>0,deadline<=86400 else{throw CoreError.invalidRequest}}
        for (name, value) in headers {
            guard !name.isEmpty, name.utf8.allSatisfy({ $0 > 32 && $0 < 127 && !"()<>@,;:\\\"/[]?={}\t".utf8.contains($0) }),
                  value.utf8.allSatisfy({$0==9 || (32...126).contains($0)}), value.utf8.count < 16384,
                  !["host", "content-length", "transfer-encoding", "connection", "cookie"].contains(name.lowercased()) else { throw CoreError.invalidRequest }
        }
        let sensitive = headers.keys.contains { ["authorization", "x-goog-drive-resource-keys"].contains($0.lowercased()) }
        guard !sensitive || URLPolicy.isAPI(url) else { throw CoreError.invalidRequest }
        self.url = url; self.method = method; self.headers = headers; self.body = body
        self.deadline = deadline; self.guardCurrent = guardCurrent
    }
}

public struct HTTPHead: Sendable, Equatable {
    public let status: Int
    public let headers: [String: String]
    public init(status: Int, headers: [String: String] = [:]) {
        self.status = status; self.headers = Dictionary(uniqueKeysWithValues: headers.map { ($0.key.lowercased(), $0.value) })
    }
    public subscript(_ key: String) -> String? { headers[key.lowercased()] }
}
public enum HTTPEvent: Sendable { case head(HTTPHead), body(Data) }
public typealias HTTPReceiver = @Sendable (HTTPEvent) throws -> Void
/// A transport calls receive serially, exactly one final head before body bytes. It never follows redirects.
public protocol HTTPTransport: Sendable {
    func execute(_ request: HTTPRequest, receive: @escaping HTTPReceiver) async throws
}

public final class ResponseBuffer: @unchecked Sendable {
    public private(set) var head: HTTPHead?
    public private(set) var data = Data()
    private let limit: Int
    public init(limit: Int = 1024 * 1024) { self.limit = limit }
    public func receive(_ event: HTTPEvent) throws {
        switch event {
        case .head(let value): guard head == nil else { throw CoreError.malformedHTTP }; head = value
        case .body(let bytes):
            guard head != nil else { throw CoreError.malformedHTTP }
            guard bytes.count <= limit - data.count else { throw CoreError.responseTooLarge }
            data.append(bytes)
        }
    }
    public static func read(_ request: HTTPRequest, using transport: any HTTPTransport, limit: Int = 1024 * 1024) async throws -> (HTTPHead, Data) {
        let buffer = ResponseBuffer(limit: limit)
        try await transport.execute(request, receive: { try buffer.receive($0) })
        guard let head = buffer.head else { throw CoreError.malformedHTTP }
        return (head, buffer.data)
    }
}

public enum URLPolicy {
    public static func allowedHost(_ host: String) -> Bool {
        ["www.googleapis.com", "oauth2.googleapis.com", "www.gstatic.com", "drive.usercontent.google.com"].contains(host)
            || (host.hasSuffix(".googleusercontent.com") && host.count > ".googleusercontent.com".count)
    }
    public static func isAPI(_ url: URL) -> Bool {
        url.host == "www.googleapis.com" && url.path.hasPrefix("/drive/v3/")
    }
    public static func contentRedirect(_ location: String, from origin: URL) throws -> URL {
        guard let url = URL(string: location, relativeTo: origin)?.absoluteURL,
              url.scheme == "https", url.user == nil, url.password == nil, url.fragment == nil,
              url.port == nil || url.port == 443,
              url.host == "drive.usercontent.google.com" || (url.host?.hasSuffix(".googleusercontent.com") == true),
              url.absoluteString.utf8.count <= 16384 else { throw CoreError.invalidURL }
        return url
    }
    public static func id(_ value: String) throws -> String {
        guard !value.isEmpty, value.utf8.count <= 200,
              value.utf8.allSatisfy({ (65...90).contains($0) || (97...122).contains($0) || (48...57).contains($0) || $0 == 45 || $0 == 95 }) else { throw CoreError.invalidURL }
        return value
    }
}
