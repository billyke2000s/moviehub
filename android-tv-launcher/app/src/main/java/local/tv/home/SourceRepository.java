package local.tv.home;

import org.json.*;
import java.util.*;

final class SourceRepository {
    enum Cache { CACHED, NOT_CACHED, UNKNOWN }
    static final class Source { final String title,uri;Cache cache=Cache.UNKNOWN;Source(String title,String uri){this.title=title;this.uri=uri;} }
    private final Secrets secrets;
    SourceRepository(Secrets secrets){this.secrets=secrets;}
    List<Source> torrentio(String imdb,int season,int episode) throws Exception{
        if(!imdb.matches("tt[0-9]+"))throw new IllegalArgumentException("IMDb ID unavailable");
        String base=secrets.get("torrentio");if(base.isEmpty())base="https://torrentio.strem.fun";
        base=Playlist.networkUrl(base,false).replaceAll("/manifest\\.json$","").replaceAll("/+$","");
        String type=season<0?"movie":"series",id=season<0?imdb:imdb+":"+season+":"+episode;
        List<String> providers=new ArrayList<>();providers.add(base);String saved=secrets.get("fallbacks");JSONArray fallback=saved.isEmpty()?new JSONArray(DefaultSourceProviders.backups()):new JSONArray(saved);for(int i=0;i<Math.min(5,fallback.length());i++){String endpoint=Playlist.networkUrl(fallback.getString(i),false).replaceAll("/manifest\\.json$","").replaceAll("/+$","");if(!providers.contains(endpoint))providers.add(endpoint);}
        List<Source> result=new ArrayList<>();Set<String> seen=new HashSet<>();Exception failure=null;
        for(String provider:providers){try{JSONArray streams=new JSONObject(Network.text(provider+"/stream/"+type+"/"+id+".json",null,false)).getJSONArray("streams");
        for(int i=0;i<streams.length()&&result.size()<200;i++){JSONObject stream=streams.getJSONObject(i);String hash=stream.optString("infoHash"),uri=stream.optString("url");
            if(hash.matches("[a-fA-F0-9]{40}"))uri="magnet:?xt=urn:btih:"+hash;
            else if(!uri.startsWith("magnet:?"))continue;
            if(!uri.matches(".*(?:urn:btih:|urn%3Abtih%3A)[a-fA-F0-9]{40}.*"))continue;
            if(seen.add(uri))result.add(new Source(stream.optString("name","Source")+"\n"+stream.optString("title",stream.optString("description","")),uri));
        }if(!result.isEmpty())return result;}catch(Exception e){failure=e;}}
        if(failure!=null&&result.isEmpty())throw new Exception("Source providers unavailable",failure);return result;
    }
    List<Source> check(List<Source> items) throws Exception{
        StringBuilder form=new StringBuilder();for(Source source:items){if(form.length()>0)form.append('&');form.append("items%5B%5D=").append(Network.q(source.uri));}
        if(items.isEmpty())return items;JSONObject reply=post("cache/check",form.toString());JSONArray statuses=reply.getJSONArray("response");if(statuses.length()!=items.size())throw new Exception("Incomplete cache response");
        for(int i=0;i<items.size();i++){Object state=statuses.get(i);items.get(i).cache=state instanceof Boolean?((Boolean)state?Cache.CACHED:Cache.NOT_CACHED):Cache.UNKNOWN;}return items;
    }
    JSONObject post(String endpoint,String body) throws Exception{JSONObject reply=new JSONObject(Network.post("https://www.premiumize.me/api/"+endpoint,secrets.get("premiumize"),body));if(!"success".equals(reply.optString("status")))throw new Exception("Premiumize request failed");return reply;}
    JSONArray direct(Source source) throws Exception{return post("transfer/directdl","src="+Network.q(source.uri)).getJSONArray("content");}
    String queue(Source source) throws Exception{return post("transfer/create","src="+Network.q(source.uri)).getString("id");}
}
