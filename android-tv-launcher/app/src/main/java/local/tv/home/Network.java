package local.tv.home;

import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

final class Network {
    static byte[] bytes(String url,String bearer,int max,boolean allowHttp) throws Exception{
        URI current=URI.create(Playlist.networkUrl(url,allowHttp));String authAuthority=current.getAuthority();
        for(int redirects=0;redirects<6;redirects++){
            HttpURLConnection connection=(HttpURLConnection)current.toURL().openConnection();
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(20000);
            if(bearer!=null&&!bearer.isEmpty()&&current.getAuthority().equalsIgnoreCase(authAuthority)&&"https".equalsIgnoreCase(current.getScheme()))connection.setRequestProperty("Authorization","Bearer "+bearer);
            try{
                int status=connection.getResponseCode();
                if(status>=300&&status<400){String location=connection.getHeaderField("Location");if(location==null)throw new IOException("Invalid redirect");URI next=current.resolve(location);if("https".equalsIgnoreCase(current.getScheme())&&!"https".equalsIgnoreCase(next.getScheme()))throw new IOException("Insecure redirect refused");current=URI.create(Playlist.networkUrl(next.toString(),allowHttp));continue;}
                if(status<200||status>=300)throw new IOException("Server returned "+status);
                try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){
                    byte[] buffer=new byte[8192];int n;while((n=input.read(buffer))!=-1){if(output.size()+n>max)throw new IOException("Response is too large");output.write(buffer,0,n);}return output.toByteArray();
                }
            }finally{connection.disconnect();}
        }throw new IOException("Too many redirects");
    }
    static String text(String url,String bearer,boolean allowHttp) throws Exception{return new String(bytes(url,bearer,8*1024*1024,allowHttp),StandardCharsets.UTF_8);}
    static String q(String s){try{return URLEncoder.encode(s,"UTF-8");}catch(UnsupportedEncodingException e){throw new AssertionError(e);}}
    static String path(String s){return q(s).replace("+","%20");}
    static String post(String url,String bearer,String form) throws Exception{
        String safe=Playlist.networkUrl(url,false);HttpURLConnection c=(HttpURLConnection)new URI(safe).toURL().openConnection();
        c.setInstanceFollowRedirects(false);c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/x-www-form-urlencoded; charset=UTF-8");
        if(bearer==null||bearer.isEmpty())throw new IOException("Connect an account first");c.setRequestProperty("Authorization","Bearer "+bearer);
        byte[] data=form.getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(data.length);
        try{try(OutputStream out=c.getOutputStream()){out.write(data);}int status=c.getResponseCode();if(status<200||status>=300)throw new IOException("Server returned "+status);
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1){if(out.size()+n>8*1024*1024)throw new IOException("Response too large");out.write(b,0,n);}return out.toString("UTF-8");}
        }finally{c.disconnect();}
    }
}
