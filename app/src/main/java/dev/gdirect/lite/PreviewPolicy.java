package dev.gdirect.lite;

import java.io.IOException;

final class PreviewPolicy {
    static boolean supports(String mime) {
        return mime!=null&&(mime.equals("application/pdf")||mime.equals("text/plain")||mime.equals("text/csv")||mime.equals("application/json")
            ||mime.equals("image/jpeg")||mime.equals("image/png")||mime.equals("image/webp")||mime.equals("image/gif")||mime.equals("image/bmp")||mime.equals("image/heic")||mime.equals("image/heif")||media(mime));
    }
    static boolean media(String mime){return mime.equals("video/mp4")||mime.equals("video/webm")||mime.equals("video/3gpp")||mime.equals("audio/mpeg")||mime.equals("audio/mp4")||mime.equals("audio/ogg")||mime.equals("audio/wav")||mime.equals("audio/flac");}
    static long limit(String mime){return mime.startsWith("text/")||mime.equals("application/json")?512*1024:media(mime)?256L*1024*1024:64L*1024*1024;}
    static int sample(int width,int height)throws IOException {
        if(width<1||height<1)throw new IOException("图片格式无效");
        int sample=1;
        while((long)Math.max(1,width/sample)*Math.max(1,height/sample)>2_000_000)sample*=2;
        return sample;
    }
    private PreviewPolicy(){}
}
