package dev.gdirect.lite;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** IPv4 interval algebra for Google's published goog.json minus cloud.json. No upstream IP assets. */
final class GoogleAddressSpace {
    private final List<long[]> intervals;
    private GoogleAddressSpace(List<long[]> intervals){this.intervals=intervals;}
    static GoogleAddressSpace fromBootstrapPrefixes(List<String> prefixes)throws IOException {
        if(prefixes.isEmpty()||prefixes.size()>8)throw new IOException("内置网段数量无效");
        List<long[]> blocks=new ArrayList<>();
        for(String prefix:prefixes) {
            if(!prefix.endsWith("/24"))throw new IOException("内置范围只允许有限 /24 网段");
            long start=number(prefix.substring(0,prefix.length()-3));
            if((start&255)!=0)throw new IOException("内置网段未对齐");
            blocks.add(new long[]{start,start+255});
        }
        blocks.sort(Comparator.comparingLong(block->block[0]));
        for(int i=1;i<blocks.size();i++)if(blocks.get(i)[0]<=blocks.get(i-1)[1])throw new IOException("内置网段重复");
        return new GoogleAddressSpace(blocks);
    }
    static GoogleAddressSpace parse(String google,String cloud)throws IOException {
        List<long[]> all=prefixes(google),excluded=prefixes(cloud),result=new ArrayList<>();
        if(all.isEmpty()||excluded.isEmpty())throw new IOException("Google 地址列表缺少 IPv4 数据");
        for(long[] range:all) {
            long cursor=range[0];
            for(long[] cut:excluded) {
                if(cut[1]<cursor)continue;if(cut[0]>range[1])break;
                if(cut[0]>cursor)result.add(new long[]{cursor,cut[0]-1});
                cursor=Math.max(cursor,cut[1]+1);if(cursor>range[1])break;
            }
            if(cursor<=range[1])result.add(new long[]{cursor,range[1]});
        }
        if(result.isEmpty())throw new IOException("Google 前端地址集合为空");
        return new GoogleAddressSpace(result);
    }
    static int prefixCount(String json)throws IOException {
        prefixes(json);
        try{JSONArray rows=new JSONObject(json).getJSONArray("prefixes");int count=0;for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).has("ipv4Prefix"))count++;return count;}
        catch(JSONException error){throw new IOException("Google 地址列表不正确");}
    }
    private static List<long[]> prefixes(String json)throws IOException {
        if(json==null||json.length()>1024*1024)throw new IOException("Google 地址列表大小不正确");
        try {
            JSONObject root=new JSONObject(json);JSONArray prefixes=root.getJSONArray("prefixes");
            root.getString("creationTime");root.getString("syncToken");
            if(prefixes.length()>20000)throw new IOException("Google 地址列表过大");
            List<long[]> ranges=new ArrayList<>();
            for(int i=0;i<prefixes.length();i++) {
                JSONObject item=prefixes.getJSONObject(i);
                if(!item.has("ipv4Prefix")){if(!item.has("ipv6Prefix"))throw new IOException("Google 地址项缺少前缀");continue;}
                String[] parts=item.getString("ipv4Prefix").split("/",-1);
                if(parts.length!=2||!parts[1].matches("[0-9]{1,2}"))throw new IOException("无效 IPv4 前缀");
                long address=number(parts[0]);int bits=Integer.parseInt(parts[1]);
                if(bits<8||bits>32)throw new IOException("Google 地址前缀范围不正确");
                long size=1L<<(32-bits);
                if(address%size!=0)throw new IOException("Google 地址前缀未对齐");
                ranges.add(new long[]{address,address+size-1});
            }
            ranges.sort(Comparator.comparingLong(range->range[0]));
            List<long[]> merged=new ArrayList<>();
            for(long[] range:ranges) {
                if(merged.isEmpty()||range[0]>merged.get(merged.size()-1)[1]+1)merged.add(range);
                else merged.get(merged.size()-1)[1]=Math.max(range[1],merged.get(merged.size()-1)[1]);
            }
            return merged;
        }catch(JSONException|IllegalArgumentException error){throw new IOException("Google 地址列表格式不正确");}
    }
    boolean contains(String ip) {
        if(!DirectRoutes.automaticAddress(ip))return false;
        long value;try{value=number(ip);}catch(IOException error){return false;}
        int low=0,high=intervals.size()-1;
        while(low<=high){int middle=(low+high)>>>1;long[] range=intervals.get(middle);
            if(value<range[0])high=middle-1;else if(value>range[1])low=middle+1;else return true;}
        return false;
    }
    List<String> sample(int count,Random random) {
        LinkedHashSet<String> result=new LinkedHashSet<>();
        List<long[]> shuffled=new ArrayList<>(intervals);Collections.shuffle(shuffled,random);
        for(int i=0;i<count*16&&result.size()<count;i++) {
            long[] range=shuffled.get(i%shuffled.size());
            long address=range[0]+(long)(random.nextDouble()*(range[1]-range[0]+1));
            String ip=((address>>>24)&255)+"."+((address>>>16)&255)+"."+((address>>>8)&255)+"."+(address&255);
            if(contains(ip))result.add(ip);
        }
        return new ArrayList<>(result);
    }
    /** Prefer independently observed neighborhoods; no imported upstream seeds or host-offset tables. */
    List<String> discoveryCandidates(List<String> observed,int count,Random random)throws IOException {
        LinkedHashSet<String> result=new LinkedHashSet<>();
        for(String ip:observed)if(contains(ip))result.add(ip);
        List<String> anchors=new ArrayList<>(result);Collections.shuffle(anchors,random);
        for(int pass=0;pass<32&&result.size()<count/2;pass++)for(String anchor:anchors) {
            long base=number(anchor)&0xffffff00L;
            long address=base+random.nextInt(256);
            String candidate=((address>>>24)&255)+"."+((address>>>16)&255)+"."+((address>>>8)&255)+"."+(address&255);
            if(contains(candidate))result.add(candidate);
        }
        for(String ip:sample(count,random)){if(result.size()>=count)break;result.add(ip);}
        return new ArrayList<>(result);
    }
    private static long number(String ip)throws IOException {
        if(!DirectRoutes.publicIpv4(ip))throw new IOException("不是公网 IPv4 地址");
        long result=0;for(String part:ip.split("\\."))result=(result<<8)|Integer.parseInt(part);return result;
    }
}
