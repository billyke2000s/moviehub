package local.tv.home;

import org.json.*;
import java.util.*;

final class MediaRepository {
    final Secrets secrets;
    MediaRepository(Secrets secrets){this.secrets=secrets;}
    List<Playlist.Channel> live(boolean http) throws Exception{
        List<Playlist.Channel> rows=new ArrayList<>();String playlist=secrets.get("m3u");
        if(!playlist.isEmpty())rows.addAll(Playlist.parse(Network.text(playlist,null,http),playlist,http));
        String account=secrets.get("xtream");
        if(!account.isEmpty()){
            JSONObject a=new JSONObject(account);String server=Playlist.networkUrl(a.getString("server"),http).replaceAll("/+$","");
            String query="?username="+Network.q(a.getString("username"))+"&password="+Network.q(a.getString("password"));
            JSONArray categories=new JSONArray(Network.text(server+"/player_api.php"+query+"&action=get_live_categories",null,http));Map<String,String> groups=new HashMap<>();
            for(int i=0;i<categories.length();i++){JSONObject c=categories.getJSONObject(i);groups.put(c.optString("category_id"),c.optString("category_name"));}
            JSONArray streams=new JSONArray(Network.text(server+"/player_api.php"+query+"&action=get_live_streams",null,http));
            if(streams.length()>500000)throw new Exception("Provider returned an unusually large channel list");
            for(int i=0;i<streams.length();i++){JSONObject c=streams.getJSONObject(i);String id=c.getString("stream_id");if(!id.matches("[0-9]+"))continue;
                String format=a.optString("format","ts");if(!format.equals("m3u8"))format="ts";
                String url=server+"/live/"+Network.path(a.getString("username"))+"/"+Network.path(a.getString("password"))+"/"+id+"."+format;
                rows.add(new Playlist.Channel(c.optString("name","Channel"),url,groups.getOrDefault(c.optString("category_id"),""),c.optString("stream_icon"),c.optString("epg_channel_id")));
            }
        }return rows;
    }
    JSONArray featured(String type,int seed) throws Exception{
        String token=secrets.get("tmdb");if(token.isEmpty())return new JSONArray();
        String path=seed>0?type+"/"+seed+"/recommendations":"trending/all/week";
        JSONObject response=new JSONObject(Network.text(tmdbUrl(path+"?language=en-US",token),null,false));JSONArray result=response.getJSONArray("results");
        if(seed>0)for(int i=0;i<result.length();i++)result.getJSONObject(i).put("media_type",type);
        return result;
    }
    JSONObject premiumize(String path,String token) throws Exception{
        JSONObject result=new JSONObject(Network.text("https://www.premiumize.me/api/"+path,token,false));
        if(!"success".equals(result.optString("status")))throw new Exception("Premiumize request was not accepted");return result;
    }
    synchronized JSONArray library() throws Exception{String value=secrets.get("library");return new JSONArray(value.isEmpty()?"[]":value);}
    synchronized void add(String title,String url,String kind,boolean http) throws Exception{
        if(title.trim().isEmpty())throw new IllegalArgumentException("Enter a title");if(!kind.equals("Movies")&&!kind.equals("Series"))throw new IllegalArgumentException("Choose Movies or Series");
        String safe=Playlist.networkUrl(url,http);JSONArray items=library();String id=UUID.randomUUID().toString();items.put(new JSONObject().put("id",id).put("title",title.trim()).put("url",safe).put("kind",kind));secrets.put("library",items.toString());
    }
    synchronized void remove(String id) throws Exception{JSONArray old=library(),items=new JSONArray();for(int i=0;i<old.length();i++)if(!id.equals(old.getJSONObject(i).getString("id")))items.put(old.getJSONObject(i));secrets.put("library",items.toString());}
    synchronized void addPremiumize(String title,String fileId,String kind) throws Exception{
        if(fileId.isEmpty()||(!kind.equals("Movies")&&!kind.equals("Series")))throw new IllegalArgumentException();JSONArray items=library();
        items.put(new JSONObject().put("id",UUID.randomUUID().toString()).put("title",title).put("source","premiumize").put("sourceId",fileId).put("kind",kind));secrets.put("library",items.toString());
    }
    static String tmdbUrl(String path,String key) throws Exception{
        if(!key.matches("(?i)[0-9a-f]{32}"))throw new IllegalArgumentException("Enter the 32-character TMDB API key (v3), not the Read Access Token");
        return "https://api.themoviedb.org/3/"+path+(path.contains("?")?"&":"?")+"api_key="+Network.q(key);
    }
    JSONObject tmdb(String path) throws Exception{String key=secrets.get("tmdb");if(key.isEmpty())throw new Exception("Connect TMDB first");return new JSONObject(Network.text(tmdbUrl(path+(path.contains("?")?"&":"?")+"language=en-US",key),null,false));}
}
