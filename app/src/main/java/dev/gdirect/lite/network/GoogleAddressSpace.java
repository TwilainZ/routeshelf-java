package dev.gdirect.lite.network;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Separate IPv4/IPv6 interval sets for official goog.json minus cloud.json. */
public final class GoogleAddressSpace {
    private final List<long[]> intervals;
    private final List<BigInteger[]> ipv6;
    private GoogleAddressSpace(List<long[]> intervals){this(intervals,Collections.emptyList());}
    private GoogleAddressSpace(List<long[]> intervals,List<BigInteger[]> ipv6){this.intervals=intervals;this.ipv6=ipv6;}
    public static GoogleAddressSpace fromBootstrapPrefixes(List<String> prefixes)throws IOException {
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
    public static GoogleAddressSpace parse(String google,String cloud)throws IOException {
        List<long[]> all=prefixes(google),excluded=prefixes(cloud),result=new ArrayList<>();
        List<BigInteger[]> all6=ipv6Prefixes(google),excluded6=ipv6Prefixes(cloud);
        if(all.isEmpty()!=excluded.isEmpty()||all6.isEmpty()!=excluded6.isEmpty())
            throw new IOException("Google 与 Cloud 地址列表的地址族不完整");
        for(long[] range:all) {
            long cursor=range[0];
            for(long[] cut:excluded) {
                if(cut[1]<cursor)continue;if(cut[0]>range[1])break;
                if(cut[0]>cursor)result.add(new long[]{cursor,cut[0]-1});
                cursor=Math.max(cursor,cut[1]+1);if(cursor>range[1])break;
            }
            if(cursor<=range[1])result.add(new long[]{cursor,range[1]});
        }
        List<BigInteger[]> result6=new ArrayList<>();
        for(BigInteger[] range:all6) {
            BigInteger cursor=range[0];
            for(BigInteger[] cut:excluded6) {
                if(cut[1].compareTo(cursor)<0)continue;if(cut[0].compareTo(range[1])>0)break;
                if(cut[0].compareTo(cursor)>0)result6.add(new BigInteger[]{cursor,cut[0].subtract(BigInteger.ONE)});
                cursor=cursor.max(cut[1].add(BigInteger.ONE));if(cursor.compareTo(range[1])>0)break;
            }
            if(cursor.compareTo(range[1])<=0)result6.add(new BigInteger[]{cursor,range[1]});
        }
        if(result.isEmpty()&&result6.isEmpty())throw new IOException("Google 前端地址集合为空");
        return new GoogleAddressSpace(result,result6);
    }
    public static int prefixCount(String json)throws IOException {
        prefixes(json);ipv6Prefixes(json);
        try{JSONArray rows=new JSONObject(json).getJSONArray("prefixes");int count=0;for(int i=0;i<rows.length();i++)if(rows.getJSONObject(i).has("ipv4Prefix"))count++;return count;}
        catch(JSONException error){throw new IOException("Google 地址列表不正确");}
    }
    private static List<BigInteger[]> ipv6Prefixes(String json)throws IOException {
        try {
            JSONArray rows=new JSONObject(json).getJSONArray("prefixes");
            List<BigInteger[]> ranges=new ArrayList<>();
            for(int i=0;i<rows.length();i++) {
                JSONObject item=rows.getJSONObject(i);
                if(!item.has("ipv6Prefix"))continue;
                if(item.has("ipv4Prefix"))throw new IOException("地址项不能混合地址族");
                String[] parts=item.getString("ipv6Prefix").split("/",-1);
                if(parts.length!=2||!parts[1].matches("[0-9]{1,3}")||!IpLiteral.ipv6(parts[0]))
                    throw new IOException("无效 IPv6 前缀");
                int bits=Integer.parseInt(parts[1]);
                if(bits<16||bits>128||!IpLiteral.publicAddress(parts[0]))throw new IOException("IPv6 前缀范围无效");
                BigInteger address=new BigInteger(1,IpLiteral.bytes(parts[0]));
                BigInteger size=BigInteger.ONE.shiftLeft(128-bits);
                if(!address.mod(size).equals(BigInteger.ZERO))throw new IOException("IPv6 前缀未对齐");
                ranges.add(new BigInteger[]{address,address.add(size).subtract(BigInteger.ONE)});
            }
            ranges.sort((a,b)->a[0].compareTo(b[0]));
            List<BigInteger[]> merged=new ArrayList<>();
            for(BigInteger[] range:ranges) {
                if(merged.isEmpty()||range[0].compareTo(merged.get(merged.size()-1)[1].add(BigInteger.ONE))>0)merged.add(range);
                else {BigInteger[] last=merged.get(merged.size()-1);last[1]=last[1].max(range[1]);}
            }
            return merged;
        }catch(JSONException|IllegalArgumentException error){throw new IOException("IPv6 地址列表格式无效",error);}
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
    public boolean contains(String ip) {
        if(!DirectRoutes.automaticAddress(ip))return false;
        if(IpLiteral.ipv6(ip)) {
            BigInteger value=new BigInteger(1,IpLiteral.bytes(ip));
            int low=0,high=ipv6.size()-1;
            while(low<=high){int middle=(low+high)>>>1;BigInteger[] range=ipv6.get(middle);
                if(value.compareTo(range[0])<0)high=middle-1;else if(value.compareTo(range[1])>0)low=middle+1;else return true;}
            return false;
        }
        long value;try{value=number(ip);}catch(IOException error){return false;}
        int low=0,high=intervals.size()-1;
        while(low<=high){int middle=(low+high)>>>1;long[] range=intervals.get(middle);
            if(value<range[0])high=middle-1;else if(value>range[1])low=middle+1;else return true;}
        return false;
    }
    public List<String> sample(int count,Random random) {
        LinkedHashSet<String> result=new LinkedHashSet<>();
        if(intervals.isEmpty()||count<=0)return new ArrayList<>(result);
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
    public List<String> discoveryCandidates(List<String> observed,int count,Random random)throws IOException {
        return discoveryCandidates(observed,count,random,true);
    }
    public List<String> discoveryCandidates(List<String> observed,int count,Random random,boolean useIpv6)throws IOException {
        if(count<=0)return Collections.emptyList();
        List<String> v4Observed=new ArrayList<>(),v6Observed=new ArrayList<>();
        for(String ip:observed)if(contains(ip)) {
            if(IpLiteral.ipv6(ip)){if(useIpv6)v6Observed.add(IpLiteral.canonical(ip));}
            else v4Observed.add(ip);
        }
        LinkedHashSet<String> result=new LinkedHashSet<>();
        for(String ip:v4Observed){if(result.size()==count)break;result.add(ip);}
        List<String> anchors=new ArrayList<>(result);Collections.shuffle(anchors,random);
        for(int pass=0;pass<32&&result.size()<count/2;pass++)for(String anchor:anchors) {
            long base=number(anchor)&0xffffff00L;
            long address=base+random.nextInt(256);
            String candidate=((address>>>24)&255)+"."+((address>>>16)&255)+"."+((address>>>8)&255)+"."+(address&255);
            if(contains(candidate))result.add(candidate);
        }
        for(String ip:sample(count,random)){if(result.size()>=count)break;result.add(ip);}
        if(!useIpv6||v6Observed.isEmpty())return new ArrayList<>(result);
        // Derive sparse neighborhoods from our own DNS/history, not upstream regional tables.
        LinkedHashSet<String> v6Candidates=new LinkedHashSet<>(v6Observed);
        int v6Budget=Math.min(128,Math.max(v6Observed.size(),count/4));
        for(int pass=0;pass<256&&v6Candidates.size()<v6Budget;pass++)for(String anchor:v6Observed) {
            BigInteger value=new BigInteger(1,IpLiteral.bytes(anchor));
            int suffixBits=pass<32?8:16;
            BigInteger candidate=value.shiftRight(suffixBits).shiftLeft(suffixBits)
                .or(BigInteger.valueOf(random.nextInt(1<<suffixBits)));
            String ip=IpLiteral.format(candidate,16);
            if(contains(ip))v6Candidates.add(ip);
            if(v6Candidates.size()>=v6Budget)break;
        }
        List<String> mixed=new ArrayList<>(result);mixed.addAll(v6Candidates);
        List<String> ordered=IpLiteral.interleave(mixed);
        return new ArrayList<>(ordered.subList(0,Math.min(count,ordered.size())));
    }
    private static long number(String ip)throws IOException {
        if(!DirectRoutes.publicIpv4(ip))throw new IOException("不是公网 IPv4 地址");
        long result=0;for(String part:ip.split("\\."))result=(result<<8)|Integer.parseInt(part);return result;
    }
}
