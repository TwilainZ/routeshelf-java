#if os(macOS)
import SwiftUI
import AppKit
import UniformTypeIdentifiers
import GDirectCore
import GDirectApple

@main @MainActor struct GDirectMacApp: App {
    @StateObject private var model=MacModel()
    init(){
        if ProcessInfo.processInfo.arguments.contains("--smoke-check") {
            do {
                let roots=try CoreResources.data("gts-roots.pem")
                guard Digest.sha256(roots).map({String(format:"%02x",$0)}).joined()=="6ba8f2f19e161f295a52b49e627796d7dbbeab3c7e60a46b07529e98e18536d7",
                      CoreResources.bootstrap.candidates.count==6 else{throw CoreError.integrity}
                print("GDirect Lite bundle/resources verified");exit(0)
            }catch{fputs("Bundle resource verification failed\n",stderr);exit(1)}
        }
    }
    var body:some Scene {
        WindowGroup("GDirect Lite") { BrowserView(model:model).frame(minWidth:780,minHeight:520) }
        .commands { CommandGroup(after:.newItem) {
            Button("通过链接下载…"){model.showLink=true}.keyboardShortcut("l",modifiers:[.command,.shift])
            Button("刷新"){model.reload()}.keyboardShortcut("r").disabled(!model.connected || model.loading)
        } }
        Settings { MacSettings(model:model).frame(width:560) }
    }
}

enum SidebarPage:String,CaseIterable,Identifiable {
    case files="我的云盘",recent="最近修改",transfers="传输",connection="连接"
    var id:String{rawValue}
    var symbol:String {switch self{case .files:return "folder";case .recent:return "clock";case .transfers:return "arrow.down.circle";case .connection:return "network"}}
}
@MainActor final class MacModel:ObservableObject {
    @Published private(set) var page:SidebarPage? = .files
    @Published var files:[DriveFile]=[]
    @Published var selection:DriveFile.ID?
    @Published var query="",message="",connected=false,loading=false,signingIn=false,configured=false,checking=false
    @Published var nextPage:String?
    @Published var showLink=false,link="",linkError=""
    @Published var progress:TransferProgress?
    @Published var transferStatus="暂无传输任务",transferActive=false,savedFile:URL?
    @Published var mode:ConnectionMode = .googleOnly
    @Published var connectionStatus="尚未检测",routeDescription="",lastVerification:Date?
    @Published var relayConfirmation=false
    let transport=RoutedTransport()
    lazy var account=GoogleAccount(transport:transport)
    private let browserOAuth=BrowserOAuth()
    private var pathWatcher:NetworkWatcher?
    private var history:[(String,String)]=[]
    @Published private(set) var folder="root",folderName="我的云盘"
    private var generation=0
    private var transferID=UUID()
    private var listTask:Task<Void,Never>?,transferTask:Task<Void,Never>?,signInTask:Task<Void,Never>?
    var canGoBack:Bool{!history.isEmpty}
    var selectedFile:DriveFile?{files.first{$0.id==selection}}
    private var started=false
    let demo=ProcessInfo.processInfo.arguments.contains("--ui-smoke")
    func start(){
        guard !started else{return};started=true
        if demo {
            connected=true;folderName="界面自测 · 示例文件";message="示例数据，无网络请求。"
            let sample="""
            {"files":[{"id":"demo_folder","name":"项目资料","mimeType":"application/vnd.google-apps.folder"},{"id":"demo_pdf","name":"连接与下载说明.pdf","mimeType":"application/pdf","size":"3245678","capabilities":{"canDownload":true}},{"id":"demo_doc","name":"会议记录","mimeType":"application/vnd.google-apps.document","capabilities":{"canDownload":true}}]}
            """
            files=(try? JSONDecoder().decode(DrivePage.self,from:Data(sample.utf8)).files) ?? [];return
        }
        pathWatcher=NetworkWatcher(transport:transport)
        Task {
            do{connected=try await account.restore();configured=await account.configured();if connected{reload()}}
            catch{message="无法读取钥匙串中的连接信息，请重新导入配置并连接。"}
            await transport.updateRanges()
        }
    }
    func changePage(_ value:SidebarPage?){
        guard let value,page != value else{return};page=value
        generation+=1;listTask?.cancel();loading=false;selection=nil;query="";nextPage=nil;files=[]
        if value == .files{folder="root";folderName="我的云盘";history=[]}
        if value == .files || value == .recent{reload()}
    }
    func importOAuth(){
        guard !demo else{return}
        let panel=NSOpenPanel();panel.allowedContentTypes=[.json];panel.canChooseDirectories=false;panel.allowsMultipleSelection=false
        guard panel.runModal() == .OK,let url=panel.url else{return}
        Task {
            do {
                let input=try FileHandle(forReadingFrom:url);defer{try? input.close()};let data=try input.read(upToCount:65536) ?? Data();guard data.count<65536 else{throw CoreError.invalidOAuth}
                signInTask?.cancel();browserOAuth.cancel();transferTask?.cancel();generation+=1;listTask?.cancel()
                try await account.importConfiguration(data);configured=true;connected=false;files=[];message="OAuth 配置已存入本机钥匙串，可以连接 Google。"
            }catch{message=error.localizedDescription}
        }
    }
    func connect(){
        guard !demo else{return}
        guard !signingIn else{return};signingIn=true;message="请在系统浏览器完成 Google 授权。"
        signInTask=Task {
            defer{signingIn=false}
            do {
                let configuration=try await account.oauthConfiguration()
                let tokens=try await browserOAuth.signIn(configuration:configuration,transport:transport)
                try Task.checkCancellation();try await account.install(tokens,configuration:configuration)
                connected=true;message="";page = .files;folder="root";folderName="我的云盘";history=[];reload()
            }catch is CancellationError{message="已取消连接。"}
            catch{message=error.localizedDescription}
        }
    }
    func disconnect(){
        guard !demo else{return}
        signInTask?.cancel();browserOAuth.cancel();transferTask?.cancel();listTask?.cancel();generation+=1
        connected=false;loading=false;files=[];selection=nil;message="已断开，本机不再自动获取访问令牌。"
        Task{do{try await account.disconnect()}catch{message="清除钥匙串连接失败，请在钥匙串访问中检查应用记录。"}}
    }
    func reload(append:Bool=false){
        guard !demo,connected,page == .files || page == .recent else{return}
        generation+=1;let ticket=generation;listTask?.cancel();loading=true;message=""
        let scope:DriveScope=page == .recent ? .recent : .folder(folder),search=query,token=append ? nextPage : nil
        if !append{selection=nil}
        listTask=Task {
            defer{if ticket==generation{loading=false}}
            do {
                let result=try await DriveClient(transport:transport,credentials:account).list(scope:scope,search:search,page:token)
                try Task.checkCancellation();guard ticket==generation else{return}
                if append{let existing=Set(files.map(\.id));files+=result.files.filter{!existing.contains($0.id)}}else{files=result.files}
                nextPage=result.nextPageToken
                let status=await transport.snapshot();routeDescription=Self.describe(status);lastVerification=status.verifiedAt;connectionStatus="云盘服务已响应。"
            }catch is CancellationError{}
            catch{if ticket==generation{message=error.localizedDescription;if error as? CoreError == .authorization{connected=false}}}
        }
    }
    func openSelection(){guard let file=selectedFile else{return};if file.isFolder{enter(file)}else{download(file)}}
    func enter(_ file:DriveFile){
        guard file.isFolder else{return}
        if page != .files{page = .files;history=[];folder="root"}
        history.append((folder,folderName));folder=file.id;folderName=file.name;query="";reload()
    }
    func back(){guard let last=history.popLast() else{return};folder=last.0;folderName=last.1;query="";reload()}
    func download(_ file:DriveFile){
        guard !demo else{message="界面自测不执行网络请求。";return}
        guard !transferActive,file.canDownload,let directory=chooseDirectory() else{return}
        let transport=self.transport,account=self.account
        beginTransfer(directory:directory){try await DriveClient(transport:transport,credentials:account).media(file)}
    }
    func publicDownload(){
        guard !demo else{linkError="界面自测不执行网络请求。";return}
        linkError=""
        do {
            let spec=try DriveClient.publicMedia(link:link);guard !transferActive,let directory=chooseDirectory() else{return}
            showLink=false;beginTransfer(directory:directory){spec}
        }catch{linkError=error.localizedDescription}
    }
    private func chooseDirectory()->URL?{
        let panel=NSOpenPanel();panel.canChooseDirectories=true;panel.canChooseFiles=false;panel.canCreateDirectories=true
        panel.prompt="保存到此文件夹";panel.directoryURL=FileManager.default.urls(for:.downloadsDirectory,in:.userDomainMask).first
        return panel.runModal() == .OK ? panel.url : nil
    }
    private func beginTransfer(directory:URL,spec:@escaping @Sendable () async throws->DownloadSpec){
        transferID=UUID();let ticket=transferID
        transferActive=true;savedFile=nil;progress=nil;transferStatus="正在准备下载…";page = .transfers
        let transport=self.transport
        transferTask=Task {
            defer{transferActive=false}
            do {
                let request=try await spec()
                let gate=ProgressGate()
                let worker=Task.detached {
                    try await DownloadEngine(transport:transport).download(request,directory:directory){value in
                        if gate.shouldPublish(){Task{@MainActor in if self.transferActive && self.transferID==ticket{self.progress=value;self.transferStatus="正在下载 \(request.filename)"}}}
                    }
                }
                let result=try await withTaskCancellationHandler(operation:{try await worker.value},onCancel:{worker.cancel()})
                savedFile=result.file;transferStatus="下载完成：\(result.file.lastPathComponent)"
                progress=TransferProgress(received:result.bytes,total:result.bytes)
            }catch is CancellationError{transferStatus="下载已停止，临时文件已清理。"}
            catch{transferStatus=error.localizedDescription}
        }
    }
    func stopTransfer(){transferTask?.cancel()}
    func reveal(){if let savedFile{NSWorkspace.shared.activateFileViewerSelecting([savedFile])}}
    func openSaved(){if let savedFile{NSWorkspace.shared.open(savedFile)}}
    func changeMode(_ value:ConnectionMode){
        guard !transferActive else{message="请先停止传输，再切换连接方式。";return}
        mode=value;connectionStatus="连接方式已切换，等待重新检测。";routeDescription="";lastVerification=nil
        listTask?.cancel();generation+=1;loading=false;Task{await transport.configure(value)}
    }
    func checkConnection(){
        guard !demo else{connectionStatus="界面自测不执行网络请求。";return}
        guard !checking,!transferActive else{return};checking=true;connectionStatus="正在验证 Google 云盘线路…"
        Task {
            defer{checking=false}
            do{await transport.updateRanges();try await transport.checkAPI();let status=await transport.snapshot();routeDescription=Self.describe(status);lastVerification=status.verifiedAt;connectionStatus="云盘线路已验证。此结果不代表文件已下载。"}
            catch{connectionStatus=error.localizedDescription}
        }
    }
    static func describe(_ status:ConnectionSnapshot)->String{
        guard let route=status.route else{return status.mode == .system ? "系统网络" : "尚无已验证线路"}
        return "\(route.isRelay ? "已知第三方中转" : "Google 前端") · \(route.ip) · \(route.identity == .googleFront ? "g.cn TLS" : route.identity == .noSNI ? "无 SNI" : "标准 TLS")"
    }
}

private final class ProgressGate:@unchecked Sendable {
    private let lock=NSLock();private var last:TimeInterval=0
    func shouldPublish()->Bool{lock.lock();defer{lock.unlock()};let now=ProcessInfo.processInfo.systemUptime;guard now-last>=0.15 else{return false};last=now;return true}
}

struct BrowserView:View {
    @ObservedObject var model:MacModel
    var body:some View {
        NavigationSplitView {
            List(SidebarPage.allCases,selection:Binding(get:{model.page},set:{model.changePage($0)})){page in Label(page.rawValue,systemImage:page.symbol).tag(page)}
                .navigationTitle("GDirect Lite").navigationSplitViewColumnWidth(min:160,ideal:180,max:230)
        } detail: {
            VStack(alignment:.leading,spacing:0){
                if !model.message.isEmpty{Text(model.message).font(.callout).textSelection(.enabled).padding().frame(maxWidth:.infinity,alignment:.leading).background(.quaternary)}
                switch model.page ?? .files {
                case .files,.recent:files
                case .transfers:TransferView(model:model)
                case .connection:ConnectionView(model:model)
                }
            }
            .navigationTitle(model.page == .files ? model.folderName : model.page?.rawValue ?? "GDirect Lite")
            .toolbar{
                if model.page == .files || model.page == .recent {
                    ToolbarItemGroup {
                        Button(action:model.back){Label("上一级",systemImage:"chevron.left")}.disabled(!model.canGoBack || model.page != .files)
                        Button{model.reload() }label:{Label("刷新",systemImage:"arrow.clockwise")}.disabled(!model.connected || model.loading)
                        Button{if let file=model.selectedFile{model.download(file)}}label:{Label("下载",systemImage:"arrow.down")}.disabled(model.selectedFile?.canDownload != true || model.transferActive)
                    }
                }
                ToolbarItem{Button{model.showLink=true}label:{Label("通过链接下载",systemImage:"link")}.disabled(model.transferActive)}
            }
        }
        .task{model.start()}
        .sheet(isPresented:$model.showLink){VStack(alignment:.leading,spacing:16){
            Text("通过链接下载").font(.title2)
            TextField("公开的 Google Drive 文件链接",text:$model.link).textFieldStyle(.roundedBorder).frame(minWidth:430)
            if !model.linkError.isEmpty{Text(model.linkError).foregroundStyle(.red).font(.callout)}
            Text("需要 Google 确认或登录的公开链接可能无法直接下载。私人文件请先连接账户。 ").font(.caption).foregroundStyle(.secondary)
            HStack{Button("取消"){model.showLink=false}.keyboardShortcut(.cancelAction);Spacer();Button("下载",action:model.publicDownload).buttonStyle(.borderedProminent).keyboardShortcut(.defaultAction).disabled(model.link.isEmpty || model.transferActive)}
        }.padding(24)}
    }
    @ViewBuilder private var files:some View {
        if !model.connected {
            VStack(alignment:.leading,spacing:16){
                Text("连接你的 Google Drive").font(.title2)
                Text("默认只读。授权在系统浏览器完成，刷新凭据保存在本机钥匙串。").foregroundStyle(.secondary)
                HStack{Button("导入 Google OAuth 配置…",action:model.importOAuth);Button(model.signingIn ? "正在连接…" : "连接 Google",action:model.connect).buttonStyle(.borderedProminent).disabled(!model.configured || model.signingIn)}
                if model.signingIn{Button("取消连接"){model.disconnect()}}
                Text("首次使用请在现有 Cloud 项目创建桌面 OAuth 客户端并导入下载的 JSON；Android 客户端继续保留。").font(.callout).foregroundStyle(.secondary)
            }.padding(28).frame(maxWidth:.infinity,maxHeight:.infinity,alignment:.topLeading)
        } else {
            VStack(spacing:0){
                HStack{Image(systemName:"magnifyingglass").foregroundStyle(.secondary);TextField("在此范围搜索文件",text:$model.query).onSubmit{model.reload()};if model.loading{ProgressView().controlSize(.small)}}.padding()
                Table(model.files,selection:$model.selection){
                    TableColumn("名称"){file in Label(file.name,systemImage:file.isFolder ? "folder" : "doc").lineLimit(1)}
                    TableColumn("大小"){file in Text(file.byteCount.map{ByteCountFormatter.string(fromByteCount:$0,countStyle:.file)} ?? "—").foregroundStyle(.secondary)}.width(min:80,ideal:100,max:140)
                }.contextMenu(forSelectionType:DriveFile.ID.self){ids in
                    if let id=ids.first,let file=model.files.first(where:{$0.id==id}){
                        if file.isFolder{Button("打开"){model.enter(file)}}else{Button("下载"){model.download(file)}.disabled(!file.canDownload || model.transferActive)}
                    }
                } primaryAction:{ids in if let id=ids.first,let file=model.files.first(where:{$0.id==id}){if file.isFolder{model.enter(file)}else{model.download(file)}}}
                if model.files.isEmpty && !model.loading {Text(model.message.isEmpty ? "此范围没有文件。" : "未加载文件，可刷新重试。").foregroundStyle(.secondary).padding()}
                if model.nextPage != nil{Button("加载更多"){model.reload(append:true)}.disabled(model.loading).padding(8)}
            }
        }
    }
}

struct TransferView:View {
    @ObservedObject var model:MacModel
    var body:some View {
        VStack(alignment:.leading,spacing:18){
            Text(model.transferActive ? "当前传输" : "最近一次传输").font(.title2)
            Text(model.transferStatus).textSelection(.enabled)
            if model.transferActive {
                if let p=model.progress,let total=p.total,total>0{ProgressView(value:Double(p.received),total:Double(total))}
                else{ProgressView()}
                Button("停止下载",action:model.stopTransfer)
            }
            if let p=model.progress {Text(ByteCountFormatter.string(fromByteCount:p.received,countStyle:.file)+(p.total.map{" / "+ByteCountFormatter.string(fromByteCount:$0,countStyle:.file)} ?? "")).foregroundStyle(.secondary)}
            if model.savedFile != nil{HStack{Button("打开文件",action:model.openSaved).buttonStyle(.borderedProminent);Button("在 Finder 中显示",action:model.reveal)}}
            if model.savedFile==nil && !model.transferActive{Button("通过链接下载…"){model.showLink=true}}
            Spacer()
        }.padding(28).frame(maxWidth:.infinity,alignment:.leading)
    }
}
struct ConnectionView:View {
    @ObservedObject var model:MacModel
    var body:some View{
        Form {
            Section("连接状态"){
                LabeledContent("账户",value:model.connected ? "已连接 · 只读" : "未连接")
                Text(model.connectionStatus).textSelection(.enabled)
                if !model.routeDescription.isEmpty{Text(model.routeDescription).font(.callout).foregroundStyle(.secondary).textSelection(.enabled)}
                if let date=model.lastVerification{LabeledContent("最近验证"){Text(date,style:.relative)}}
                Button(model.checking ? "正在检测…" : "检测连接",action:model.checkConnection).disabled(model.checking || model.transferActive)
            }
            modePicker
            Section{Text("授权页面仍由系统浏览器访问。选择 Google 前端不代表流量绕过设备 VPN；线路验证也不代表文件下载完成。").font(.callout).foregroundStyle(.secondary)}
        }.formStyle(.grouped)
    }
    var modePicker:some View{
        Section("数据连接方式"){
            Picker("连接方式",selection:Binding(get:{model.mode},set:{value in if value == .relayAllowed{model.relayConfirmation=true}else{model.changeMode(value)}})){
                Text("仅 Google 前端").tag(ConnectionMode.googleOnly);Text("允许已知第三方中转").tag(ConnectionMode.relayAllowed);Text("系统网络").tag(ConnectionMode.system)
            }.disabled(model.transferActive || model.checking)
            Text("内置地址是待验证候选，会实时校验 Google 根证书和服务；不保证每个网络都可达。").font(.caption).foregroundStyle(.secondary)
        }.confirmationDialog("允许已知第三方中转？",isPresented:$model.relayConfirmation,titleVisibility:.visible){
            Button("允许"){model.changeMode(.relayAllowed)};Button("取消",role:.cancel){}
        }message:{Text("183.56.143.147 是第三方中转，不是 Google 自有节点。即使验证 Google 端到端 TLS，运营者仍可看到连接 IP、时间和流量大小。")}
    }
}
struct MacSettings:View {
    @ObservedObject var model:MacModel
    var body:some View{
        Form {
            Section("Google 账户"){
                Text(model.connected ? "已连接 · 只读访问" : "未连接")
                Button("导入桌面 OAuth JSON…",action:model.importOAuth).disabled(model.transferActive || model.signingIn)
                if model.connected{Button("断开连接",action:model.disconnect)}else{Button("连接 Google",action:model.connect).disabled(!model.configured || model.signingIn)}
                Text("配置和刷新凭据只保存在本机钥匙串，不上传给开发者。断开仅清除本机连接，不代替 Google 账号中的撤销授权。").font(.caption).foregroundStyle(.secondary)
            }
            Section("关于"){
                Text("GDirect Lite · macOS 0.1.0 开发预览")
                Text("独立 Swift 核心与 Apple 网络适配。当前提供只读浏览、下载和文档导出，尚无上传、队列及离线同步。").foregroundStyle(.secondary)
                Link("源码与许可",destination:URL(string:"https://github.com/qingzhuernest/gdirect-lite-android/tree/main/apple")!)
            }
        }.formStyle(.grouped).padding()
    }
}
#endif
