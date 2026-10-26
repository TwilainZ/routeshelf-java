package dev.gdirect.lite;

import static org.junit.Assert.*;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import org.junit.Test;

public final class DriveApiWriteTest {
    private static final class Server {
        final List<DriveTransportTest.Fake> calls=new ArrayList<>();final Deque<String> replies=new ArrayDeque<>();
        DriveApi api() throws IOException {
            DriveSession session=new DriveSession();session.connect("fixture",true);
            return new DriveApi(new DriveTransport(session.lease(),url->{
                DriveTransportTest.Fake c=new DriveTransportTest.Fake(url,200);c.body=replies.removeFirst().getBytes(StandardCharsets.UTF_8);calls.add(c);return c;
            },()->fail()));
        }
    }
    private static String file(String id,String extra){return "{\"id\":\""+id+"\",\"name\":\"file\","+extra+"}";}
    @Test public void folderAndMetadataWritesSerializeUserInputAsData()throws Exception {
        Server s=new Server();s.replies.add(file("folder","\"mimeType\":\"application/vnd.google-apps.folder\""));
        DriveApi api=s.api();api.createFolder("parent","a\"b");
        DriveTransportTest.Fake first=s.calls.get(0);JSONObject body=new JSONObject(first.written.toString("UTF-8"));
        assertEquals("POST",first.getRequestMethod());assertEquals("a\"b",body.getString("name"));assertEquals("parent",body.getJSONArray("parents").getString(0));assertTrue(first.closed);
        s.replies.add(file("folder","\"starred\":true"));api.star("folder",true);
        assertTrue(new JSONObject(s.calls.get(1).written.toString("UTF-8")).getBoolean("starred"));
        s.replies.add(file("folder","\"trashed\":true"));api.trash("folder",true);
        assertTrue(new JSONObject(s.calls.get(2).written.toString("UTF-8")).getBoolean("trashed"));
        assertThrows(IOException.class,()->api.rename("folder","  "));assertEquals(3,s.calls.size());
    }
    @Test public void moveUsesFreshParentsAndResolvesRootInsteadOfAppendingBlindly()throws Exception {
        Server s=new Server();DriveApi api=s.api();
        s.replies.add(file("source","\"parents\":[\"old-parent\"]"));
        s.replies.add(file("actual-root","\"mimeType\":\"application/vnd.google-apps.folder\""));
        s.replies.add(file("source","\"parents\":[\"actual-root\"]"));
        api.move("source","root");
        assertEquals(3,s.calls.size());String query=URLDecoder.decode(s.calls.get(2).getURL().getQuery(),"UTF-8");
        assertTrue(query.contains("addParents=actual-root"));assertTrue(query.contains("removeParents=old-parent"));
        assertEquals("PATCH",s.calls.get(2).getRequestMethod());
        s.replies.add(file("source","\"parents\":[\"actual-root\"]"));s.replies.add(file("source","\"mimeType\":\"application/vnd.google-apps.folder\""));
        assertThrows(IOException.class,()->api.move("source","source"));assertEquals(5,s.calls.size());
    }
    @Test public void sharingProtectsOwnersInheritedAccessAndResourceKeys()throws Exception {
        assertFalse(new DriveApi.Permission(new JSONObject("{\"id\":\"owner\",\"role\":\"owner\"}")).removable);
        assertFalse(new DriveApi.Permission(new JSONObject("{\"id\":\"inherited\",\"role\":\"reader\",\"permissionDetails\":[{\"inherited\":true}]}")).removable);
        JSONObject publicAccess=new JSONObject(DriveApi.permissionJson(null,true,true));
        assertEquals("reader",publicAccess.getString("role"));assertFalse(publicAccess.getBoolean("allowFileDiscovery"));assertFalse(publicAccess.has("emailAddress"));
        JSONObject person=new JSONObject(DriveApi.permissionJson("a@example.com",true,false));assertEquals("writer",person.getString("role"));
        assertThrows(IOException.class,()->DriveApi.permissionJson("a\nb@example.com",false,false));
        DriveApi.FileEntry shared=new DriveApi.FileEntry(new JSONObject(file("file","\"resourceKey\":\"key_123\"")));
        assertEquals("https://drive.google.com/open?id=file&resourcekey=key_123",DriveApi.sharingLink(shared));
    }
    @Test public void permanentDeleteRejectsFileOutsideTrashBeforeDeletion()throws Exception {
        Server s=new Server();DriveApi api=s.api();s.replies.add(file("source","\"trashed\":false"));
        assertThrows(IOException.class,()->api.deletePermanently("source"));assertEquals(1,s.calls.size());assertEquals("GET",s.calls.get(0).getRequestMethod());
    }
    @Test public void sharedDrivesBecomeNavigableRootsAndFileQueriesStayInTheirDrive()throws Exception {
        Server s=new Server();DriveApi api=s.api();s.replies.add("{\"drives\":[{\"id\":\"team\",\"name\":\"Team\"}],\"nextPageToken\":\"next\"}");
        DriveApi.Page roots=api.list("root","","",DriveApi.View.DRIVES,DriveApi.Sort.NAME);
        assertEquals("next",roots.nextToken);assertTrue(roots.files.get(0).folder);assertEquals("team",roots.files.get(0).driveId);
        s.replies.add("{\"files\":[]}");api.list("team","","",DriveApi.View.FOLDER,DriveApi.Sort.NAME,"team");
        assertTrue(s.calls.get(1).getURL().getQuery().contains("corpora=drive&driveId=team"));
    }
    @Test public void shortcutResourceKeysAreHeadersAndCyclesAreRejected()throws Exception {
        Server s=new Server();DriveApi api=s.api();
        DriveApi.FileEntry shortcut=new DriveApi.FileEntry(new JSONObject(file("shortcut","\"mimeType\":\"application/vnd.google-apps.shortcut\",\"shortcutDetails\":{\"targetId\":\"target\",\"targetResourceKey\":\"key_123\"}")));
        s.replies.add(file("target","\"mimeType\":\"application/vnd.google-apps.folder\""));
        assertTrue(api.resolve(shortcut).folder);assertEquals("target/key_123",s.calls.get(0).getRequestProperty("X-Goog-Drive-Resource-Keys"));
        assertFalse(s.calls.get(0).getURL().toString().contains("key_123"));
        s.replies.add(file("target","\"mimeType\":\"application/vnd.google-apps.shortcut\",\"shortcutDetails\":{\"targetId\":\"shortcut\"}"));
        s.replies.add(file("shortcut","\"mimeType\":\"application/vnd.google-apps.shortcut\",\"shortcutDetails\":{\"targetId\":\"target\"}"));
        assertThrows(IOException.class,()->api.resolve(shortcut));assertEquals(3,s.calls.size());
    }
    @Test public void conditionalDeletionRefusesConcurrentChangesAndMissingVersion()throws Exception {
        DriveSession session=new DriveSession();session.connect("fixture",true);List<DriveTransportTest.Fake> calls=new ArrayList<>();
        DriveApi api=new DriveApi(new DriveTransport(session.lease(),url->{
            DriveTransportTest.Fake c=new DriveTransportTest.Fake(url,calls.isEmpty()?200:412);
            c.body=file("file","\"trashed\":true").getBytes(StandardCharsets.UTF_8);c.etag="\"version-1\"";calls.add(c);return c;
        },()->{}));
        assertThrows(IOException.class,()->api.deletePermanently("file"));assertEquals(2,calls.size());
        assertEquals("DELETE",calls.get(1).getRequestMethod());assertEquals("\"version-1\"",calls.get(1).getRequestProperty("If-Match"));
        Server missing=new Server();missing.replies.add(file("file","\"trashed\":true"));
        assertThrows(IOException.class,()->missing.api().deletePermanently("file"));assertEquals(1,missing.calls.size());
    }
}
