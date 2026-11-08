#if os(macOS)
import XCTest
import Foundation
import Security
@testable import GDirectApple
import GDirectCore

private final class TLSFixture {
    let directory: URL
    let process: Process
    let port: UInt16
    let root: SecCertificate

    init(name: String, delay: Int = 0) throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false,
                                                attributes: [.posixPermissions: 0o700])
        let process = Process()
        var initialized = false
        defer {
            if !initialized {
                if process.isRunning { process.terminate() }
                try? FileManager.default.removeItem(at: directory)
            }
        }
        let configuration = """
        [req]
        distinguished_name=dn
        [dn]
        CN=\(name)
        [ext]
        subjectAltName=DNS:\(name)
        basicConstraints=critical,CA:TRUE
        keyUsage=digitalSignature,keyEncipherment,keyCertSign
        extendedKeyUsage=serverAuth
        """
        try configuration.write(to: directory.appendingPathComponent("cert.cnf"), atomically: true, encoding: .utf8)
        let python = """
        import pathlib, signal, socket, ssl, subprocess, sys, time

        # Bound the fixture's lifetime even if the Swift test process exits unexpectedly.
        signal.alarm(45)
        subprocess.run([
            '/usr/bin/openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-sha256',
            '-days', '1', '-subj', '/CN=' + sys.argv[1], '-config', 'cert.cnf',
            '-extensions', 'ext', '-keyout', 'key.pem', '-out', 'cert.pem'
        ], check=True, timeout=5, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain('cert.pem', 'key.pem')
        server = socket.socket()
        server.bind(('127.0.0.1', 0))
        server.listen(8)
        pending = pathlib.Path('port.tmp')
        pending.write_text(str(server.getsockname()[1]))
        pending.rename('port')
        while True:
            client, _ = server.accept()
            client.settimeout(3)
            try:
                with context.wrap_socket(client, server_side=True) as stream:
                    request = b''
                    while b'\\r\\n\\r\\n' not in request and len(request) < 32768:
                        chunk = stream.recv(4096)
                        if not chunk: break
                        request += chunk
                    pathlib.Path('captured').write_bytes(request)
                    time.sleep(\(delay))
                    stream.sendall(b'HTTP/1.1 200 OK\\r\\nContent-Length: 7\\r\\nConnection: close\\r\\n\\r\\nfixture')
            except OSError:
                client.close()
        """
        try python.write(to: directory.appendingPathComponent("server.py"), atomically: true, encoding: .utf8)
        process.executableURL = URL(fileURLWithPath: "/usr/bin/python3")
        process.arguments = ["server.py", name]
        process.currentDirectoryURL = directory
        process.standardOutput = FileHandle.nullDevice
        process.standardError = FileHandle.nullDevice
        try process.run()
        let portFile = directory.appendingPathComponent("port")
        let deadline = ProcessInfo.processInfo.systemUptime + 8
        while !FileManager.default.fileExists(atPath: portFile.path),
              process.isRunning, ProcessInfo.processInfo.systemUptime < deadline {
            Thread.sleep(forTimeInterval: 0.02)
        }
        guard let number = try? String(contentsOf: portFile, encoding: .utf8),
              let port = UInt16(number),
              let root = try AppleSocketTransport.certificates(
                Data(contentsOf: directory.appendingPathComponent("cert.pem"))
              ).first else { throw CoreError.timeout }
        self.directory = directory
        self.process = process
        self.port = port
        self.root = root
        initialized = true
    }

    var captured: String {
        (try? String(contentsOf: directory.appendingPathComponent("captured"), encoding: .utf8)) ?? ""
    }

    deinit {
        // Foundation can miss a rapid process-exit notification while synchronously waiting on
        // the XCTest executor. The fixture has its own lifetime limit, so teardown never waits.
        if process.isRunning { process.terminate() }
        try? FileManager.default.removeItem(at: directory)
    }
}
final class AppleTransportTests:XCTestCase {
    func testRealTLSIdentitiesPreserveLogicalHostAndValidateTheirCertificate()async throws{
        for mode in TLSIdentity.allCases {
            let fixture=try TLSFixture(name:mode == .googleFront ? "g.cn" : "www.googleapis.com")
            let transport=AppleSocketTransport(testRoute:Route(host:"www.googleapis.com",ip:"127.0.0.1",identity:mode),roots:[fixture.root],port:fixture.port)
            let request=try HTTPRequest(url:URL(string:"https://www.googleapis.com/drive/v3/files")!,headers:["Authorization":"Bearer LOOPBACK_FIXTURE"],deadline:4)
            let (head,body)=try await ResponseBuffer.read(request,using:transport)
            XCTAssertEqual(head.status,200);XCTAssertEqual(body,Data("fixture".utf8))
            XCTAssertTrue(fixture.captured.contains("Host: www.googleapis.com\r\n"));XCTAssertTrue(fixture.captured.contains("Authorization: Bearer LOOPBACK_FIXTURE\r\n"));XCTAssertFalse(fixture.captured.contains("Host: g.cn"))
        }
    }
    func testWrongCertificateNameRejectsBeforeAnyHTTPAuthorization()async throws{
        let fixture=try TLSFixture(name:"unrelated.invalid")
        let transport=AppleSocketTransport(testRoute:Route(host:"www.googleapis.com",ip:"127.0.0.1",identity:.normal),roots:[fixture.root],port:fixture.port)
        do{_ = try await ResponseBuffer.read(HTTPRequest(url:URL(string:"https://www.googleapis.com/drive/v3/files")!,headers:["Authorization":"Bearer LOOPBACK_FIXTURE"],deadline:3),using:transport);XCTFail("Hostname mismatch accepted")}catch{}
        XCTAssertTrue(fixture.captured.isEmpty)
    }
    func testUntrustedRootIsNotMergedWithOtherTrustSources()async throws{
        let fixture=try TLSFixture(name:"www.googleapis.com"),other=try TLSFixture(name:"www.googleapis.com")
        let transport=AppleSocketTransport(testRoute:Route(host:"www.googleapis.com",ip:"127.0.0.1",identity:.normal),roots:[other.root],port:fixture.port)
        do{_ = try await ResponseBuffer.read(HTTPRequest(url:URL(string:"https://www.googleapis.com/drive/v3/files")!,deadline:3),using:transport);XCTFail("Untrusted fixture accepted")}catch{}
        XCTAssertTrue(fixture.captured.isEmpty)
    }
    func testCancelledAccountGuardCannotSendHTTP()async throws{
        let fixture=try TLSFixture(name:"www.googleapis.com"),flag=CancellationFlag();flag.cancel()
        let transport=AppleSocketTransport(testRoute:Route(host:"www.googleapis.com",ip:"127.0.0.1",identity:.normal),roots:[fixture.root],port:fixture.port)
        do{_ = try await ResponseBuffer.read(HTTPRequest(url:URL(string:"https://www.googleapis.com/drive/v3/files")!,guardCurrent:{try flag.check()}),using:transport);XCTFail("Cancelled guard accepted")}catch{XCTAssertTrue(error is CancellationError)}
        XCTAssertTrue(fixture.captured.isEmpty)
    }
    func testCancellingAnInFlightTLSReadReturnsBeforeTheIdleTimeout() async throws {
        let fixture = try TLSFixture(name: "www.googleapis.com", delay: 10)
        let transport = AppleSocketTransport(
            testRoute: Route(host: "www.googleapis.com", ip: "127.0.0.1", identity: .normal),
            roots: [fixture.root], port: fixture.port
        )
        let request = try HTTPRequest(url: URL(string: "https://www.googleapis.com/drive/v3/files")!, deadline: 15)
        let task = Task { try await ResponseBuffer.read(request, using: transport) }
        defer { task.cancel() }
        for _ in 0..<100 where fixture.captured.isEmpty {
            try await Task.sleep(for: .milliseconds(20))
        }
        XCTAssertTrue(fixture.captured.contains("GET /drive/v3/files"))
        let cancelledAt = ContinuousClock.now
        task.cancel()
        do {
            _ = try await task.value
            XCTFail("Cancellation returned an HTTP response")
        } catch {
            XCTAssertTrue(error is CancellationError)
        }
        XCTAssertLessThan(ContinuousClock.now - cancelledAt, .seconds(3))
    }

    func testNativeMD5MatchesPublishedEmptyAndABCValues()throws{
        let file=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString);defer{try? FileManager.default.removeItem(at:file)}
        try Data().write(to:file);XCTAssertEqual(try Digest.md5File(file,check:{}),"d41d8cd98f00b204e9800998ecf8427e")
        try Data("abc".utf8).write(to:file);XCTAssertEqual(try Digest.md5File(file,check:{}),"900150983cd24fb0d6963f7d28e17f72")
    }
}
#endif
