#if os(macOS)
import AppKit
import Foundation
import Network
import GDirectCore

/// RFC 8252: ephemeral IPv4 loopback listener, PKCE/state validation, bounded unrelated requests.
public final class BrowserOAuth:@unchecked Sendable {
    private let queue=DispatchQueue(label:"dev.gdirect.oauth.loopback")
    private let lock=NSLock()
    private var listener:NWListener?
    private var clients:[NWConnection]=[]
    private var continuation:CheckedContinuation<URL,Error>?
    private var expected:OAuthAttempt?
    private var active=false
    public init(){}
    public func cancel(){terminate(CancellationError())}
    private func expire(){terminate(CoreError.timeout)}
    private func terminate(_ error:Error){
        lock.lock();let callback=continuation;continuation=nil;let server=listener;listener=nil
        let sockets=clients;clients=[];active=false;expected=nil;lock.unlock()
        server?.cancel();sockets.forEach{$0.cancel()};callback?.resume(throwing:error)
    }
    @MainActor public func signIn(configuration:OAuthConfiguration,transport:any HTTPTransport)async throws->OAuthTokens{
        try begin();defer{cancel()}
        return try await withTaskCancellationHandler(operation:{
            let port = try await withDeadline(8, cancel: { self.expire() }) { try await self.start() }
            let attempt = try OAuthAttempt(port: port)
            let callback=try await withDeadline(300,cancel:{self.expire()}){try await self.wait(attempt:attempt,configuration:configuration)}
            try Task.checkCancellation()
            return try await GoogleOAuth.exchange(attempt:attempt,code:attempt.code(from:callback),configuration:configuration,transport:transport)
        },onCancel:{ self.cancel() })
    }
    private func begin()throws{lock.lock();defer{lock.unlock()};guard !active else{throw CoreError.invalidOAuth};active=true}
    private func start()async throws->UInt16{
        let parameters=NWParameters.tcp;parameters.requiredLocalEndpoint = .hostPort(host:.ipv4(.loopback),port:.any)
        let server = try NWListener(using: parameters)
        guard lock.withLock({
            guard active else { return false }
            listener = server
            return true
        }) else { throw CancellationError() }
        server.newConnectionHandler = { [weak self] socket in self?.accept(socket) }
        return try await withCheckedThrowingContinuation { continuation in
            let callback = OneShot<UInt16>(continuation)
            server.stateUpdateHandler = { [weak server] state in
                switch state {
                case .ready:
                    if let port = server?.port { callback.resume(with: .success(port.rawValue)) }
                    else { callback.resume(with: .failure(CoreError.invalidOAuth)) }
                case .failed(let error): callback.resume(with: .failure(error))
                case .cancelled: callback.resume(with: .failure(CancellationError()))
                default: break
                }
            }
            server.start(queue: queue)
        }
    }
    private func wait(attempt:OAuthAttempt,configuration:OAuthConfiguration)async throws->URL{
        try Task.checkCancellation()
        return try await withCheckedThrowingContinuation{callback in
            lock.lock();guard active else{lock.unlock();callback.resume(throwing:CancellationError());return}
            expected=attempt;continuation=callback;lock.unlock()
            Task { @MainActor in
                guard self.lock.withLock({ self.continuation != nil }) else { return }
                if !NSWorkspace.shared.open(attempt.authorizationURL(configuration: configuration)) {
                    self.terminate(CoreError.invalidOAuth)
                }
            }
        }
    }
    private func accept(_ socket:NWConnection){
        lock.lock();guard active,clients.count<4 else{lock.unlock();socket.cancel();return};clients.append(socket);lock.unlock()
        socket.start(queue:queue);read(socket,buffer:Data())
        queue.asyncAfter(deadline:.now()+5){[weak self,weak socket] in if let socket{self?.close(socket)}}
    }
    private func close(_ socket:NWConnection){lock.lock();clients.removeAll{$0===socket};lock.unlock();socket.cancel()}
    private func read(_ socket:NWConnection,buffer:Data){
        socket.receive(minimumIncompleteLength:1,maximumLength:8192){[weak self] data,_,end,error in
            guard let self else{socket.cancel();return};var buffer=buffer;buffer.append(data ?? Data())
            guard error==nil,buffer.count<=16384 else{self.close(socket);return}
            guard buffer.range(of:Data("\r\n\r\n".utf8)) != nil else{if end{self.close(socket)}else{self.read(socket,buffer:buffer)};return}
            self.lock.lock();let attempt=self.expected;self.lock.unlock()
            guard let line=String(data:buffer,encoding:.utf8)?.components(separatedBy:"\r\n").first else{self.close(socket);return}
            let fields=line.split(separator:" ")
            guard let attempt,fields.count==3,fields[0]=="GET",fields[1].hasPrefix("/callback?"),
                  let url=URL(string:"http://127.0.0.1:\(attempt.redirect.port!)\(fields[1])"),
                  (try? attempt.callback(from:url)) != nil else{self.close(socket);return}
            // Never echo code/state/tokens in HTML, logs, redirects or error messages.
            let body="Google callback received. Return to GDirect Lite to finish connecting."
            let reply="HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nCache-Control: no-store\r\nContent-Security-Policy: default-src 'none'\r\nConnection: close\r\nContent-Length: \(body.utf8.count)\r\n\r\n\(body)"
            self.lock.lock();let callback=self.continuation;self.continuation=nil;self.lock.unlock()
            socket.send(content:Data(reply.utf8),completion:.contentProcessed{[weak self] _ in self?.close(socket)})
            callback?.resume(returning:url)
        }
    }
    deinit{cancel()}
}
#endif
