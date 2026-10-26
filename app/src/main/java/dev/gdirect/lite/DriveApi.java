package dev.gdirect.lite;

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
final class DriveApi {
    static final String READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly";
    private static final String BASE = "https://www.googleapis.com/drive/v3/files";
    private static final String FIELDS = "id,name,mimeType,size,md5Checksum,modifiedTime,starred,trashed,capabilities/canDownload";
    enum View { FOLDER, ALL, RECENT, STARRED, SHARED, TRASH }
    enum Sort {
        NAME("folder,name"), NAME_DESC("folder,name desc"), MODIFIED("modifiedTime desc"), SIZE("quotaBytesUsed desc");
        final String order; Sort(String order) { this.order=order; }
    }
    private final DriveTransport transport;

    DriveApi(DriveTransport transport) { this.transport = transport; }

    static final class FileEntry {
        final String id, name, mime, md5, modified;
        final String exportMime, exportSuffix;
        final long size;
        final boolean folder, downloadable;
        FileEntry(JSONObject json) throws JSONException, IOException {
            id = requireId(json.getString("id"));
            name = json.optString("name", "未命名文件");
            mime = json.optString("mimeType", "application/octet-stream");
            folder = "application/vnd.google-apps.folder".equals(mime);
            modified = json.optString("modifiedTime", "");
            if (mime.equals("application/vnd.google-apps.spreadsheet")) {
                exportMime="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"; exportSuffix=".xlsx";
            } else if (mime.equals("application/vnd.google-apps.document") || mime.equals("application/vnd.google-apps.presentation") || mime.equals("application/vnd.google-apps.drawing")) {
                exportMime="application/pdf"; exportSuffix=".pdf";
            } else { exportMime=""; exportSuffix=""; }
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
    }

    static final class Page {
        final List<FileEntry> files;
        final String nextToken;
        Page(String body) throws IOException {
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

    static String requireId(String id) throws IOException {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,200}")) throw new IOException("Google Drive 文件 ID 无效");
        return id;
    }

    static URL listUrl(String folder, String search, String pageToken) throws IOException {
        return listUrl(folder,search,pageToken,View.FOLDER,Sort.NAME);
    }
    static URL listUrl(String folder, String search, String pageToken,View view,Sort sort) throws IOException {
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

    Page list(String folder, String search, String pageToken) throws IOException {
        return new Page(transport.json(listUrl(folder, search, pageToken)));
    }
    Page list(String folder,String search,String pageToken,View view,Sort sort) throws IOException {
        return new Page(transport.json(listUrl(folder,search,pageToken,view,sort)));
    }

    URL exportUrl(FileEntry file) throws IOException {
        if(file.exportMime.isEmpty()) throw new IOException("此文件不支持导出");
        return new URL(BASE+"/"+requireId(file.id)+"/export?mimeType="+encode(file.exportMime));
    }

    FileEntry metadata(String id) throws IOException {
        try { return new FileEntry(new JSONObject(transport.json(new URL(BASE + "/" + requireId(id) + "?supportsAllDrives=true&fields=" + encode(FIELDS))))); }
        catch (JSONException error) { throw new IOException("Google 返回的文件信息格式不正确"); }
    }

    URL mediaUrl(String id) throws IOException { return new URL(BASE + "/" + requireId(id) + "?alt=media&supportsAllDrives=true"); }
    private static String encode(String text) throws IOException { return URLEncoder.encode(text, "UTF-8"); }
}
