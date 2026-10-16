package local.tv.home;

import java.net.URI;
import java.util.*;
import java.util.regex.*;

/** Provider playlist data is never executable input. */
public final class Playlist {
    public static final class Channel {
        public final String name, url, group, logo, epgId;
        public Channel(String name,String url,String group,String logo,String epgId){this.name=name;this.url=url;this.group=group;this.logo=logo;this.epgId=epgId;}
    }
    private static final Pattern ATTR=Pattern.compile("([\\w-]+)=\"([^\"]*)\"");
    public static String networkUrl(String value,boolean allowHttp){
        URI u=URI.create(value.trim());String scheme=u.getScheme();
        if(u.getHost()==null||u.getUserInfo()!=null||!("https".equalsIgnoreCase(scheme)||(allowHttp&&"http".equalsIgnoreCase(scheme))))throw new IllegalArgumentException("Use an HTTPS media URL");
        return u.toASCIIString();
    }
    public static List<Channel> parse(String text,String source,boolean allowHttp){
        if(text.length()>8*1024*1024)throw new IllegalArgumentException("Playlist is too large");
        URI base=URI.create(networkUrl(source,allowHttp));List<Channel> result=new ArrayList<>();String name="",group="",logo="",id="";
        for(String raw:text.replace("\uFEFF", "").split("\\r?\\n")){
            String line=raw.trim();if(line.isEmpty())continue;
            if(line.startsWith("#EXTINF:")){
                int comma=-1;boolean quoted=false;for(int i=0;i<line.length();i++){if(line.charAt(i)=='\"')quoted=!quoted;if(line.charAt(i)==','&&!quoted){comma=i;break;}}
                name=comma<0?"Channel":line.substring(comma+1).trim();group="";logo="";id="";
                Matcher m=ATTR.matcher(comma<0?line:line.substring(0,comma));while(m.find()){switch(m.group(1)){case "group-title":group=m.group(2);break;case "tvg-logo":logo=m.group(2);break;case "tvg-id":id=m.group(2);break;}}
            }else if(line.startsWith("#EXTGRP:")){group=line.substring(8).trim();}
            else if(!line.startsWith("#")){
                try{String url=networkUrl(base.resolve(line).toString(),allowHttp);result.add(new Channel(name.isEmpty()?"Channel "+(result.size()+1):name,url,group,logo,id));}catch(IllegalArgumentException ignored){}
                name="";group="";logo="";id="";
                
            }
        }return result;
    }
}
