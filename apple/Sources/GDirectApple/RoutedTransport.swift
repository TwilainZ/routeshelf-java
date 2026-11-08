#if canImport(Network)
import Foundation
import Network
import GDirectCore

public struct ConnectionSnapshot: Sendable {
    public let mode:ConnectionMode
    public let route:Route?
    public let verifiedAt:Date?
}
public actor RoutedTransport: HTTPTransport {
    private let selector=RouteSelector()
    private var mode:ConnectionMode = .googleOnly
    private var ranges=CoreResources.bootstrap.ranges
    private var candidates=CoreResources.bootstrap.candidates
    private var rangesFetched:Date?
    private var networkFlag=CancellationFlag()
    private var lastRoute:Route?
    private var verifiedAt:Date?
    public init() {}
    public func snapshot() -> ConnectionSnapshot { ConnectionSnapshot(mode:mode,route:lastRoute,verifiedAt:verifiedAt) }
    public func configure(_ mode:ConnectionMode) async { self.mode=mode; await reset() }
    public func reset() async { networkFlag.cancel(); networkFlag=CancellationFlag(); lastRoute=nil; verifiedAt=nil; await selector.reset() }
    public func updateRanges() async {
        if let date=rangesFetched, Date().timeIntervalSince(date)<3600 { return }
        rangesFetched=Date() // failure backoff; bootstrap remains narrow and attributed
        do {
            let system=SystemTransport()
            let goog=try await ResponseBuffer.read(HTTPRequest(url:URL(string:"https://www.gstatic.com/ipranges/goog.json")!,deadline:3),using:system)
            let cloud=try await ResponseBuffer.read(HTTPRequest(url:URL(string:"https://www.gstatic.com/ipranges/cloud.json")!,deadline:3),using:system)
            guard goog.0.status==200,cloud.0.status==200 else { return }
            let fresh=GoogleRanges(google:try GoogleRanges.parse(goog.1),cloud:try GoogleRanges.parse(cloud.1))
            var sampled=CoreResources.bootstrap.candidates.filter { fresh.contains($0) }
            for range in fresh.google.shuffled() {
                if sampled.count>=24 { break }
                let host=UInt32.random(in:0...UInt32.max) & ~range.mask, value=range.network | host
                let ip=[24,16,8,0].map { String((value >> $0)&255) }.joined(separator:".")
                if fresh.contains(ip) { sampled.append(ip) }
            }
            ranges=fresh; candidates=sampled
        } catch { } // public catalogs never contain account credentials
    }
    public func execute(_ original:HTTPRequest,receive:@escaping HTTPReceiver) async throws {
        let pathFlag=networkFlag, selectedMode=mode
        var request=original; request.guardCurrent={ try pathFlag.check(); try original.guardCurrent() }
        if selectedMode == .system { try await SystemTransport().execute(request,receive:receive); return }
        var excluded=Set<Route>()
        for attempt in 0..<(request.method=="GET" ? 2 : 1) {
            try request.guardCurrent(); try Task.checkCancellation()
            let route=try await selector.select(host:request.url.host!,candidates:candidates,ranges:ranges,mode:selectedMode,excluded:excluded) { route in
                let path=route.host=="www.googleapis.com" ? "/drive/v3/files?pageSize=1&fields=files(id)" : route.host=="oauth2.googleapis.com" ? "/token" : "/"
                let probe=try HTTPRequest(url:URL(string:"https://\(route.host)\(path)")!,deadline:3)
                let (head,data)=try await ResponseBuffer.read(probe,using:AppleSocketTransport(route:route),limit:65536)
                if route.host=="www.googleapis.com" {
                    guard head.status==401, (try? JSONSerialization.jsonObject(with:data)) != nil else { throw CoreError.noRoute }
                } else { guard (200...499).contains(head.status), ![301,302,303,307,308].contains(head.status) else { throw CoreError.noRoute } }
            }
            try request.guardCurrent(); try Task.checkCancellation()
            let response=ResponseMarker()
            do {
                try await AppleSocketTransport(route:route).execute(request) { event in
                    if case .head = event { response.mark() }
                    do{try receive(event)}catch{response.consumerFailed();throw error}
                }
                try request.guardCurrent(); lastRoute=route; verifiedAt=Date(); return
            } catch {
                try request.guardCurrent(); try Task.checkCancellation()
                if error is CancellationError { throw error }
                if response.failedConsumer {throw error} // disk/validation failures do not poison a healthy route
                await selector.reject(route); excluded.insert(route)
                if response.seen || attempt==1 || request.method != "GET" { throw error }
            }
        }
    }
    public func checkAPI() async throws {
        let request=try HTTPRequest(url:URL(string:"https://www.googleapis.com/drive/v3/files?pageSize=1&fields=files(id)")!)
        let (head,_)=try await ResponseBuffer.read(request,using:self)
        guard head.status==401 else { throw CoreError.noRoute }
    }
}
private final class ResponseMarker: @unchecked Sendable {
    private let lock=NSLock(); private var value=false,consumer=false
    func mark(){lock.lock();value=true;lock.unlock()}
    func consumerFailed(){lock.lock();consumer=true;lock.unlock()}
    var failedConsumer:Bool{lock.lock();defer{lock.unlock()};return consumer}
    var seen:Bool { lock.lock();defer{lock.unlock()};return value }
}
public final class NetworkWatcher: @unchecked Sendable {
    private let monitor=NWPathMonitor()
    private var signature:String?
    public init(transport:RoutedTransport){
        monitor.pathUpdateHandler={ [weak self] path in
            let value="\(path.status)-\(path.isExpensive)-\(path.availableInterfaces.map(\.name).sorted().joined(separator:","))"
            guard let self else {return}; if self.signature != nil {Task {await transport.reset()}}
            self.signature=value
        };monitor.start(queue:DispatchQueue(label:"dev.gdirect.path"))
    }
    deinit{monitor.cancel()}
}
#endif
