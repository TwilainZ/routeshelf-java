import XCTest
import Foundation
@testable import GDirectCore

final class ProtocolTests:XCTestCase {
    func testChunkedAcrossEveryByteBoundaryAndTrailers()throws{
        var parser=HTTPDecoder();let output=ResponseBuffer()
        let wire="HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nETag: \"v1\"\r\n\r\n4\r\nWiki\r\n5;test=yes\r\npedia\r\n0\r\nX-End: yes\r\n\r\n"
        for byte in wire.utf8{try parser.feed(Data([byte]),receive:{try output.receive($0)})}
        try parser.finish(receive:{try output.receive($0)});XCTAssertTrue(parser.complete)
        XCTAssertEqual(String(data:output.data,encoding:.utf8),"Wikipedia");XCTAssertEqual(output.head?["etag"],"\"v1\"")
    }
    func testInterimResponseDoesNotPublishAnExtraHead()throws{
        var parser=HTTPDecoder();let buffer=ResponseBuffer()
        try parser.feed(Data("HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".utf8),receive:{try buffer.receive($0)})
        XCTAssertEqual(buffer.head?.status,200);XCTAssertEqual(buffer.data,Data("ok".utf8));XCTAssertTrue(parser.complete)
    }
    func testLengthSmugglingAndMalformedChunksAreRejected(){
        let wires=["HTTP/1.1 200 OK\r\nContent-Length: 1\r\nContent-Length: 1\r\n\r\nx",
            "HTTP/1.1 200 OK\r\nContent-Length: 1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n","HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip\r\n\r\n",
            "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nXX\r\n"]
        for wire in wires{var parser=HTTPDecoder();XCTAssertThrowsError(try parser.feed(Data(wire.utf8),receive:{_ in}))}
    }
    func testTruncationAndTrailingResponseBytesAreRejected()throws{
        var parser=HTTPDecoder();try parser.feed(Data("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nabc".utf8),receive:{_ in})
        XCTAssertThrowsError(try parser.finish(receive:{_ in}))
        var second=HTTPDecoder();XCTAssertThrowsError(try second.feed(Data("HTTP/1.1 200 OK\r\nContent-Length: 1\r\n\r\nxy".utf8),receive:{_ in}))
    }
    func testBoundedHeadersAndBufferedJSON(){
        var parser=HTTPDecoder();XCTAssertThrowsError(try parser.feed(Data(repeating:65,count:32769),receive:{_ in}))
        let output=ResponseBuffer(limit:2);XCTAssertNoThrow(try output.receive(.head(HTTPHead(status:200))))
        XCTAssertThrowsError(try output.receive(.body(Data("abc".utf8))))
    }
    func testUntilCloseAndNoContentCompleteCorrectly()throws{
        var parser=HTTPDecoder();let output=ResponseBuffer()
        try parser.feed(Data("HTTP/1.0 200 OK\r\n\r\nbody".utf8),receive:{try output.receive($0)})
        XCTAssertFalse(parser.complete);try parser.finish(receive:{try output.receive($0)});XCTAssertEqual(output.data,Data("body".utf8))
        var empty=HTTPDecoder();try empty.feed(Data("HTTP/1.1 204 No Content\r\n\r\n".utf8),receive:{_ in});XCTAssertTrue(empty.complete)
    }
}

final class SecurityTests:XCTestCase {
    func testStateMatchedAuthorizationRefusalEndsTheAttemptImmediately()throws{
        let attempt=try OAuthAttempt(port:49152)
        let denied=URL(string:"http://127.0.0.1:49152/callback?state=\(attempt.state)&error=access_denied")!
        XCTAssertEqual(try attempt.callback(from:denied),.cancelled)
        XCTAssertThrowsError(try attempt.code(from:denied)){XCTAssertTrue($0 is CancellationError)}
        XCTAssertThrowsError(try attempt.callback(from:URL(string:"http://127.0.0.1:49152/callback?state=wrong&error=access_denied")!))
    }
    func testInvalidDeadlinesAndControlHeadersDoNotReachATransport(){
        let url=URL(string:"https://www.googleapis.com/drive/v3/files")!
        for deadline in [-1.0,0,Double.nan,Double.infinity]{XCTAssertThrowsError(try HTTPRequest(url:url,deadline:deadline))}
        XCTAssertThrowsError(try HTTPRequest(url:url,headers:["X-Example":"bad\0value"]))
    }
    func testSHA256StandardVectors(){
        func hex(_ data:Data)->String{Digest.sha256(data).map{String(format:"%02x",$0)}.joined()}
        XCTAssertEqual(hex(Data()),"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        XCTAssertEqual(hex(Data("abc".utf8)),"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        XCTAssertEqual(hex(Data(repeating:97,count:1000000)),"cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0")
    }
    func testPKCEUsesRFC7636S256Vector()throws{
        let attempt=try OAuthAttempt(port:49152,state:String(repeating:"s",count:43),verifier:"dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        let url=attempt.authorizationURL(configuration:try OAuthConfiguration(clientID:"fixture.apps.googleusercontent.com"))
        let fields=URLComponents(url:url,resolvingAgainstBaseURL:false)!.queryItems!
        XCTAssertEqual(fields.first{$0.name=="code_challenge"}?.value,"E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
        XCTAssertEqual(fields.first{$0.name=="scope"}?.value,"https://www.googleapis.com/auth/drive.readonly")
        XCTAssertNil(fields.first{$0.name=="client_secret"})
    }
    func testCallbackValidatesExactStateOriginDuplicatesAndExpiry()throws{
        let now=Date(),attempt=try OAuthAttempt(port:49152,created:now)
        let valid="http://127.0.0.1:49152/callback?state=\(attempt.state)&code=fixture_code"
        XCTAssertEqual(try attempt.code(from:URL(string:valid)!,now:now),"fixture_code")
        for bad in [valid+"&state=other",valid+"&code=other",valid+"&error=access_denied",valid.replacingOccurrences(of:"127.0.0.1",with:"localhost"),valid.replacingOccurrences(of:":49152",with:":49153")]{
            XCTAssertThrowsError(try attempt.code(from:URL(string:bad)!,now:now))
        }
        XCTAssertThrowsError(try attempt.code(from:URL(string:valid)!,now:now.addingTimeInterval(301)))
    }
    func testImportRejectsWebClientsAndIgnoresUntrustedEndpoints()throws{
        XCTAssertThrowsError(try OAuthConfiguration.imported(Data("{\"web\":{\"client_id\":\"fixture.apps.googleusercontent.com\"}}".utf8)))
        XCTAssertThrowsError(try OAuthConfiguration.imported(Data("{\"installed\":{\"client_id\":\"fixture.apps.googleusercontent.com\"}}".utf8)))
        let data=Data("{\"installed\":{\"client_id\":\"fixture.apps.googleusercontent.com\",\"redirect_uris\":[\"http://localhost\"],\"token_uri\":\"https://attacker.invalid/token\"}}".utf8)
        let configuration=try OAuthConfiguration.imported(data)
        XCTAssertEqual(configuration.clientID,"fixture.apps.googleusercontent.com")
        let form=String(data:GoogleOAuth.form(["code":"a&refresh_token=secret", "client_id":configuration.clientID]),encoding:.utf8)!
        XCTAssertTrue(form.contains("a%26refresh_token%3Dsecret"))
    }
    func testCredentialHeadersCannotBeSentToContentHosts()throws{
        XCTAssertThrowsError(try HTTPRequest(url:URL(string:"https://drive.usercontent.google.com/download")!,headers:["Authorization":"Bearer fixture"]))
        XCTAssertThrowsError(try HTTPRequest(url:URL(string:"https://www.googleapis.com/drive/v3/files")!,headers:["X-Test":"ok\r\nInjected: true"]))
        XCTAssertThrowsError(try HTTPRequest(url:URL(string:"https://www.googleapis.com.evil.test/drive/v3/files")!))
        XCTAssertThrowsError(try HTTPRequest(url:URL(string:"http://www.googleapis.com/drive/v3/files")!))
    }
    func testRedirectOriginsAndPublicShareLinksAreStrict()throws{
        let api=URL(string:"https://www.googleapis.com/drive/v3/files/file?alt=media")!
        XCTAssertEqual(try URLPolicy.contentRedirect("https://abc.googleusercontent.com/file",from:api).host,"abc.googleusercontent.com")
        for target in ["http://abc.googleusercontent.com/file","https://abc.googleusercontent.com.evil.test/file","https://user@abc.googleusercontent.com/file","https://abc.googleusercontent.com:444/file"]{
            XCTAssertThrowsError(try URLPolicy.contentRedirect(target,from:api))
        }
        XCTAssertNoThrow(try DriveClient.publicMedia(link:"https://drive.google.com/file/d/fixture_id/view?usp=sharing"))
        XCTAssertThrowsError(try DriveClient.publicMedia(link:"https://drive.google.com.evil.test/file/d/id/view"))
        XCTAssertThrowsError(try DriveClient.publicMedia(link:"https://drive.google.com/open?id=one&id=two"))
    }
    func testDriveQueriesEscapeOnlyInsideNameLiteral()throws{
        let url=try DriveClient.listURL(scope:.folder("fixture_folder"),search:"a' or trashed = true\\")
        let query=URLComponents(url:url,resolvingAgainstBaseURL:false)!.queryItems!.first{$0.name=="q"}!.value!
        XCTAssertEqual(query,"trashed = false and 'fixture_folder' in parents and name contains 'a\\' or trashed = true\\\\'")
        XCTAssertThrowsError(try DriveClient.listURL(scope:.folder("bad/id")))
        XCTAssertThrowsError(try DriveClient.listURL(scope:.recent,search:String(repeating:"x",count:201)))
    }
    func testRangeRulesRequireValidBoundsAndStrongValidators(){
        XCTAssertEqual(ByteRange.parse("bytes 3-5/6")?.start,3)
        for value in ["bytes 5-3/6","bytes 0-6/6","bytes -1-5/6","bytes 0-5/*"]{XCTAssertNil(ByteRange.parse(value))}
        XCTAssertEqual(ByteRange.strongETag("\"v1\""),"\"v1\"");XCTAssertNil(ByteRange.strongETag("W/\"v1\""))
    }
    func testResourcesRemainAttributedAndPinned()throws{
        let roots=try CoreResources.data("gts-roots.pem")
        XCTAssertEqual(Digest.sha256(roots).map{String(format:"%02x",$0)}.joined(),"6ba8f2f19e161f295a52b49e627796d7dbbeab3c7e60a46b07529e98e18536d7")
        XCTAssertEqual(CoreResources.bootstrap.candidates.count,6)
        XCTAssertTrue(CoreResources.bootstrap.candidates.allSatisfy{CoreResources.bootstrap.ranges.contains($0)})
        XCTAssertTrue(String(data:try CoreResources.data("gts-roots.LICENSE"),encoding:.utf8)!.contains("Mozilla"))
    }
}

actor ScriptTransport:HTTPTransport {
    typealias Script = @Sendable (HTTPRequest,HTTPReceiver) throws -> Void
    private var scripts:[Script]
    private(set) var requests:[HTTPRequest]=[]
    init(_ scripts:[Script]){self.scripts=scripts}
    func execute(_ request:HTTPRequest,receive:@escaping HTTPReceiver)async throws{
        try request.guardCurrent();requests.append(request)
        guard !scripts.isEmpty else{throw CoreError.invalidRequest};let script=scripts.removeFirst();try script(request,receive)
    }
}
final class DownloadTests:XCTestCase {
    func testAuthenticatedHTMLFileRemainsDownloadableButPublicConfirmationDoesNot()async throws{
        let value=Data("{\"id\":\"fixture\",\"name\":\"fixture.html\",\"mimeType\":\"text/html\",\"size\":\"6\",\"capabilities\":{\"canDownload\":true}}".utf8)
        let file=try JSONDecoder().decode(DriveFile.self,from:value)
        let transport=ScriptTransport([{_,r in try r(.head(HTTPHead(status:200,headers:["Content-Type":"text/html","Content-Length":"6"])));try r(.body(Data("<html>".utf8)))}])
        let spec=try await DriveClient(transport:transport,credentials:FixtureCredentials()).media(file)
        XCTAssertFalse(spec.rejectHTML)
        let result=try await DownloadEngine(transport:transport).download(spec,directory:directory())
        XCTAssertEqual(try Data(contentsOf:result.file),Data("<html>".utf8))
    }
    private func directory()throws->URL{
        let url=FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at:url,withIntermediateDirectories:false);addTeardownBlock{try? FileManager.default.removeItem(at:url)};return url
    }
    private func spec(_ name:String="fixture.bin",size:Int64?=6,check:@escaping @Sendable ()throws->Void={}) -> DownloadSpec {
        DownloadSpec(url:URL(string:"https://www.googleapis.com/drive/v3/files/fixture?alt=media")!,filename:name,expectedSize:size,
            headers:["Authorization":"Bearer LOOPBACK_FIXTURE","X-Goog-Drive-Resource-Keys":"fixture/key"],check:check)
    }
    func testInterruptedStrongETagTransferResumesAtTheExactOffset()async throws{
        let transport=ScriptTransport([
            {_,receive in try receive(.head(HTTPHead(status:200,headers:["Content-Length":"6","ETag":"\"v1\""])));try receive(.body(Data("abc".utf8)));throw URLError(.networkConnectionLost)},
            {request,receive in XCTAssertEqual(request.headers["Range"],"bytes=3-");XCTAssertEqual(request.headers["If-Range"],"\"v1\"")
                try receive(.head(HTTPHead(status:206,headers:["Content-Range":"bytes 3-5/6","Content-Length":"3","ETag":"\"v1\""])));try receive(.body(Data("def".utf8)))}])
        let result=try await DownloadEngine(transport:transport).download(spec(),directory:directory())
        XCTAssertEqual(try Data(contentsOf:result.file),Data("abcdef".utf8));XCTAssertEqual(result.bytes,6)
    }
    func testIgnoredRangeRestartsRatherThanMixingVersions()async throws{
        let transport=ScriptTransport([
            {_,r in try r(.head(HTTPHead(status:200,headers:["Content-Length":"6","ETag":"\"v1\""])));try r(.body(Data("old".utf8)));throw URLError(.networkConnectionLost)},
            {_,r in try r(.head(HTTPHead(status:200,headers:["Content-Length":"6","ETag":"\"v2\""])));try r(.body(Data("NEWNEW".utf8)))}])
        let result=try await DownloadEngine(transport:transport).download(spec(),directory:directory())
        XCTAssertEqual(try Data(contentsOf:result.file),Data("NEWNEW".utf8))
    }
    func testWeakETagCannotResumeAndMismatchedRangeIsRejected()async throws{
        let weak=ScriptTransport([
            {_,r in try r(.head(HTTPHead(status:200,headers:["Content-Length":"6","ETag":"W/\"v1\""])));try r(.body(Data("abc".utf8)));throw URLError(.networkConnectionLost)},
            {request,r in XCTAssertNil(request.headers["Range"]);try r(.head(HTTPHead(status:200,headers:["Content-Length":"6"])));try r(.body(Data("abcdef".utf8)))}])
        let result=try await DownloadEngine(transport:weak).download(spec(),directory:directory());XCTAssertEqual(result.bytes,6)
        let bad=ScriptTransport([
            {_,r in try r(.head(HTTPHead(status:200,headers:["Content-Length":"6","ETag":"\"v1\""])));try r(.body(Data("abc".utf8)));throw URLError(.networkConnectionLost)},
            {_,r in try r(.head(HTTPHead(status:206,headers:["Content-Range":"bytes 2-5/6","ETag":"\"v1\""])))}])
        let output=try directory()
        do{_ = try await DownloadEngine(transport:bad).download(spec(),directory:output);XCTFail("Accepted wrong range")}
        catch{XCTAssertEqual(error as? CoreError,.integrity)}
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath:output.path),[])
    }
    func testContentRedirectDropsEveryCredentialAndResourceKey()async throws{
        let transport=ScriptTransport([
            {_,r in try r(.head(HTTPHead(status:302,headers:["Location":"https://fixture.googleusercontent.com/content"])))},
            {request,r in XCTAssertNil(request.headers["Authorization"]);XCTAssertNil(request.headers["X-Goog-Drive-Resource-Keys"])
                try r(.head(HTTPHead(status:200,headers:["Content-Length":"6"])));try r(.body(Data("abcdef".utf8)))}])
        let result=try await DownloadEngine(transport:transport).download(spec(),directory:directory());XCTAssertEqual(result.bytes,6)
    }
    func testCancellationRemovesPartialAndNeverPublishesFinalFile()async throws{
        let flag=CancellationFlag(),output=try directory()
        let transport=ScriptTransport([{_,r in try r(.head(HTTPHead(status:200,headers:["Content-Length":"6"])));try r(.body(Data("abc".utf8)));flag.cancel();try r(.body(Data("def".utf8)))}])
        do{_ = try await DownloadEngine(transport:transport).download(spec(check:{try flag.check()}),directory:output);XCTFail("Cancellation ignored")}
        catch{XCTAssertTrue(error is CancellationError)}
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath:output.path),[])
    }
    func testExistingFileAndSymlinkAreNeverOverwritten()async throws{
        let directory=try directory(),existing=directory.appendingPathComponent("fixture.bin"),external=directory.appendingPathComponent("external")
        try Data("keep".utf8).write(to:external);try FileManager.default.createSymbolicLink(at:existing,withDestinationURL:external)
        let transport=ScriptTransport([{_,r in try r(.head(HTTPHead(status:200,headers:["Content-Length":"6"])));try r(.body(Data("abcdef".utf8)))}])
        let result=try await DownloadEngine(transport:transport).download(spec(),directory:directory)
        XCTAssertEqual(result.file.lastPathComponent,"fixture (1).bin");XCTAssertEqual(try Data(contentsOf:external),Data("keep".utf8))
        XCTAssertEqual(SafeFiles.filename("../../file/name\n.txt"),"_.._file_name_.txt")
    }
    func testHTMLAndWrongSizeAreNotSavedAsDownloads()async throws{
        for headers in [["Content-Type":"text/html"],["Content-Length":"100"]]{
            let directory=try directory(),transport=ScriptTransport([{_,r in try r(.head(HTTPHead(status:200,headers:headers)))}])
            do{_ = try await DownloadEngine(transport:transport).download(spec(),directory:directory);XCTFail("Invalid content saved")}catch{}
            XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath:directory.path),[])
        }
    }
    #if canImport(CryptoKit)
    func testActualMD5MismatchPreventsPublication()async throws{
        let directory=try directory(),transport=ScriptTransport([{_,r in try r(.head(HTTPHead(status:200,headers:["Content-Length":"6"])));try r(.body(Data("abcdef".utf8)))}])
        let value=DownloadSpec(url:spec().url,filename:"fixture.bin",expectedSize:6,md5:String(repeating:"0",count:32))
        do{_ = try await DownloadEngine(transport:transport).download(value,directory:directory);XCTFail("Bad digest saved")}catch{XCTAssertEqual(error as? CoreError,.integrity)}
        XCTAssertEqual(try FileManager.default.contentsOfDirectory(atPath:directory.path),[])
    }
    #endif
}

actor ProbeCounter {
    var count=0
    func increment(){count+=1}
    func waitForFirst()async throws{
        let deadline=Date().addingTimeInterval(2)
        while count==0{guard Date()<deadline else{throw CoreError.timeout};try await Task.sleep(nanoseconds:1_000_000)}
    }
}
struct FixtureCredentials:Credentials {
    func credential()async throws->CredentialLease{CredentialLease(token:"LOOPBACK_FIXTURE",check:{})}
}
final class RoutingTests:XCTestCase {
    func testCancellingOneWaiterReturnsPromptlyAndPreservesSharedDiscovery()async throws{
        let selector=RouteSelector(),counter=ProbeCounter(),bootstrap=CoreResources.bootstrap
        let first=Task{try await selector.select(host:"www.googleapis.com",candidates:["192.179.50.100"],ranges:bootstrap.ranges,mode:.googleOnly){_ in await counter.increment();try await Task.sleep(nanoseconds:400_000_000)}}
        try await counter.waitForFirst();let started=Date();first.cancel()
        do{_ = try await first.value;XCTFail("Cancelled waiter did not exit")}catch{XCTAssertTrue(error is CancellationError)}
        XCTAssertLessThan(Date().timeIntervalSince(started),0.25)
        _ = try await selector.select(host:"www.googleapis.com",candidates:["192.179.50.100"],ranges:bootstrap.ranges,mode:.googleOnly){_ in XCTFail("Started a second discovery")}
        let count=await counter.count;XCTAssertEqual(count,1)
    }
    func testNetworkResetCannotPublishAnOldProbeResult()async throws{
        let selector=RouteSelector(),bootstrap=CoreResources.bootstrap,counter=ProbeCounter()
        let first=Task{try await selector.select(host:"www.googleapis.com",candidates:["192.179.50.100"],ranges:bootstrap.ranges,mode:.googleOnly){_ in await counter.increment();try? await Task.sleep(nanoseconds:300_000_000)}}
        try await counter.waitForFirst();await selector.reset()
        do{_ = try await first.value;XCTFail("Reset published stale route")}catch{XCTAssertTrue(error is CancellationError)}
        do{_ = try await selector.select(host:"www.googleapis.com",candidates:[],ranges:bootstrap.ranges,mode:.googleOnly){_ in XCTFail("No candidates")};XCTFail("Stale result cached")}catch{}
    }
    func testPublicRangesExcludePrivateFakeAndCloudAddresses()throws{
        let ranges=GoogleRanges(google:[IPv4Range("192.179.50.0/24")!,IPv4Range("8.0.0.0/8")!],cloud:[IPv4Range("8.8.8.0/24")!])
        XCTAssertTrue(ranges.contains("192.179.50.100"));XCTAssertFalse(ranges.contains("8.8.8.8"))
        for ip in ["127.0.0.1","10.0.0.1","198.18.0.1","192.0.2.1","192.179.050.1","256.1.1.1"]{XCTAssertFalse(IPv4Range.isPublic(ip))}
        XCTAssertThrowsError(try GoogleRanges.parse(Data("{\"prefixes\":[{\"ipv4Prefix\":\"8.0.0.0/8\"}]}".utf8)))
    }
    func testRouteCacheReusesOnlySuccessfulVerificationAndResetRevalidates()async throws{
        let selector=RouteSelector(),counter=ProbeCounter(),bootstrap=CoreResources.bootstrap
        let probe:RouteSelector.Probe={_ in await counter.increment()}
        let first=try await selector.select(host:"www.googleapis.com",candidates:["192.179.50.100"],ranges:bootstrap.ranges,mode:.googleOnly,probe:probe)
        let cached=try await selector.select(host:"www.googleapis.com",candidates:[],ranges:bootstrap.ranges,mode:.googleOnly,probe:{_ in XCTFail("Reprobed cached route")})
        XCTAssertEqual(first,cached);await selector.reset()
        _ = try await selector.select(host:"www.googleapis.com",candidates:["192.179.50.100"],ranges:bootstrap.ranges,mode:.googleOnly,probe:probe)
        let count=await counter.count;XCTAssertEqual(count,2)
    }
    func testGoogleOnlyRejectsRelayAndCancellationDoesNotTryOtherIdentities()async throws{
        let selector=RouteSelector(),counter=ProbeCounter(),bootstrap=CoreResources.bootstrap
        do{_ = try await selector.select(host:"www.googleapis.com",candidates:["183.56.143.147"],ranges:bootstrap.ranges,mode:.googleOnly,probe:{_ in XCTFail("Relay probed")});XCTFail("Relay accepted")}catch{}
        do{_ = try await selector.select(host:"www.googleapis.com",candidates:["192.179.50.100"],ranges:bootstrap.ranges,mode:.googleOnly,probe:{_ in await counter.increment();throw CancellationError()});XCTFail("Cancellation ignored")}catch{XCTAssertTrue(error is CancellationError)}
        let count=await counter.count;XCTAssertEqual(count,1)
    }
    func testExcludedIdentityRecoversOnSameCandidate()async throws{
        let selector=RouteSelector(),bootstrap=CoreResources.bootstrap
        let excluded=Route(host:"www.googleapis.com",ip:"192.179.50.100",identity:.googleFront)
        let route=try await selector.select(host:excluded.host,candidates:[excluded.ip],ranges:bootstrap.ranges,mode:.googleOnly,excluded:[excluded]){route in XCTAssertNotEqual(route,excluded)}
        XCTAssertEqual(route.identity,.normal)
    }
}
