import Foundation
#if canImport(Darwin)
import Darwin
#else
import Glibc
#endif

public struct TransferProgress: Sendable, Equatable { public let received: Int64; public let total: Int64?; public init(received:Int64,total:Int64?){self.received=received;self.total=total} }
public struct DownloadResult: Sendable { public let file: URL; public let bytes: Int64 }

public enum SafeFiles {
    public static func filename(_ original: String) -> String {
        let cleaned = original.map { char -> String in
            char.unicodeScalars.contains(where: { $0.value < 32 || $0.value == 127 }) || char == "/" || char == "\\" || char == ":" ? "_" : String(char)
        }.joined().trimmingCharacters(in: CharacterSet(charactersIn: ". "))
        var result = ""
        for char in cleaned { if result.utf8.count + String(char).utf8.count > 180 { break }; result.append(char) }
        return result.isEmpty ? "GoogleDrive-file" : result
    }
    static func exclusive(_ url: URL) throws -> FileHandle {
        let fd = url.path.withCString { open($0, O_WRONLY | O_CREAT | O_EXCL | O_NOFOLLOW, mode_t(0o600)) }
        guard fd >= 0 else { throw CoreError.unsafeFile }; return FileHandle(fileDescriptor: fd, closeOnDealloc: true)
    }
    static func publish(_ temporary: URL, directory: URL, name: String, check: () throws -> Void) throws -> URL {
        let base = filename(name), ext = (base as NSString).pathExtension, stem = (base as NSString).deletingPathExtension
        for index in 0..<1000 {
            try check()
            let candidate = directory.appendingPathComponent(index == 0 ? base : stem + " (\(index))" + (ext.isEmpty ? "" : "." + ext))
            // link(2) atomically refuses existing destinations, including symbolic links.
            if link(temporary.path, candidate.path) == 0 { try? FileManager.default.removeItem(at: temporary); return candidate }
            if errno == EEXIST { continue }
            let output: FileHandle
            do { output = try exclusive(candidate) } catch { if FileManager.default.fileExists(atPath: candidate.path) { continue }; throw error }
            do {
                let input = try FileHandle(forReadingFrom: temporary); defer { try? input.close() }
                while let chunk = try input.read(upToCount: 65536), !chunk.isEmpty { try check(); try output.write(contentsOf: chunk) }
                try output.synchronize(); try output.close(); try? FileManager.default.removeItem(at: temporary); return candidate
            } catch { try? output.close(); try? FileManager.default.removeItem(at: candidate); throw error }
        }
        throw CoreError.unsafeFile
    }
}

private final class DownloadSink: @unchecked Sendable {
    let file: FileHandle
    let expected: Int64?
    let rejectHTML:Bool
    let check: @Sendable () throws -> Void
    let progress: @Sendable (TransferProgress) -> Void
    var received: Int64 = 0
    var etag: String?
    var head: HTTPHead?
    var rangeStart: Int64 = 0
    var responseBytes: Int64 = 0
    var responseLength: Int64?
    var total: Int64?
    init(file: FileHandle, spec: DownloadSpec, progress: @escaping @Sendable (TransferProgress) -> Void) {
        self.file=file; self.expected=spec.expectedSize;self.rejectHTML=spec.rejectHTML; self.total=spec.expectedSize; self.check=spec.check; self.progress=progress
    }
    func begin() { head=nil; rangeStart=received; responseBytes=0; responseLength=nil }
    func receive(_ event: HTTPEvent) throws {
        try check(); try Task.checkCancellation()
        switch event {
        case .head(let value):
            guard head == nil else { throw CoreError.malformedHTTP }; head=value
            guard value.status == 200 || value.status == 206 else { return }
            // Public Google confirmation/quota HTML must never be saved as the file.
            if rejectHTML && value["content-type"]?.lowercased().hasPrefix("text/html") == true { throw CoreError.permission }
            guard value["content-encoding"] == nil || value["content-encoding"]?.lowercased() == "identity" else { throw CoreError.integrity }
            if value.status == 206 {
                guard rangeStart > 0, let text=value["content-range"], let range=ByteRange.parse(text), range.start==rangeStart,
                      range.total == total, let old=etag, value["etag"]==old else { throw CoreError.integrity }
                responseLength=range.end-range.start+1
            } else {
                if rangeStart > 0 { try file.truncate(atOffset: 0); try file.seek(toOffset: 0); received=0; rangeStart=0 }
                let length=value["content-length"].flatMap(Int64.init)
                guard length == nil || length! >= 0 else { throw CoreError.integrity }
                if let expected, let length, expected != length { throw CoreError.integrity }
                total=expected ?? length; responseLength=length
                etag=ByteRange.strongETag(value["etag"])
            }
        case .body(let bytes):
            guard let head else { throw CoreError.malformedHTTP }
            guard head.status == 200 || head.status == 206 else {
                responseBytes += Int64(bytes.count); if responseBytes > 65536 { throw CoreError.responseTooLarge }; return
            }
            guard Int64(bytes.count) <= Int64.max-received else { throw CoreError.integrity }
            if let total, Int64(bytes.count) > total-received { throw CoreError.integrity }
            if let length=responseLength, Int64(bytes.count) > length-responseBytes { throw CoreError.integrity }
            try file.write(contentsOf: bytes); received += Int64(bytes.count); responseBytes += Int64(bytes.count)
            progress(TransferProgress(received: received, total: total))
        }
    }
    func finish() throws {
        guard let head else { throw CoreError.malformedHTTP }
        if head.status == 401 { throw CoreError.authorization }; if head.status == 403 { throw CoreError.permission }
        guard head.status == 200 || head.status == 206 else { throw CoreError.http(head.status) }
        if let total, received != total { throw CoreError.integrity }
        if let length=responseLength, responseBytes != length { throw CoreError.integrity }
    }
    func restart() throws { try file.truncate(atOffset: 0); try file.seek(toOffset: 0); received=0; etag=nil; total=expected }
}

public struct ByteRange: Sendable, Equatable {
    public let start: Int64, end: Int64, total: Int64
    public static func parse(_ value: String) -> ByteRange? {
        guard value.hasPrefix("bytes ") else { return nil }
        let fields=value.dropFirst(6).split(separator: "/", omittingEmptySubsequences: false)
        guard fields.count==2 else { return nil }; let ends=fields[0].split(separator: "-", omittingEmptySubsequences: false)
        guard ends.count==2, let start=Int64(ends[0]), let end=Int64(ends[1]), let total=Int64(fields[1]),
              start>=0, end>=start, total>end else { return nil }
        return ByteRange(start:start,end:end,total:total)
    }
    public static func strongETag(_ text: String?) -> String? {
        guard let text, text.utf8.count<1024, text.hasPrefix("\""), text.hasSuffix("\""), text.count>=2,
              !text.contains("\r"), !text.contains("\n") else { return nil }; return text
    }
}

public struct DownloadEngine: Sendable {
    public let transport: any HTTPTransport
    public init(transport: any HTTPTransport) { self.transport=transport }
    public func download(_ spec: DownloadSpec, directory: URL, progress: @escaping @Sendable (TransferProgress) -> Void = { _ in }) async throws -> DownloadResult {
        try spec.check(); try Task.checkCancellation()
        guard spec.expectedSize.map({$0>=0}) ?? true else{throw CoreError.invalidRequest}
        let temporary=directory.appendingPathComponent(".gdirect-\(UUID().uuidString).part"), file=try SafeFiles.exclusive(temporary)
        defer { try? file.close(); try? FileManager.default.removeItem(at: temporary) }
        let sink=DownloadSink(file:file,spec:spec,progress:progress)
        for attempt in 0..<3 {
            try spec.check(); try Task.checkCancellation()
            if sink.received>0 && (sink.etag==nil || sink.total==nil) { try sink.restart() }
            var url=spec.url, headers=spec.headers; var hops=0
            while true {
                var actual=headers; actual["Accept-Encoding"]="identity"
                if sink.received>0, let tag=sink.etag { actual["Range"]="bytes=\(sink.received)-"; actual["If-Range"]=tag }
                sink.begin()
                do {
                    let request=try HTTPRequest(url:url,headers:actual,deadline:nil,guardCurrent:spec.check)
                    try await transport.execute(request, receive:{ try sink.receive($0) })
                    if let head=sink.head, [301,302,303,307,308].contains(head.status) {
                        guard hops<4, let location=head["location"] else { throw CoreError.invalidURL }
                        url=try URLPolicy.contentRedirect(location,from:url); headers=[:]; hops+=1; continue
                    }
                    try sink.finish(); try spec.check(); try file.synchronize()
                    if let md5=spec.md5 { guard md5.count==32, try Digest.md5File(temporary,check:{try spec.check();try Task.checkCancellation()}).lowercased()==md5.lowercased() else { throw CoreError.integrity } }
                    try Task.checkCancellation(); try spec.check(); try file.close()
                    let destination=try SafeFiles.publish(temporary,directory:directory,name:spec.filename,check:{try spec.check();try Task.checkCancellation()})
                    return DownloadResult(file:destination,bytes:sink.received)
                } catch {
                    try spec.check(); try Task.checkCancellation()
                    if error is CancellationError { throw error }
                    if let error=error as? CoreError, error != .timeout && error != .malformedHTTP { throw error }
                    if attempt==2 { throw error }; break
                }
            }
        }
        throw CoreError.integrity
    }
}
