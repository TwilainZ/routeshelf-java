package dev.gdirect.lite.drive;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** UI-thread listing transaction: failed refreshes retain the last successful same-context page. */
public final class DriveListing {
    public final List<DriveApi.FileEntry> files=new ArrayList<>();
    private String context="",next="",requested="";
    private boolean append;
    private final Set<String> pages=new HashSet<>();
    public String begin(String value,boolean loadMore) {
        if(!context.equals(value)){reset();context=value;}
        append=loadMore;requested=loadMore?next:"";
        return requested;
    }
    public void accept(DriveApi.Page page)throws IOException {
        if(!page.nextToken.isEmpty()&&(page.nextToken.equals(requested)||(append&&pages.contains(page.nextToken))))
            throw new IOException("Google 返回了重复分页，请刷新列表后重试");
        LinkedHashMap<String,DriveApi.FileEntry> unique=new LinkedHashMap<>();
        if(append)for(DriveApi.FileEntry file:files)unique.put(file.id,file);
        for(DriveApi.FileEntry file:page.files)unique.put(file.id,file);
        files.clear();files.addAll(unique.values());
        if(!append)pages.clear();
        if(!requested.isEmpty())pages.add(requested);
        next=page.nextToken;
    }
    public String next(){return next;}
    public void reset(){files.clear();pages.clear();context="";next="";requested="";append=false;}
}
