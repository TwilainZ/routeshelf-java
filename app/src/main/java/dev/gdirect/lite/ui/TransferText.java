package dev.gdirect.lite.ui;

import java.util.Locale;

/** Current task progress; completed/failed tasks must never read as waiting or still downloading. */
public final class TransferText {
    public static int progress(long done,long total){
        return total<=0?0:(int)Math.min(1000,Math.max(0,(double)done/total*1000));
    }
    public static String amount(long done,long total,long speed,boolean active,boolean finished){
        if(!active)return (finished?"已完成 ":"已传输 ")+units(done);
        String size=total>0?units(done)+" / "+units(total):units(done);
        return size+" · "+units(speed)+"/s"+(total>0?" · "+progress(done,total)/10+"%":"");
    }
    private static String units(long bytes){
        double value=Math.max(0,bytes);String[] suffix={"B","KiB","MiB","GiB","TiB","PiB","EiB"};int index=0;
        while(value>=1024&&index<suffix.length-1){value/=1024;index++;}
        return String.format(Locale.ROOT,index==0?"%.0f %s":"%.1f %s",value,suffix[index]);
    }
    private TransferText(){}
}
