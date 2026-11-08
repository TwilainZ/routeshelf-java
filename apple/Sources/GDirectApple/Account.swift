#if canImport(Security)
import Foundation
import Security
import GDirectCore

public struct AccountRecord: Codable, Sendable {
    public let configuration: OAuthConfiguration
    public let refreshToken: String?
    public init(configuration:OAuthConfiguration,refreshToken:String?){self.configuration=configuration;self.refreshToken=refreshToken}
}
public struct KeychainStore: Sendable {
    private let service="dev.gdirect.lite.mac.account"
    public init(){}
    public func load() throws -> AccountRecord? {
        var result:CFTypeRef?
        let status=SecItemCopyMatching([kSecClass:kSecClassGenericPassword,kSecAttrService:service,kSecAttrAccount:"primary",
            kSecReturnData:true,kSecMatchLimit:kSecMatchLimitOne] as CFDictionary,&result)
        if status==errSecItemNotFound{return nil}
        guard status==errSecSuccess,let data=result as? Data else{throw CoreError.authorization}
        return try JSONDecoder().decode(AccountRecord.self,from:data)
    }
    public func save(_ record:AccountRecord) throws {
        let data=try JSONEncoder().encode(record)
        let query=[kSecClass:kSecClassGenericPassword,kSecAttrService:service,kSecAttrAccount:"primary"] as CFDictionary
        let attributes=[kSecValueData:data,kSecAttrAccessible:kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,kSecAttrSynchronizable:false] as CFDictionary
        let updated=SecItemUpdate(query,attributes)
        if updated==errSecItemNotFound {
            let added=SecItemAdd([kSecClass:kSecClassGenericPassword,kSecAttrService:service,kSecAttrAccount:"primary",
                kSecValueData:data,kSecAttrAccessible:kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,kSecAttrSynchronizable:false] as CFDictionary,nil)
            guard added==errSecSuccess else{throw CoreError.authorization}
        } else {guard updated==errSecSuccess else{throw CoreError.authorization}}
    }
    public func remove() throws {
        let status=SecItemDelete([kSecClass:kSecClassGenericPassword,kSecAttrService:service,kSecAttrAccount:"primary"] as CFDictionary)
        guard status==errSecSuccess || status==errSecItemNotFound else{throw CoreError.authorization}
    }
}

public actor GoogleAccount: Credentials {
    private let transport:any HTTPTransport
    private let store=KeychainStore()
    private var configuration:OAuthConfiguration?
    private var tokens:OAuthTokens?
    private var flag=CancellationFlag()
    private var refreshing:Task<OAuthTokens,Error>?
    public init(transport:any HTTPTransport){self.transport=transport}
    public func restore() throws -> Bool {
        if let record=try store.load(){configuration=record.configuration;if let refresh=record.refreshToken {
            tokens=OAuthTokens(accessToken:"",refreshToken:refresh,expiresAt:.distantPast)
        };return record.refreshToken != nil};return false
    }
    public func configured() -> Bool {configuration != nil}
    public func importConfiguration(_ data:Data) throws {
        let value = try OAuthConfiguration.imported(data)
        try store.save(AccountRecord(configuration: value, refreshToken: nil))
        invalidateSession()
        configuration = value
    }
    public func oauthConfiguration() throws -> OAuthConfiguration {guard let configuration else{throw CoreError.invalidOAuth};return configuration}
    public func install(_ value:OAuthTokens,configuration:OAuthConfiguration) throws {
        try Task.checkCancellation()
        // Publish credentials only after Keychain persistence succeeds.
        try store.save(AccountRecord(configuration:configuration,refreshToken:value.refreshToken))
        invalidateSession()
        tokens = value; self.configuration = configuration
    }
    public func disconnect() throws {
        invalidateSession()
        // Forget credentials; preserve only the OAuth app configuration for the next explicit sign-in.
        if let configuration {try store.save(AccountRecord(configuration:configuration,refreshToken:nil))}
        else {try store.remove()}
    }
    private func invalidateSession() {
        flag.cancel(); flag = CancellationFlag()
        refreshing?.cancel(); refreshing = nil; tokens = nil
    }
    public func credential() async throws -> CredentialLease {
        try Task.checkCancellation()
        if let tokens,tokens.expiresAt.timeIntervalSinceNow>60,!tokens.accessToken.isEmpty {return lease(tokens)}
        guard let refresh=tokens?.refreshToken,let configuration else{throw CoreError.authorization}
        let oldFlag = flag
        let task: Task<OAuthTokens, Error>
        if let pending = refreshing {
            task = pending
        } else {
            task = Task {
                defer { if flag === oldFlag { refreshing = nil } }
                let result = try await GoogleOAuth.refresh(refresh, configuration: configuration, transport: transport)
                try oldFlag.check()
                try store.save(AccountRecord(configuration: configuration, refreshToken: result.refreshToken))
                tokens = result
                return result
            }
            refreshing = task
        }
        let result = try await task.value
        try oldFlag.check()
        return lease(result)
    }
    private func lease(_ tokens:OAuthTokens)->CredentialLease {
        let current=flag;return CredentialLease(token:tokens.accessToken,check:{try current.check()})
    }
}
#endif
