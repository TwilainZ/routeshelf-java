package dev.gdirect.lite.drive;

import dev.gdirect.lite.network.ReadRetry;
import dev.gdirect.lite.preview.PreviewPolicy;
import dev.gdirect.lite.transfer.TransferRules;
import dev.gdirect.lite.transfer.UploadTransfer;

import java.io.IOException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Independently implemented Google Drive REST v3 subset. No tokens appear in URLs. */
public final class DriveApi {
    public static final String READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly";
    public static final String MANAGE_SCOPE = "https://www.googleapis.com/auth/drive";
    private static final String BASE = "https://www.googleapis.com/drive/v3/files";
    private static final String FIELDS = "id,name,mimeType,size,md5Checksum,modifiedTime,starred,trashed,parents,resourceKey,driveId,shortcutDetails,capabilities/canDownload";
    public enum View { FOLDER, ALL, RECENT, STARRED, SHARED, TRASH, DRIVES }
    public enum Sort {
        NAME("folder,name"), NAME_DESC("folder,name desc"), MODIFIED("modifiedTime desc"), SIZE("quotaBytesUsed desc");
        final String order; Sort(String order) { this.order=order; }
    }
    private final DriveTransport transport;

    public DriveApi(DriveTransport transport) { this.transport = transport; }

    public static final class FileEntry {
        public final String id, name, mime, md5, modified;
        public final String exportMime, exportSuffix;
        public final String previewMime;
        public final List<String> parents;
        public final String resourceKey;
        public final String driveId,targetId,targetKey;
        public final boolean shortcut;
        public final long size;
        public final boolean folder, downloadable;
        public final boolean starred,trashed;
        public FileEntry(JSONObject json) throws JSONException, IOException {
            id = requireId(json.getString("id"));
            name = json.optString("name", "未命名文件");
            mime = json.optString("mimeType", "application/octet-stream");
            folder = "application/vnd.google-apps.folder".equals(mime);
            modified = json.optString("modifiedTime", "");
            starred=json.optBoolean("starred",false);trashed=json.optBoolean("trashed",false);
            resourceKey=json.optString("resourceKey","");
            driveId=json.optString("driveId","");if(!driveId.isEmpty())requireId(driveId);
            shortcut=mime.equals("application/vnd.google-apps.shortcut");JSONObject target=json.optJSONObject("shortcutDetails");
            targetId=shortcut&&target!=null?requireId(target.getString("targetId")):"";targetKey=target==null?"":target.optString("targetResourceKey","");
            List<String> parentIds=new ArrayList<>();JSONArray parentArray=json.optJSONArray("parents");
            if(parentArray!=null)for(int i=0;i<parentArray.length();i++)parentIds.add(requireId(parentArray.getString(i)));
            parents=Collections.unmodifiableList(parentIds);
            if (mime.equals("application/vnd.google-apps.spreadsheet")) {
                exportMime="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; exportSuffix=".xlsx";
            } else if (mime.equals("application/vnd.google-apps.document") || mime.equals("application/vnd.google-apps.presentation") || mime.equals("application/vnd.google-apps.drawing")) {
                exportMime="application/pdf"; exportSuffix=".pdf";
            } else { exportMime=""; exportSuffix=""; }
            previewMime=exportMime.isEmpty()?mime:"application/pdf";
            String rawSize = json.optString("size", "");
            try { size = rawSize.isEmpty() ? -1 : Long.parseLong(rawSize); }
            catch (NumberFormatException error) { throw new IOException("Google 返回了无效的文件大小"); }
            if (size < -1) throw new IOException("Google 返回了无效的文件大小");
            md5 = json.optString("md5Checksum", "");
            if (!md5.isEmpty() && !md5.matches("[a-fA-F0-9]{32}")) throw new IOException("Google 返回了无效的文件校验值");
            JSONObject capabilities = json.optJSONObject("capabilities");
            downloadable = capabilities != null && capabilities.optBoolean("canDownload", false)
                && !json.optBoolean("trashed", false)
                && ((!mime.startsWith("application/vnd.google-apps.") && size >= 0) || !exportMime.isEmpty());
        }
        public boolean previewable(){return downloadable&&PreviewPolicy.supports(previewMime)&&(!exportMime.isEmpty()||size<=PreviewPolicy.limit(previewMime));}
    }

    public static final class Page {
        public final List<FileEntry> files;
        public final String nextToken;
        public Page(String body) throws IOException {
            try {
                JSONObject root = new JSONObject(body);
                JSONArray array = root.optJSONArray("files");
                if (array == null) throw new JSONException("Missing files");
                List<FileEntry> result = new ArrayList<>();
                for (int i = 0; i < array.length(); i++) result.add(new FileEntry(array.getJSONObject(i)));
                files = Collections.unmodifiableList(result);
                nextToken = root.optString("nextPageToken", "");
            } catch (JSONException error) { throw new IOException("Google 返回的文件列表格式不正确"); }
        }
    }

    public static String requireId(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,200}")) throw new IOException("Google Drive 文件 ID 无效");
        return id;
    }

    public static URL listUrl(String folder, String search, String pageToken) throws IOException {
        return listUrl(folder,search,pageToken,View.FOLDER,Sort.NAME);
    }
    public static URL listUrl(String folder, String search, String pageToken,View view,Sort sort) throws IOException {
        if(view==View.DRIVES) {
            String query="";
            if(search!=null&&!search.trim().isEmpty()){if(search.length()>200)throw new IOException("搜索内容过长");query="&q="+encode("name contains '"+search.trim().replace("\\","\\\\").replace("'","\\'")+"'");}
            return new URL("https://www.googleapis.com/drive/v3/drives?pageSize=50&fields="+encode("nextPageToken,drives(id,name)")+query+(pageToken==null||pageToken.isEmpty()?"":"&pageToken="+encode(pageToken)));
        }
        if (folder == null) folder = "root";
        requireId(folder);
        String query = view == View.TRASH ? "trashed = true" : "trashed = false";
        if(view == View.FOLDER) query += " and '" + folder + "' in parents";
        if(view == View.STARRED) query += " and starred = true";
        if(view == View.SHARED) query += " and sharedWithMe = true";
        if (search != null && !search.trim().isEmpty()) {
            if (search.length() > 200) throw new IOException("搜索内容过长，请使用 200 字以内的关键词");
            query += " and name contains '" + search.trim().replace("\\", "\\\\").replace("'", "\\'") + "'";
        }
        String url = BASE + "?pageSize=50&spaces=drive&supportsAllDrives=true&includeItemsFromAllDrives=true&orderBy=" + encode(sort.order) + "&q=" + encode(query)
            + "&fields=" + encode("nextPageToken,files(" + FIELDS + ")");
        if (pageToken != null && !pageToken.isEmpty()) url += "&pageToken=" + encode(pageToken);
        return new URL(url);
    }

    public Page list(String folder, String search, String pageToken) throws IOException {
        return list(folder,search,pageToken,View.FOLDER,Sort.NAME);
    }
    public Page list(String folder,String search,String pageToken,View view,Sort sort) throws IOException {
        return list(folder,search,pageToken,view,sort,"");
    }
    public Page list(String folder,String search,String pageToken,View view,Sort sort,String driveId)throws IOException {
        URL url=listUrl(folder,search,pageToken,view,sort);
        if(view==View.DRIVES) {
            try {
                JSONObject response=object(transport.json(url));JSONArray drives=response.getJSONArray("drives"),files=new JSONArray();
                for(int i=0;i<drives.length();i++){JSONObject drive=drives.getJSONObject(i);files.put(new JSONObject().put("id",drive.getString("id")).put("driveId",drive.getString("id")).put("name",drive.getString("name")).put("mimeType","application/vnd.google-apps.folder"));}
                return new Page(new JSONObject().put("files",files).put("nextPageToken",response.optString("nextPageToken","")).toString());
            }catch(JSONException e){throw new IOException("共享云端硬盘列表格式不正确");}
        }
        if(!driveId.isEmpty())url=new URL(url+"&corpora=drive&driveId="+encode(requireId(driveId)));
        return remember(new Page(transport.json(url,folder)));
    }
    private Page remember(Page page)throws IOException{for(FileEntry file:page.files)transport.rememberKey(file.id,file.resourceKey);return page;}

    public URL exportUrl(FileEntry file) throws IOException {
        return exportUrl(file,file.exportMime);
    }
    public URL exportUrl(FileEntry file,String format)throws IOException {
        if(file.exportMime.isEmpty()) throw new IOException("此文件不支持导出");
        if(!format.equals(file.exportMime)&&!format.equals("application/pdf"))throw new IOException("不支持此导出格式");
        return new URL(BASE+"/"+requireId(file.id)+"/export?mimeType="+encode(format));
    }

    public static String requireName(String name)throws IOException {
        if(name==null||name.trim().isEmpty()||name.length()>255||name.indexOf('\0')>=0)throw new IOException("名称应为 1–255 个字符，不能包含空字符");
        return name;
    }
    private JSONObject object(String body)throws IOException {
        try{return new JSONObject(body);}catch(JSONException e){throw new IOException("操作响应无法解析，请刷新列表核对");}
    }
    private URL editUrl(String id)throws IOException{return new URL(BASE+"/"+requireId(id)+"?supportsAllDrives=true&fields="+encode(FIELDS));}
    public FileEntry createFolder(String parent,String name)throws IOException {
        try {
            JSONObject body=new JSONObject().put("name",requireName(name)).put("mimeType","application/vnd.google-apps.folder")
                .put("parents",new JSONArray().put(requireId(parent)));
            return new FileEntry(object(transport.mutate(new URL(BASE+"?supportsAllDrives=true&fields="+encode(FIELDS)),"POST",body.toString())));
        }catch(JSONException e){throw new IOException("文件夹响应无法解析，请刷新列表");}
    }
    public FileEntry rename(String id,String name)throws IOException {
        try{return new FileEntry(object(transport.mutate(editUrl(id),"PATCH",new JSONObject().put("name",requireName(name)).toString())));}
        catch(JSONException e){throw new IOException("重命名响应无法解析，请刷新列表");}
    }
    public FileEntry trash(String id,boolean trashed)throws IOException {
        try{return new FileEntry(object(transport.mutate(editUrl(id),"PATCH",new JSONObject().put("trashed",trashed).toString())));}
        catch(JSONException e){throw new IOException("回收站操作响应无法解析，请刷新列表");}
    }
    public void deletePermanently(String id)throws IOException {
        DriveTransport.Document document=transport.document(editUrl(id));
        try{if(!new FileEntry(object(document.body)).trashed)throw new IOException("仅可永久删除回收站内的文件");}
        catch(JSONException e){throw new IOException("无法核对文件状态，未执行删除");}
        if(TransferRules.strongEtag(document.etag)==null)throw new IOException("Google 未提供可靠文件版本，已停止永久删除；可在 Google Drive 中处理此文件");
        transport.mutate(new URL(BASE+"/"+requireId(id)+"?supportsAllDrives=true"),"DELETE","",document.etag);
    }
    public FileEntry star(String id,boolean starred)throws IOException {
        try{return new FileEntry(object(transport.mutate(editUrl(id),"PATCH",new JSONObject().put("starred",starred).toString())));}
        catch(JSONException e){throw new IOException("星标响应无法解析，请刷新列表");}
    }
    public Page folders(String parent,String token)throws IOException {
        String query="trashed = false and mimeType = 'application/vnd.google-apps.folder' and '"+requireId(parent)+"' in parents";
        URL url=new URL(BASE+"?supportsAllDrives=true&includeItemsFromAllDrives=true&pageSize=50&orderBy=name&q="+encode(query)+"&fields="+encode("nextPageToken,files("+FIELDS+")")+(token==null||token.isEmpty()?"":"&pageToken="+encode(token)));
        return remember(new Page(transport.json(url,parent)));
    }
    public FileEntry move(String id,String destination)throws IOException {
        FileEntry current=metadata(id),target=metadata(destination);
        if(!target.folder||target.trashed||target.id.equals(current.id))throw new IOException("请选择其他有效文件夹");
        if(current.parents.contains(target.id))return current;
        String remove=current.parents.isEmpty()?"":"&removeParents="+encode(String.join(",",current.parents));
        try{return new FileEntry(object(transport.mutate(new URL(editUrl(id)+"&addParents="+encode(target.id)+remove),"PATCH","{}")));}
        catch(JSONException e){throw new IOException("移动结果无法解析，请刷新列表核对");}
    }
    public static final class Permission {
        public final String id,type,role,name;public final boolean removable;
        public Permission(JSONObject json)throws JSONException,IOException {
            id=requireId(json.getString("id"));type=json.optString("type");role=json.optString("role");
            name=json.optString("emailAddress",json.optString("displayName",type.equals("anyone")?"拥有链接的任何人":type));
            boolean inherited=false;JSONArray details=json.optJSONArray("permissionDetails");
            if(details!=null)for(int i=0;i<details.length();i++)inherited|=details.getJSONObject(i).optBoolean("inherited",false);
            removable=!role.equals("owner")&&!role.equals("organizer")&&!inherited;
        }
    }
    public List<Permission> permissions(String id)throws IOException {
        try {
            List<Permission> result=new ArrayList<>();String token="";java.util.Set<String> seen=new java.util.HashSet<>();
            for(int page=0;page<20;page++) {
                String url=BASE+"/"+requireId(id)+"/permissions?supportsAllDrives=true&pageSize=100&fields="+encode("nextPageToken,permissions(id,type,role,emailAddress,displayName,permissionDetails/inherited)")+(token.isEmpty()?"":"&pageToken="+encode(token));
                JSONObject response=object(transport.json(new URL(url)));JSONArray items=response.getJSONArray("permissions");
                for(int i=0;i<items.length();i++)result.add(new Permission(items.getJSONObject(i)));
                token=response.optString("nextPageToken","");if(token.isEmpty())return result;
                if(!seen.add(token))throw new IOException("共享权限分页重复，请稍后重试");
            }
            throw new IOException("共享人员超过 2000 条，请在 Google Drive 中管理此文件");
        } catch(JSONException e){throw new IOException("无法读取共享权限");}
    }
    public static String permissionJson(String email,boolean writer,boolean anyone)throws IOException {
        if(!anyone&&(email==null||email.length()>254||!email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")))throw new IOException("请输入有效邮箱地址");
        try {
            JSONObject body=new JSONObject().put("type",anyone?"anyone":"user").put("role",writer&&!anyone?"writer":"reader");
            if(anyone)body.put("allowFileDiscovery",false);else body.put("emailAddress",email);
            return body.toString();
        }catch(JSONException e){throw new IOException("无法创建共享权限请求");}
    }
    public void share(String id,String email,boolean writer,boolean anyone)throws IOException {
        transport.mutate(new URL(BASE+"/"+requireId(id)+"/permissions?supportsAllDrives=true&sendNotificationEmail="+(!anyone)),"POST",permissionJson(email,writer,anyone));
    }
    public void removePermission(String id,String permission)throws IOException {
        transport.mutate(new URL(BASE+"/"+requireId(id)+"/permissions/"+requireId(permission)+"?supportsAllDrives=true"),"DELETE","");
    }
    public static String sharingLink(FileEntry file)throws IOException {
        String url="https://drive.google.com/open?id="+encode(requireId(file.id));
        if(!file.resourceKey.isEmpty()) {
            if(!file.resourceKey.matches("[A-Za-z0-9_-]{1,200}"))throw new IOException("共享资源密钥格式无效");
            url+="&resourcekey="+encode(file.resourceKey);
        }
        return url;
    }
    public FileEntry upload(java.io.File file,String parent,String name,String mime,ReadRetry.Check check,UploadTransfer.Progress progress)throws IOException {
        String metadata;
        try {metadata=new JSONObject().put("name",requireName(name)).put("mimeType",mime).put("parents",new JSONArray().put(requireId(parent))).toString();}
        catch(JSONException e){throw new IOException("上传元数据无法创建");}
        if(file.length()==0) {
            try{return new FileEntry(object(transport.mutate(new URL(BASE+"?supportsAllDrives=true&fields="+encode(FIELDS)),"POST",metadata)));}
            catch(JSONException e){throw new IOException("上传响应无法解析，请刷新核对");}
        }
        check.run();URL session=transport.startUpload(metadata,mime,file.length());
        String result=UploadTransfer.run(file,new UploadTransfer.Remote(){
            public UploadTransfer.Reply put(long start,byte[] bytes,long total)throws IOException{return transport.uploadChunk(session,mime,start,bytes,total);}
            public UploadTransfer.Reply query(long total)throws IOException{return transport.uploadChunk(session,mime,0,new byte[0],total);}
        },check,progress);
        try{return new FileEntry(object(result));}catch(JSONException e){throw new IOException("上传完成响应无法解析，请刷新文件列表核对");}
    }

    public FileEntry metadata(String id) throws IOException {
        try { FileEntry file=new FileEntry(new JSONObject(transport.json(new URL(BASE + "/" + requireId(id) + "?supportsAllDrives=true&fields=" + encode(FIELDS)))));transport.rememberKey(file.id,file.resourceKey);return file; }
        catch (JSONException error) { throw new IOException("Google 返回的文件信息格式不正确"); }
    }
    public FileEntry resolve(FileEntry file)throws IOException {
        java.util.Set<String> seen=new java.util.HashSet<>();
        for(int hops=0;hops<8;hops++) {
            if(!file.shortcut)return file;
            if(!seen.add(file.id)||file.targetId.isEmpty())throw new IOException("快捷方式目标无效或形成循环");
            transport.rememberKey(file.targetId,file.targetKey);file=metadata(file.targetId);
        }
        throw new IOException("快捷方式嵌套过多");
    }

    public URL mediaUrl(String id) throws IOException { return new URL(BASE + "/" + requireId(id) + "?alt=media&supportsAllDrives=true"); }
    private static String encode(String text) throws IOException { return URLEncoder.encode(text, "UTF-8"); }
}
