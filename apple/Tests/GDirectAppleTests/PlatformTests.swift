#if os(macOS)
import XCTest
import Foundation
import Security
@testable import GDirectApple
import GDirectCore

private final class TLSFixture {
    let directory:URL
    let process=Process()
    let port:UInt16
    let root:SecCertificate
    init(name:String)throws{
        directory=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at:directory,withIntermediateDirectories:false)
        try "[req]\ndistinguished_name=dn\n[dn]\nCN=\(name)\n[ext]\nsubjectAltName=DNS:\(name)\nbasicConstraints=critical,CA:TRUE\nkeyUsage=digitalSignature,keyEncipherment,keyCertSign\nextendedKeyUsage=serverAuth\n".write(to:directory.appendingPathComponent("cert.cnf"),atomically:true,encoding:.utf8)
        let command=Process();command.executableURL=URL(fileURLWithPath:"/usr/bin/openssl");command.currentDirectoryURL=directory
        command.arguments=["req","-x509","-newkey","rsa:2048","-nodes","-sha256","-days","1","-subj","/CN=\(name)","-config","cert.cnf","-extensions","ext","-keyout","key.pem","-out","cert.pem"]
        command.standardOutput=FileHandle.nullDevice;command.standardError=FileHandle.nullDevice
        try command.run();command.waitUntilExit();guard command.terminationStatus==0 else{throw CoreError.invalidRequest}
        root=try AppleSocketTransport.certificates(Data(contentsOf:directory.appendingPathComponent("cert.pem")))[0]
        let python="""
        import socket,ssl,pathlib
        context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        context.load_cert_chain('cert.pem','key.pem')
        server=socket.socket();server.bind(('127.0.0.1',0));server.listen(8)
        pathlib.Path('port').write_text(str(server.getsockname()[1]))
        while True:
            client,_=server.accept();client.settimeout(3)
            try:
                with context.wrap_socket(client,server_side=True) as stream:
                    request=b''
                    while b'\\r\\n\\r\\n' not in request and len(request)<32768:
                        chunk=stream.recv(4096)
                        if not chunk: break
                        request+=chunk
                    pathlib.Path('captured').write_bytes(request)
                    stream.sendall(b'HTTP/1.1 200 OK\\r\\nContent-Length: 7\\r\\nConnection: close\\r\\n\\r\\nfixture')
            except (OSError,ssl.SSLError):
                client.close()
        """
        try python.write(to:directory.appendingPathComponent("server.py"),atomically:true,encoding:.utf8)
        process.executableURL=URL(fileURLWithPath:"/usr/bin/python3");process.arguments=["server.py"];process.currentDirectoryURL=directory
        process.standardOutput=FileHandle.nullDevice;process.standardError=FileHandle.nullDevice;try process.run()
        let file=directory.appendingPathComponent("port"),until=Date().addingTimeInterval(5)
        while !FileManager.default.fileExists(atPath:file.path),Date()<until{Thread.sleep(forTimeInterval:0.02)}
        guard let number=try? String(contentsOf:file,encoding:.utf8),let port=UInt16(number) else{process.terminate();throw CoreError.timeout};self.port=port
    }
    var captured:String{(try? String(contentsOf:directory.appendingPathComponent("captured"),encoding:.utf8)) ?? ""}
    deinit{if process.isRunning{process.terminate();process.waitUntilExit()};try? FileManager.default.removeItem(at:directory)}
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
    func testNativeMD5MatchesPublishedEmptyAndABCValues()throws{
        let file=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString);defer{try? FileManager.default.removeItem(at:file)}
        try Data().write(to:file);XCTAssertEqual(try Digest.md5File(file,check:{}),"d41d8cd98f00b204e9800998ecf8427e")
        try Data("abc".utf8).write(to:file);XCTAssertEqual(try Digest.md5File(file,check:{}),"900150983cd24fb0d6963f7d28e17f72")
    }
}
#endif
