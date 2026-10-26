package dev.gdirect.lite;

import static org.junit.Assert.*;
import java.io.IOException;
import org.junit.Test;

public final class DriveListingTest {
    static DriveApi.Page page(String ids,String token)throws IOException {
        StringBuilder json=new StringBuilder("{\"files\":[");
        for(String id:ids.split(",")){if(id.isEmpty())continue;if(json.charAt(json.length()-1)!='[')json.append(',');json.append("{\"id\":\"").append(id).append("\",\"name\":\"").append(id).append("\"}");}
        return new DriveApi.Page(json+"],\"nextPageToken\":\""+token+"\"}");
    }
    @Test public void failedRefreshRetainsFilesButFolderSearchAndAccountChangesClearThem()throws Exception {
        DriveListing listing=new DriveListing();listing.begin("account1/folder/search",false);listing.accept(page("a,b","more"));
        assertEquals("",listing.begin("account1/folder/search",false));
        // No successful response: previously loaded files and next token remain available.
        assertEquals(2,listing.files.size());assertEquals("more",listing.next());
        listing.begin("account1/folder/search-other",false);assertTrue(listing.files.isEmpty());assertEquals("",listing.next());
        listing.accept(page("private",""));listing.begin("account2/folder/search-other",false);assertTrue(listing.files.isEmpty());
    }
    @Test public void paginationDeduplicatesChangedFilesAndRefreshReplacesTheSnapshot()throws Exception {
        DriveListing listing=new DriveListing();listing.begin("folder",false);listing.accept(page("a,b","next"));
        assertEquals("next",listing.begin("folder",true));listing.accept(page("b,c",""));
        assertEquals(3,listing.files.size());assertEquals("c",listing.files.get(2).id);
        listing.begin("folder",false);listing.accept(page("new",""));assertEquals(1,listing.files.size());assertEquals("new",listing.files.get(0).id);
    }
    @Test public void repeatedPageTokensCannotLoopOrPartiallyOverwriteAValidList()throws Exception {
        DriveListing listing=new DriveListing();listing.begin("folder",false);listing.accept(page("a","page1"));
        listing.begin("folder",true);assertThrows(IOException.class,()->listing.accept(page("bad","page1")));
        assertEquals(1,listing.files.size());assertEquals("page1",listing.next());
        listing.begin("folder",true);listing.accept(page("b","page2"));listing.begin("folder",true);
        assertThrows(IOException.class,()->listing.accept(page("bad","page1")));assertEquals(2,listing.files.size());
    }
}
