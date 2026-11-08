import Foundation

/// Incremental HTTP/1.1 framing; bounded headers, strict length/chunk rules, no response smuggling.
public struct HTTPDecoder {
    private enum State { case headers, fixed(Int64), chunkSize, chunk(Int), chunkEnd, trailers, untilClose, complete }
    private var state = State.headers
    private var buffer = Data()
    private var headerBytes = 0
    public private(set) var complete = false
    public init() {}
    public mutating func feed(_ bytes: Data, receive: HTTPReceiver) throws {
        guard !complete || bytes.isEmpty else { throw CoreError.malformedHTTP }
        buffer.append(bytes)
        while true {
            switch state {
            case .headers:
                guard let range = buffer.range(of: Data("\r\n\r\n".utf8)) else {
                    if buffer.count > 32768 { throw CoreError.responseTooLarge }; return
                }
                let block = buffer.prefix(upTo: range.lowerBound); headerBytes += range.upperBound-buffer.startIndex
                guard headerBytes <= 32768, let text = String(data: block, encoding: .utf8) else { throw CoreError.malformedHTTP }
                buffer.removeSubrange(..<range.upperBound)
                let lines = text.components(separatedBy: "\r\n"), statusParts = (lines.first ?? "").split(separator: " ", maxSplits: 2)
                guard statusParts.count >= 2, ["HTTP/1.1", "HTTP/1.0"].contains(statusParts[0]),
                      let status = Int(statusParts[1]), (100...599).contains(status) else { throw CoreError.malformedHTTP }
                var fields: [String: String] = [:]
                for line in lines.dropFirst() {
                    guard let colon = line.firstIndex(of: ":"), !line.hasPrefix(" "), !line.hasPrefix("\t") else { throw CoreError.malformedHTTP }
                    let name = String(line[..<colon]).lowercased(), value = line[line.index(after: colon)...].trimmingCharacters(in: .whitespaces)
                    guard !name.isEmpty, !name.contains(where: { $0.isWhitespace }), !value.contains("\0") else { throw CoreError.malformedHTTP }
                    if fields[name] != nil && ["content-length", "transfer-encoding", "location"].contains(name) { throw CoreError.malformedHTTP }
                    fields[name] = fields[name].map { $0 + "," + value } ?? value
                }
                if status < 200 { guard status != 101 else { throw CoreError.malformedHTTP }; continue }
                let transfer = fields["transfer-encoding"]?.lowercased()
                guard transfer == nil || (transfer == "chunked" && fields["content-length"] == nil) else { throw CoreError.malformedHTTP }
                try receive(.head(HTTPHead(status: status, headers: fields)))
                if status == 204 || status == 304 { state = .complete }
                else if transfer == "chunked" { state = .chunkSize }
                else if let length = fields["content-length"] {
                    guard !length.isEmpty, length.utf8.allSatisfy({ (48...57).contains($0) }), let count = Int64(length), count >= 0 else { throw CoreError.malformedHTTP }
                    state = count == 0 ? .complete : .fixed(count)
                } else { state = .untilClose }
            case .fixed(let remaining):
                guard !buffer.isEmpty else { return }
                let count = Int(min(Int64(buffer.count), remaining)); try receive(.body(Data(buffer.prefix(count)))); buffer.removeFirst(count)
                state = remaining == Int64(count) ? .complete : .fixed(remaining - Int64(count))
            case .chunkSize:
                guard let line = try takeLine(maximum: 1024) else { return }
                let size = line.split(separator: ";", maxSplits: 1, omittingEmptySubsequences: false)[0]
                guard !size.isEmpty, size.utf8.allSatisfy({ (48...57).contains($0) || (65...70).contains($0) || (97...102).contains($0) }),
                      let count = Int(size, radix: 16), count >= 0 else { throw CoreError.malformedHTTP }
                state = count == 0 ? .trailers : .chunk(count)
            case .chunk(let remaining):
                guard !buffer.isEmpty else { return }
                let count = min(buffer.count, remaining); try receive(.body(Data(buffer.prefix(count)))); buffer.removeFirst(count)
                state = remaining == count ? .chunkEnd : .chunk(remaining - count)
            case .chunkEnd:
                guard buffer.count >= 2 else { return }
                guard buffer.prefix(2) == Data("\r\n".utf8) else { throw CoreError.malformedHTTP }; buffer.removeFirst(2); state = .chunkSize
            case .trailers:
                guard let line = try takeLine(maximum: 8192) else { return }
                headerBytes += line.utf8.count + 2
                guard headerBytes <= 32768 else { throw CoreError.responseTooLarge }
                if line.isEmpty { state = .complete }
                else { guard line.contains(":"), !line.hasPrefix(" "), !line.hasPrefix("\t") else { throw CoreError.malformedHTTP } }
            case .untilClose:
                if !buffer.isEmpty { try receive(.body(buffer)); buffer.removeAll(keepingCapacity: true) }; return
            case .complete:
                guard buffer.isEmpty else { throw CoreError.malformedHTTP }; complete = true; return
            }
        }
    }
    private mutating func takeLine(maximum: Int) throws -> String? {
        guard let range = buffer.range(of: Data("\r\n".utf8)) else {
            if buffer.count > maximum { throw CoreError.responseTooLarge }; return nil
        }
        guard range.lowerBound - buffer.startIndex <= maximum,
              let value = String(data: buffer.prefix(upTo: range.lowerBound), encoding: .utf8) else { throw CoreError.malformedHTTP }
        buffer.removeSubrange(..<range.upperBound); return value
    }
    public mutating func finish(receive: HTTPReceiver) throws {
        try feed(Data(), receive: receive)
        if case .untilClose = state { complete = true; state = .complete }
        guard complete else { throw CoreError.malformedHTTP }
    }
}
