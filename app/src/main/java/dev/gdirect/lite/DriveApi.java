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
    private static final String FIELDS = "id,name,mimeType,size,md5Checksum,capabilities/canDownload";
    private final DriveTransport transport;

    DriveApi(DriveTransport transport) { this.transport = transport; }

    static final class FileEntry {
        final String id, name, mime, md5;
        final long size;
        final boolean folder, downloadable;
        FileEntry(JSONObject json) throws JSONException, IOException {
            id = requireId(json.getString("id"));
            name = json.optString("name", "未命名文件");
            mime = json.optString("mimeType", "application/octet-stream");
            folder = "application/vnd.google-apps.folder".equals(mime);
            String rawSize = json.optString("size", "");
            try { size = rawSize.isEmpty() ? -1 : Long.parseLong(rawSize); }
            catch (NumberFormatException error) { throw new IOException("Google 返回了无效的文件大小"); }
            if (size < -1) throw new IOException("Google 返回了无效的文件大小");
            md5 = json.optString("md5Checksum", "");
            if (!md5.isEmpty() && !md5.matches("[a-fA-F0-9]{32}")) throw new IOException("Google 返回了无效的文件校验值");
            JSONObject capabilities = json.optJSONObject("capabilities");
            downloadable = !mime.startsWith("application/vnd.google-apps.")
                && capabilities != null && capabilities.optBoolean("canDownload", false) && size >= 0;
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
        if (folder == null) folder = "root";
        requireId(folder);
        String query = "trashed = false and '" + folder + "' in parents";
        if (search != null && !search.trim().isEmpty()) {
            if (search.length() > 200) throw new IOException("搜索内容过长，请使用 200 字以内的关键词");
            query += " and name contains '" + search.trim().replace("\\", "\\\\").replace("'", "\\'") + "'";
        }
        String url = BASE + "?pageSize=50&spaces=drive&orderBy=folder,name&q=" + encode(query)
            + "&fields=" + encode("nextPageToken,files(" + FIELDS + ")");
        if (pageToken != null && !pageToken.isEmpty()) url += "&pageToken=" + encode(pageToken);
        return new URL(url);
    }

    Page list(String folder, String search, String pageToken) throws IOException {
        return new Page(transport.json(listUrl(folder, search, pageToken)));
    }

    FileEntry metadata(String id) throws IOException {
        try { return new FileEntry(new JSONObject(transport.json(new URL(BASE + "/" + requireId(id) + "?fields=" + encode(FIELDS))))); }
        catch (JSONException error) { throw new IOException("Google 返回的文件信息格式不正确"); }
    }

    URL mediaUrl(String id) throws IOException { return new URL(BASE + "/" + requireId(id) + "?alt=media"); }
    private static String encode(String text) throws IOException { return URLEncoder.encode(text, "UTF-8"); }
}
