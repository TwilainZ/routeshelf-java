import Foundation

public struct IPv4Range: Sendable, Equatable {
    public let network: UInt32, mask: UInt32
    public init?(_ cidr: String) {
        let parts=cidr.split(separator:"/",omittingEmptySubsequences:false)
        guard parts.count==2, let address=Self.address(String(parts[0])), let bits=Int(parts[1]), (0...32).contains(bits) else { return nil }
        let mask: UInt32=bits==0 ? 0 : UInt32.max << (32-bits)
        self.network=address & mask; self.mask=mask
    }
    public static func address(_ text: String) -> UInt32? {
        let parts=text.split(separator:".",omittingEmptySubsequences:false); guard parts.count==4 else { return nil }
        var value: UInt32=0
        for part in parts {
            guard !part.isEmpty, (part.count==1 || !part.hasPrefix("0")), part.utf8.allSatisfy({ (48...57).contains($0) }), let n=UInt32(part), n<=255 else { return nil }
            value=(value << 8)|n
        }; return value
    }
    public func contains(_ text: String) -> Bool { IPv4Range.address(text).map { $0 & mask == network } ?? false }
    public static func isPublic(_ text: String) -> Bool {
        guard let n=address(text), n < 0xe0000000 else { return false }
        let excluded=["0.0.0.0/8","10.0.0.0/8","100.64.0.0/10","127.0.0.0/8","169.254.0.0/16","172.16.0.0/12","192.168.0.0/16","192.0.0.0/24","192.0.2.0/24","198.18.0.0/15","198.51.100.0/24","203.0.113.0/24"]
        return !excluded.contains { IPv4Range($0)!.contains(text) }
    }
}
public enum TLSIdentity: String, Codable, Sendable, CaseIterable { case googleFront, normal, noSNI }
public struct Route: Codable, Hashable, Sendable {
    public let host: String, ip: String
    public let identity: TLSIdentity
    public init(host: String, ip: String, identity: TLSIdentity) { self.host=host; self.ip=ip; self.identity=identity }
    public var isRelay: Bool { ip == "183.56.143.147" }
    public var tlsHost: String { identity == .googleFront ? "g.cn" : host }
}
public enum ConnectionMode: String, Codable, Sendable, CaseIterable { case googleOnly, relayAllowed, system }
public struct GoogleRanges: Sendable {
    public let google: [IPv4Range], cloud: [IPv4Range]
    public init(google: [IPv4Range], cloud: [IPv4Range]) { self.google=google; self.cloud=cloud }
    public func contains(_ ip: String) -> Bool { IPv4Range.isPublic(ip) && google.contains { $0.contains(ip) } && !cloud.contains { $0.contains(ip) } }
    public static func parse(_ data: Data) throws -> [IPv4Range] {
        struct Catalog: Decodable { let prefixes: [Prefix] }; struct Prefix: Decodable { let ipv4Prefix: String? }
        guard data.count<=1024*1024 else { throw CoreError.responseTooLarge }
        let result=try JSONDecoder().decode(Catalog.self,from:data).prefixes.compactMap { $0.ipv4Prefix.flatMap(IPv4Range.init) }
        guard result.count>=20 else { throw CoreError.invalidRequest }; return result
    }
}
public enum CoreResources {
    public static let rootSHA256="6ba8f2f19e161f295a52b49e627796d7dbbeab3c7e60a46b07529e98e18536d7"
    public static func trustedRoots() throws -> Data {
        let roots=try data("gts-roots.pem")
        guard Digest.sha256(roots).map({String(format:"%02x",$0)}).joined()==rootSHA256 else{throw CoreError.integrity}
        return roots
    }
    public static func data(_ name:String)throws->Data{
        guard ["google-bootstrap.json","gts-roots.pem","gts-roots.LICENSE"].contains(name) else{throw CoreError.invalidRequest}
        if Bundle.main.bundleURL.pathExtension=="app" {
            guard let root=Bundle.main.resourceURL else{throw CoreError.invalidRequest}
            let package=root.appendingPathComponent("GDirectSwift_GDirectCore.bundle")
            for relative in ["Resources/", "Contents/Resources/Resources/"]{
                let url=package.appendingPathComponent(relative+name)
                if FileManager.default.fileExists(atPath:url.path){return try Data(contentsOf:url)}
            };throw CoreError.invalidRequest
        }
        guard let url=Bundle.module.url(forResource:name,withExtension:nil,subdirectory:"Resources") else{throw CoreError.invalidRequest}
        return try Data(contentsOf:url)
    }
    public static var bootstrap: (ranges: GoogleRanges, candidates: [String]) {
        struct Bootstrap: Decodable { let prefixes: [String]; let candidates: [String] }
        guard let data=try? self.data("google-bootstrap.json"), let value=try? JSONDecoder().decode(Bootstrap.self,from:data) else { return (GoogleRanges(google:[],cloud:[]),[]) }
        return (GoogleRanges(google:value.prefixes.compactMap(IPv4Range.init),cloud:[]),value.candidates)
    }
}

/// Single-flight discovery per host. A reset invalidates in-flight verification before cache publication.
public actor RouteSelector {
    public typealias Probe = @Sendable (Route) async throws -> Void
    private struct Pending {let id:UUID;let task:Task<Route,Error>}
    private var verified:[String:(Route,Date)]=[:]
    private var pending:[String:Pending]=[:]
    private var rejected:[Route:Date]=[:]
    private var epoch=0
    public init(){}
    public func reset(){epoch+=1;pending.values.forEach{$0.task.cancel()};pending.removeAll();verified.removeAll();rejected.removeAll()}
    public func reject(_ route:Route){rejected[route]=Date();if verified[route.host]?.0==route{verified[route.host]=nil}}
    public func select(host:String,candidates:[String],ranges:GoogleRanges,mode:ConnectionMode,excluded:Set<Route>=[],probe:@escaping Probe)async throws->Route{
        guard URLPolicy.allowedHost(host) else{throw CoreError.invalidURL};try Task.checkCancellation()
        rejected=rejected.filter{Date().timeIntervalSince($0.value)<60}
        let exclusions=excluded.union(rejected.keys)
        if let (route,date)=verified[host],Date().timeIntervalSince(date)<86400,!exclusions.contains(route),
           (route.isRelay ? mode == .relayAllowed : ranges.contains(route.ip)){return route}
        let ticket=epoch,record:Pending
        if let active=pending[host]{record=active}
        else{
            let task=Task<Route,Error>{try await Self.discover(host:host,candidates:candidates,ranges:ranges,mode:mode,excluded:exclusions,probe:probe)}
            record=Pending(id:UUID(),task:task);pending[host]=record
            Task{let result=await task.result;self.completed(host:host,id:record.id,epoch:ticket,result:result)}
        }
        do{
            let route=try await cancellableValue(record.task);try Task.checkCancellation()
            guard ticket==epoch else{throw CancellationError()}
            if pending[host]?.id==record.id{pending[host]=nil}
            guard !exclusions.contains(route),rejected[route]==nil else{throw CoreError.noRoute}
            verified[host]=(route,Date());return route
        }catch{
            // A single canceled consumer must not cancel another consumer's discovery.
            if !(error is CancellationError && !record.task.isCancelled),ticket==epoch,pending[host]?.id==record.id{pending[host]=nil}
            throw error
        }
    }
    private func completed(host:String,id:UUID,epoch ticket:Int,result:Result<Route,Error>){
        guard ticket==epoch,pending[host]?.id==id else{return};pending[host]=nil
        if case .success(let route)=result,rejected[route]==nil{verified[host]=(route,Date())}
    }
    private static func discover(host:String,candidates:[String],ranges:GoogleRanges,mode:ConnectionMode,excluded:Set<Route>,probe:@escaping Probe)async throws->Route{
        var seen=Set<String>(),ips=candidates.filter{ranges.contains($0)&&seen.insert($0).inserted}.prefix(24).map{$0}
        if mode == .relayAllowed{ips.insert("183.56.143.147",at:0)}
        let started=Date()
        for start in stride(from:0,to:ips.count,by:3){
            try Task.checkCancellation();guard Date().timeIntervalSince(started)<35 else{throw CoreError.timeout}
            let result=try await withThrowingTaskGroup(of:Route?.self,returning:Route?.self){group in
                for ip in ips[start..<min(start+3,ips.count)]{group.addTask{
                    for identity in TLSIdentity.allCases{
                        let route=Route(host:host,ip:ip,identity:identity);if excluded.contains(route){continue}
                        do{try Task.checkCancellation();try await probe(route);try Task.checkCancellation();return route}
                        catch is CancellationError{throw CancellationError()}
                        catch{continue}
                    };return nil
                }}
                while let result=try await group.next(){if let route=result{group.cancelAll();return route}};return nil
            }
            if let result{return result}
        };throw CoreError.noRoute
    }
}

private final class DiscoveryWaiter:@unchecked Sendable {
    private let lock=NSLock()
    private var result:Result<Route,Error>?
    private var continuation:CheckedContinuation<Route,Error>?
    func install(_ value:CheckedContinuation<Route,Error>){
        lock.lock();if let result{lock.unlock();value.resume(with:result)}else{continuation=value;lock.unlock()}
    }
    func finish(_ value:Result<Route,Error>){
        lock.lock();guard result==nil else{lock.unlock();return};result=value;let callback=continuation;continuation=nil;lock.unlock();callback?.resume(with:value)
    }
}
private func cancellableValue(_ task:Task<Route,Error>)async throws->Route{
    let waiter=DiscoveryWaiter()
    return try await withTaskCancellationHandler(operation:{
        try await withCheckedThrowingContinuation{callback in
            waiter.install(callback);Task{waiter.finish(await task.result)}
        }
    },onCancel:{waiter.finish(.failure(CancellationError()))})
}
