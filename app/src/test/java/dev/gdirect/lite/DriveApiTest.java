package dev.gdirect.lite;

import static org.junit.Assert.*;
import java.io.IOException;
import java.net.URLDecoder;
import org.junit.Test;

public final class DriveApiTest {
    @Test public void queriesEscapeNamesAndEncodeOpaquePageTokens() throws Exception {
        String url = DriveApi.listUrl("root", "a' or trashed = true\\x", "p&token=secret").toString();
        assertFalse(url.contains("&token=secret"));
        String decoded = URLDecoder.decode(url, "UTF-8");
        assertTrue(decoded.contains("name contains 'a\\' or trashed = true\\\\x'"));
        assertTrue(decoded.contains("trashed = false and 'root' in parents"));
        assertThrows(IOException.class, () -> DriveApi.listUrl("root' or 'x", "", ""));
    }

    @Test public void parsesFoldersPermissionsSizesAndPagination() throws Exception {
        DriveApi.Page page = new DriveApi.Page("{\"nextPageToken\":\"next\",\"files\":["
            + "{\"id\":\"folder-1\",\"name\":\"Folder\",\"mimeType\":\"application/vnd.google-apps.folder\"},"
            + "{\"id\":\"file-1\",\"name\":\"私密.txt\",\"size\":\"3\",\"capabilities\":{\"canDownload\":true}}]}");
        assertEquals("next", page.nextToken);
        assertTrue(page.files.get(0).folder);
        assertFalse(page.files.get(0).downloadable);
        assertTrue(page.files.get(1).downloadable);
        assertEquals(3,page.files.get(1).size);
    }

    @Test public void unsupportedDocumentsAndMalformedResponsesFailClosed() throws Exception {
        DriveApi.Page page = new DriveApi.Page("{\"files\":[{\"id\":\"doc\",\"mimeType\":\"application/vnd.google-apps.document\",\"size\":\"5\",\"capabilities\":{\"canDownload\":true}}]}");
        assertFalse(page.files.get(0).downloadable);
        for (String bad : new String[]{"{}", "not-json", "{\"files\":[{\"id\":\"../escape\"}]}", "{\"files\":[{\"id\":\"ok\",\"size\":\"-10\"}]}", "{\"files\":[{\"id\":\"ok\",\"md5Checksum\":\"bad\"}]}"})
            assertThrows(IOException.class, () -> new DriveApi.Page(bad));
    }
}
