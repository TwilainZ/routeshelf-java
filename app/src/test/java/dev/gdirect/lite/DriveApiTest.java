package dev.gdirect.lite;

import static org.junit.Assert.*;
import java.io.IOException;
import java.net.URLDecoder;
import org.junit.Test;

public final class DriveApiTest {
    @Test public void libraryViewsHaveIndependentQueriesAndServerSideSorts() throws Exception {
        for(DriveApi.View view:DriveApi.View.values()) {
            String decoded=URLDecoder.decode(DriveApi.listUrl("root","sample","next",view,DriveApi.Sort.MODIFIED).toString(),"UTF-8");
            assertTrue(decoded.contains(view==DriveApi.View.TRASH?"trashed = true":"trashed = false"));
            assertEquals(view==DriveApi.View.FOLDER,decoded.contains("in parents"));
            assertEquals(view==DriveApi.View.STARRED,decoded.contains("starred = true"));
            assertEquals(view==DriveApi.View.SHARED,decoded.contains("sharedWithMe = true"));
            assertTrue(decoded.contains("orderBy=modifiedTime desc"));
            assertTrue(decoded.contains("name contains 'sample'"));
            assertTrue(decoded.contains("includeItemsFromAllDrives=true"));
        }
    }
    @Test public void documentsExportInSupportedFormatsAndRespectDownloadPermissions() throws Exception {
        DriveApi api=new DriveApi(null);
        for(String type:new String[]{"document","spreadsheet","presentation","drawing"}) {
            DriveApi.FileEntry file=new DriveApi.Page("{\"files\":[{\"id\":\"doc\",\"name\":\"Example\",\"mimeType\":\"application/vnd.google-apps."+type+"\",\"capabilities\":{\"canDownload\":true}}]}").files.get(0);
            assertTrue(file.downloadable); assertEquals(-1,file.size);
            assertEquals(type.equals("spreadsheet")?".xlsx":".pdf",file.exportSuffix);
            java.net.URL url=api.exportUrl(file); DriveTransport.requireApiUrl(url);
            assertEquals("www.googleapis.com",url.getHost()); assertEquals("/drive/v3/files/doc/export",url.getPath());
        }
        for(String flag:new String[]{"\"trashed\":true,\"capabilities\":{\"canDownload\":true}","\"capabilities\":{\"canDownload\":false}"}) {
            DriveApi.FileEntry file=new DriveApi.Page("{\"files\":[{\"id\":\"doc\",\"mimeType\":\"application/vnd.google-apps.document\","+flag+"}]}").files.get(0);
            assertFalse(file.downloadable);
        }
        assertThrows(IOException.class,()->DriveTransport.requireApiUrl(new java.net.URL("https://www.googleapis.com/drive/v3/files/doc/export/other")));
    }
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
        DriveApi.Page page = new DriveApi.Page("{\"files\":[{\"id\":\"doc\",\"mimeType\":\"application/vnd.google-apps.form\",\"size\":\"5\",\"capabilities\":{\"canDownload\":true}}]}");
        assertFalse(page.files.get(0).downloadable);
        for (String bad : new String[]{"{}", "not-json", "{\"files\":[{\"id\":\"../escape\"}]}", "{\"files\":[{\"id\":\"ok\",\"size\":\"-10\"}]}", "{\"files\":[{\"id\":\"ok\",\"md5Checksum\":\"bad\"}]}"})
            assertThrows(IOException.class, () -> new DriveApi.Page(bad));
    }
}
