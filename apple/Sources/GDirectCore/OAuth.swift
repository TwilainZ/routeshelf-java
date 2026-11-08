import Foundation

public struct OAuthConfiguration: Codable, Sendable {
    public let clientID: String
    public let clientSecret: String?
    public init(clientID: String, clientSecret: String? = nil) throws {
        guard clientID.hasSuffix(".apps.googleusercontent.com"), clientID.utf8.count < 512,
              clientID.utf8.allSatisfy({ $0 > 32 && $0 < 127 }), (clientSecret?.utf8.count ?? 0) < 1024 else { throw CoreError.invalidOAuth }
        self.clientID = clientID; self.clientSecret = clientSecret
    }
    public static func imported(_ data: Data) throws -> OAuthConfiguration {
        guard data.count < 65536, let root = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let installed = root["installed"] as? [String: Any], let id = installed["client_id"] as? String,
              let redirects = installed["redirect_uris"] as? [String],
              redirects.contains(where: { value in
                  guard let url = URLComponents(string: value) else { return false }
                  return url.scheme == "http" && ["localhost", "127.0.0.1"].contains(url.host ?? "")
                      && url.user == nil && url.password == nil && url.query == nil && url.fragment == nil
                      && (url.path.isEmpty || url.path == "/")
              }) else { throw CoreError.invalidOAuth }
        // Ignore imported endpoint URLs. Only fixed Google endpoints are used.
        return try OAuthConfiguration(clientID: id, clientSecret: installed["client_secret"] as? String)
    }
}

public enum OAuthCallback:Sendable,Equatable {case code(String),cancelled,rejected}

public struct OAuthAttempt: Sendable {
    public let state: String
    public let verifier: String
    public let redirect: URL
    public let created: Date
    public init(port: UInt16, state: String = Digest.randomURLToken(), verifier: String = Digest.randomURLToken(), created: Date = Date()) throws {
        guard port > 0, (43...128).contains(verifier.utf8.count), state.utf8.count >= 32 else { throw CoreError.invalidOAuth }
        self.redirect = URL(string: "http://127.0.0.1:\(port)/callback")!; self.state = state; self.verifier = verifier; self.created = created
    }
    public func authorizationURL(configuration: OAuthConfiguration) -> URL {
        var url = URLComponents(string: "https://accounts.google.com/o/oauth2/v2/auth")!
        url.queryItems = [URLQueryItem(name: "client_id", value: configuration.clientID), .init(name: "redirect_uri", value: redirect.absoluteString),
            .init(name: "response_type", value: "code"), .init(name: "scope", value: "https://www.googleapis.com/auth/drive.readonly"),
            .init(name: "state", value: state), .init(name: "code_challenge", value: Digest.base64URL(Digest.sha256(Data(verifier.utf8)))),
            .init(name: "code_challenge_method", value: "S256"), .init(name: "access_type", value: "offline"), .init(name: "prompt", value: "consent")]
        return url.url!
    }
    public func callback(from callback:URL,now:Date=Date())throws->OAuthCallback{
        guard now.timeIntervalSince(created)>=0,now.timeIntervalSince(created)<300,
              callback.scheme==redirect.scheme,callback.host==redirect.host,callback.port==redirect.port,
              callback.path==redirect.path,callback.user==nil,callback.password==nil,callback.fragment==nil,
              let parts=URLComponents(url:callback,resolvingAgainstBaseURL:false) else{throw CoreError.invalidOAuth}
        let items=parts.queryItems ?? [],states=items.filter{$0.name=="state"},codes=items.filter{$0.name=="code"},errors=items.filter{$0.name=="error"}
        guard states.count==1,states[0].value==state else{throw CoreError.invalidOAuth}
        if !errors.isEmpty{
            guard errors.count==1,codes.isEmpty,let error=errors[0].value,!error.isEmpty,error.utf8.count<128 else{throw CoreError.invalidOAuth}
            return error=="access_denied" ? .cancelled : .rejected
        }
        guard codes.count==1,let code=codes[0].value,!code.isEmpty,code.utf8.count<8192 else{throw CoreError.invalidOAuth}
        return .code(code)
    }
    public func code(from callback:URL,now:Date=Date())throws->String{
        switch try self.callback(from:callback,now:now){
        case .code(let code):return code
        case .cancelled:throw CancellationError()
        case .rejected:throw CoreError.invalidOAuth
        }
    }

}

public struct OAuthTokens: Codable, Sendable {
    public let accessToken: String
    public let refreshToken: String?
    public let expiresAt: Date
    public init(accessToken: String, refreshToken: String?, expiresAt: Date) { self.accessToken=accessToken; self.refreshToken=refreshToken; self.expiresAt=expiresAt }
}
public struct CredentialLease: Sendable {
    public let token: String
    public let check: @Sendable () throws -> Void
    public init(token: String, check: @escaping @Sendable () throws -> Void) { self.token=token; self.check=check }
}
public protocol Credentials: Sendable { func credential() async throws -> CredentialLease }

public enum GoogleOAuth {
    public static func form(_ fields: [String: String]) -> Data {
        let allowed = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~")
        return Data(fields.sorted { $0.key < $1.key }.map { $0.key.addingPercentEncoding(withAllowedCharacters: allowed)! + "=" + $0.value.addingPercentEncoding(withAllowedCharacters: allowed)! }.joined(separator: "&").utf8)
    }
    public static func exchange(attempt: OAuthAttempt, code: String, configuration: OAuthConfiguration, transport: any HTTPTransport) async throws -> OAuthTokens {
        var fields = ["client_id": configuration.clientID, "code": code, "code_verifier": attempt.verifier,
                      "redirect_uri": attempt.redirect.absoluteString, "grant_type": "authorization_code"]
        fields["client_secret"] = configuration.clientSecret
        return try await token(fields: fields, refresh: nil, transport: transport)
    }
    public static func refresh(_ refresh: String, configuration: OAuthConfiguration, transport: any HTTPTransport) async throws -> OAuthTokens {
        var fields = ["client_id": configuration.clientID, "refresh_token": refresh, "grant_type": "refresh_token"]
        fields["client_secret"] = configuration.clientSecret
        return try await token(fields: fields, refresh: refresh, transport: transport)
    }
    private static func token(fields: [String: String], refresh: String?, transport: any HTTPTransport) async throws -> OAuthTokens {
        let request = try HTTPRequest(url: URL(string: "https://oauth2.googleapis.com/token")!, method: "POST",
            headers: ["Content-Type": "application/x-www-form-urlencoded"], body: form(fields))
        let (head, data) = try await ResponseBuffer.read(request, using: transport, limit: 65536)
        if head.status==400 || head.status==401 {throw CoreError.authorization}
        if head.status==403 {throw CoreError.permission}
        guard head.status==200 else{throw CoreError.http(head.status)}
        guard head.status == 200, let root = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let access = root["access_token"] as? String, !access.isEmpty, access.utf8.count < 16384,
              (root["token_type"] as? String)?.lowercased() == "bearer", let expires = root["expires_in"] as? Double, expires > 0, expires <= 86400 else { throw CoreError.authorization }
        if let scopes = root["scope"] as? String {
            guard scopes.split(separator: " ").contains("https://www.googleapis.com/auth/drive.readonly") || scopes.split(separator: " ").contains("https://www.googleapis.com/auth/drive") else { throw CoreError.authorization }
        }
        return OAuthTokens(accessToken: access, refreshToken: root["refresh_token"] as? String ?? refresh, expiresAt: Date().addingTimeInterval(expires))
    }
}
