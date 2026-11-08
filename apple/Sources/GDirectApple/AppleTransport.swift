#if canImport(Network) && canImport(Security)
import Foundation
import Network
import Security
import GDirectCore

private final class ConnectionBox: @unchecked Sendable {
    let connection: NWConnection
    private let lock=NSLock()
    private var expired=false
    init(_ connection: NWConnection) { self.connection=connection }
    func cancel() { connection.cancel() }
    func expire() { lock.lock();expired=true;lock.unlock();connection.cancel() }
    var timedOut:Bool {lock.lock();defer{lock.unlock()};return expired}
}

func withDeadline<T: Sendable>(_ seconds: TimeInterval, cancel: @escaping @Sendable () -> Void,
                               operation: @escaping @Sendable () async throws -> T) async throws -> T {
    try await withThrowingTaskGroup(of:T.self) { group in
        group.addTask { try await operation() }
        group.addTask { try await Task.sleep(nanoseconds:UInt64(seconds*1_000_000_000)); cancel(); throw CoreError.timeout }
        defer { group.cancelAll() }; return try await group.next()!
    }
}

/// Network.framework controls the socket endpoint and SNI separately from the HTTP logical origin.
public struct AppleSocketTransport: HTTPTransport, @unchecked Sendable {
    public let route: Route
    private let roots: [SecCertificate]
    private let port:UInt16
    init(route: Route) throws {
        guard IPv4Range.isPublic(route.ip), URLPolicy.allowedHost(route.host) else { throw CoreError.invalidURL }
        self.route=route;self.port=443; self.roots=try Self.certificates(CoreResources.trustedRoots())
        guard roots.count==4 else { throw CoreError.invalidRequest }
    }
    // Only @testable tests can inject an ephemeral loopback root; never part of the public initializer.
    init(testRoute: Route, roots: [SecCertificate],port:UInt16) { self.route=testRoute; self.roots=roots;self.port=port }
    static func certificates(_ pem: Data) throws -> [SecCertificate] {
        guard let text=String(data:pem,encoding:.utf8) else { throw CoreError.invalidRequest }
        return try text.components(separatedBy:"-----BEGIN CERTIFICATE-----").dropFirst().map { block in
            guard let encoded=block.components(separatedBy:"-----END CERTIFICATE-----").first,
                  let der=Data(base64Encoded:encoded,options:.ignoreUnknownCharacters),
                  let certificate=SecCertificateCreateWithData(nil,der as CFData) else { throw CoreError.invalidRequest }
            return certificate
        }
    }
    public func execute(_ request: HTTPRequest, receive: @escaping HTTPReceiver) async throws {
        try request.guardCurrent(); try Task.checkCancellation()
        guard request.url.host==route.host else { throw CoreError.invalidURL }
        let tls=NWProtocolTLS.Options(), tcp=NWProtocolTCP.Options(), queue=DispatchQueue(label:"dev.gdirect.tls.\(UUID().uuidString)")
        if route.identity != .noSNI { sec_protocol_options_set_tls_server_name(tls.securityProtocolOptions,route.tlsHost) }
        sec_protocol_options_add_tls_application_protocol(tls.securityProtocolOptions,"http/1.1")
        sec_protocol_options_set_min_tls_protocol_version(tls.securityProtocolOptions,.TLSv12)
        let anchors=roots, expected=route.tlsHost
        sec_protocol_options_set_verify_block(tls.securityProtocolOptions,{ _, secured, complete in
            let trust=sec_trust_copy_ref(secured).takeRetainedValue()
            guard SecTrustSetAnchorCertificates(trust,anchors as CFArray)==errSecSuccess,
                  SecTrustSetAnchorCertificatesOnly(trust,true)==errSecSuccess,
                  SecTrustSetPolicies(trust,SecPolicyCreateSSL(true,expected as CFString))==errSecSuccess else { complete(false); return }
            complete(SecTrustEvaluateWithError(trust,nil))
        },queue)
        let parameters=NWParameters(tls:tls,tcp:tcp)
        parameters.preferNoProxies=true
        let box=ConnectionBox(NWConnection(host:NWEndpoint.Host(route.ip),port:NWEndpoint.Port(rawValue:port)!,using:parameters))
        // Each read has an idle limit; metadata additionally has a whole-request limit.
        do { try await withTaskCancellationHandler(operation:{
            let operation: @Sendable () async throws -> Void = {
                try await withDeadline(8,cancel:{box.expire()}) { try await ready(box,queue:queue) }
                try Task.checkCancellation(); try request.guardCurrent() // after TLS, before any bearer/header
                let bytes=try wireRequest(request)
                try await withDeadline(20,cancel:{box.expire()}) {
                    try await withCheckedThrowingContinuation { (continuation:CheckedContinuation<Void,Error>) in
                        box.connection.send(content:bytes,completion:.contentProcessed { error in
                            if let error { continuation.resume(throwing:error) } else { continuation.resume() }
                        })
                    }
                }
                var decoder=HTTPDecoder()
                while !decoder.complete {
                    try Task.checkCancellation(); try request.guardCurrent()
                    let result=try await withDeadline(25,cancel:{box.expire()}) {
                        try await withCheckedThrowingContinuation { (continuation:CheckedContinuation<SocketRead,Error>) in
                            box.connection.receive(minimumIncompleteLength:1,maximumLength:65536) { data,_,end,error in
                                if let error { continuation.resume(throwing:error) } else { continuation.resume(returning:SocketRead(data:data ?? Data(),end:end)) }
                            }
                        }
                    }
                    try decoder.feed(result.data,receive:receive)
                    if result.end { try decoder.finish(receive:receive); break }
                }
            }
            defer { box.cancel() }
            if let deadline=request.deadline { try await withDeadline(deadline,cancel:{box.expire()},operation:operation) }
            else { try await operation() }
        },onCancel:{box.cancel()}) }
        catch {if Task.isCancelled{throw CancellationError()};if box.timedOut{throw CoreError.timeout};throw error}
    }
}
private struct SocketRead: Sendable { let data: Data; let end: Bool }

final class OneShot<Value>: @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: CheckedContinuation<Value, Error>?

    init(_ continuation: CheckedContinuation<Value, Error>) { self.continuation = continuation }

    func resume(with result: Result<Value, Error>) {
        let callback = lock.withLock {
            let callback = continuation
            continuation = nil
            return callback
        }
        callback?.resume(with: result)
    }
}

private func ready(_ box: ConnectionBox, queue: DispatchQueue) async throws {
    try await withCheckedThrowingContinuation { continuation in
        let callback = OneShot<Void>(continuation)
        box.connection.stateUpdateHandler = { state in
            switch state {
            case .ready: callback.resume(with: .success(()))
            case .failed(let error): callback.resume(with: .failure(error))
            case .cancelled: callback.resume(with: .failure(CancellationError()))
            default: break
            }
        }
        box.connection.start(queue: queue)
    }
}
private func wireRequest(_ request:HTTPRequest) throws -> Data {
    let parts=URLComponents(url:request.url,resolvingAgainstBaseURL:false)!
    let path=parts.percentEncodedPath.isEmpty ? "/" : parts.percentEncodedPath
    let target=path+(parts.percentEncodedQuery.map { "?"+$0 } ?? "")
    guard !target.contains("\r"), !target.contains("\n") else { throw CoreError.invalidRequest }
    var text="\(request.method) \(target) HTTP/1.1\r\nHost: \(request.url.host!)\r\nConnection: close\r\nUser-Agent: GDirectLite-Swift/0.1\r\n"
    for (key,value) in request.headers.sorted(by: { $0.key<$1.key }) { text += "\(key): \(value)\r\n" }
    if request.method=="POST" { text += "Content-Length: \(request.body.count)\r\n" }
    text += "\r\n"; var data=Data(text.utf8); data.append(request.body); return data
}

private final class SessionDelegate: NSObject,URLSessionTaskDelegate,@unchecked Sendable {
    let roots: [SecCertificate]
    let host: String
    let check: @Sendable () throws -> Void
    init(roots: [SecCertificate], host: String, check: @escaping @Sendable () throws -> Void) {
        self.roots = roots; self.host = host; self.check = check
    }
    func urlSession(_ session:URLSession,task:URLSessionTask,willPerformHTTPRedirection response:HTTPURLResponse,newRequest request:URLRequest,completionHandler:@escaping (URLRequest?)->Void) { completionHandler(nil) }
    func urlSession(_ session: URLSession, task: URLSessionTask, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        completionHandler(.cancelAuthenticationChallenge, nil)
    }
    func urlSession(_ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
                    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              challenge.protectionSpace.host.lowercased() == host.lowercased(),
              let trust = challenge.protectionSpace.serverTrust,
              SecTrustSetAnchorCertificates(trust,roots as CFArray)==errSecSuccess,
              SecTrustSetAnchorCertificatesOnly(trust,true)==errSecSuccess,
              SecTrustSetPolicies(trust,SecPolicyCreateSSL(true,challenge.protectionSpace.host as CFString))==errSecSuccess,
              SecTrustEvaluateWithError(trust,nil) else{completionHandler(.cancelAuthenticationChallenge,nil);return}
        do{try check();completionHandler(.useCredential,URLCredential(trust:trust))}
        catch{completionHandler(.cancelAuthenticationChallenge,nil)}
    }
}
/// System mode and public range catalogs. No cookies/cache/automatic redirect or credential persistence.
public struct SystemTransport: HTTPTransport {
    public init() {}
    public func execute(_ request:HTTPRequest,receive:@escaping HTTPReceiver) async throws {
        try request.guardCurrent(); try Task.checkCancellation()
        let configuration=URLSessionConfiguration.ephemeral; configuration.httpCookieStorage=nil; configuration.urlCredentialStorage=nil;configuration.httpShouldSetCookies=false
        configuration.urlCache=nil; configuration.timeoutIntervalForRequest=25
        configuration.timeoutIntervalForResource=request.deadline ?? 86400
        let delegate=SessionDelegate(roots:try AppleSocketTransport.certificates(CoreResources.trustedRoots()),host: request.url.host!, check: request.guardCurrent)
        let session=URLSession(configuration:configuration,delegate:delegate,delegateQueue:nil)
        defer { session.invalidateAndCancel() }
        var native=URLRequest(url:request.url); native.httpMethod=request.method; native.httpBody=request.body.isEmpty ? nil : request.body
        for (key,value) in request.headers { native.setValue(value,forHTTPHeaderField:key) }
        let bytes: URLSession.AsyncBytes, response: URLResponse
        do {
            (bytes, response) = try await session.bytes(for: native)
        } catch let error as URLError {
            try request.guardCurrent()
            switch error.code {
            case .secureConnectionFailed, .serverCertificateHasBadDate, .serverCertificateUntrusted,
                 .serverCertificateHasUnknownRoot, .serverCertificateNotYetValid:
                throw CoreError.secureConnection
            case .timedOut:
                throw CoreError.timeout
            default:
                throw error
            }
        }
        guard let response=response as? HTTPURLResponse else { throw CoreError.malformedHTTP }
        try request.guardCurrent()
        let headers=response.allHeaderFields.reduce(into:[String:String]()) { result,field in
            if let name=field.key as? String { result[name.lowercased()]=String(describing:field.value) }
        }
        try receive(.head(HTTPHead(status:response.statusCode,headers:headers)))
        var chunk=Data(); chunk.reserveCapacity(65536)
        for try await byte in bytes {
            chunk.append(byte)
            if chunk.count==65536 { try Task.checkCancellation(); try request.guardCurrent(); try receive(.body(chunk)); chunk.removeAll(keepingCapacity:true) }
        }
        if !chunk.isEmpty { try request.guardCurrent(); try receive(.body(chunk)) }
    }
}
#else
// Linux builds the protocol/API/state machines. Apple framework execution is tested on the macOS runner.
public enum ApplePlatformUnavailable {}
#endif
