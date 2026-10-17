package local.tv.home;

import java.time.LocalDate;
import java.util.*;

/** TMDB discovery routes shared by Movies and Series. No account or playback state. */
final class TmdbCategories {
    static final class Entry {
        final String name,path;
        Entry(String name,String path){this.name=name;this.path=path;}
    }
    static String type(String type){if(!type.equals("movie")&&!type.equals("tv"))throw new IllegalArgumentException("Unknown catalogue");return type;}
    static String discover(String type,String query){return "discover/"+type(type)+"?include_adult=false&"+query;}
    static Entry genre(String type,int id,String name){if(id<=0||name.trim().isEmpty())throw new IllegalArgumentException("Invalid genre");return new Entry(name,discover(type,"with_genres="+id));}
    static List<Entry> genres(String type){
        type(type);List<Entry> rows=new ArrayList<>();
        String[][] definitions=type.equals("movie")?new String[][]{
            {"28","Action"},{"12","Adventure"},{"16","Animation"},{"35","Comedy"},{"80","Crime"},{"99","Documentary"},{"18","Drama"},{"10751","Family"},{"14","Fantasy"},{"36","History"},{"27","Horror"},{"10402","Music"},{"9648","Mystery"},{"10749","Romance"},{"878","Science Fiction"},{"10770","TV Movie"},{"53","Thriller"},{"10752","War"},{"37","Western"}
        }:new String[][]{
            {"10759","Action & Adventure"},{"16","Animation"},{"35","Comedy"},{"80","Crime"},{"99","Documentary"},{"18","Drama"},{"10751","Family"},{"10762","Kids"},{"9648","Mystery"},{"10763","News"},{"10764","Reality"},{"10765","Sci-Fi & Fantasy"},{"10766","Soap"},{"10767","Talk"},{"10768","War & Politics"},{"37","Western"}
        };
        for(String[] definition:definitions)rows.add(genre(type,Integer.parseInt(definition[0]),definition[1]));return rows;
    }
    static List<Entry> lists(String type){
        type(type);return Arrays.asList(new Entry("Trending today","trending/"+type+"/day"),new Entry("Trending this week","trending/"+type+"/week"),new Entry("Popular",type+"/popular"),new Entry("Top rated",type+"/top_rated"),new Entry(type.equals("movie")?"Now playing":"Airing today",type+(type.equals("movie")?"/now_playing":"/airing_today")),new Entry(type.equals("movie")?"Upcoming":"On the air",type+(type.equals("movie")?"/upcoming":"/on_the_air")));
    }
    static List<Entry> groups(String type){
        type(type);List<Entry> rows=new ArrayList<>();
        for(String[] group:new String[][]{{"Years & decades",":years"},{"Languages",":languages"},{"Countries",":countries"},{"Streaming services",":services"},{"Ratings",":ratings"},{"Runtime",":runtime"},{"Sort order",":sort"},{"Keywords & themes",":keywords"},{"Studios",":companies"},{"People",":people"}})rows.add(new Entry(group[0],group[1]));
        if(type.equals("movie")){rows.add(new Entry("Collections",":collections"));rows.add(new Entry("Release types",":releases"));}else{rows.add(new Entry("Series types",":types"));rows.add(new Entry("Series status",":status"));}return rows;
    }
    static List<Entry> filters(String type,String section,String country,LocalDate today){
        type(type);List<Entry> rows=new ArrayList<>();String date=type.equals("movie")?"primary_release_date":"first_air_date";
        switch(section){
            case ":years":
                rows.add(year(type,today.getYear()));for(int y=today.getYear()-1;y>=today.getYear()-24;y--)rows.add(year(type,y));
                for(int decade=today.getYear()/10*10;decade>=1900;decade-=10)rows.add(new Entry(decade+"s",discover(type,date+".gte="+decade+"-01-01&"+date+".lte="+(decade+9)+"-12-31")));break;
            case ":ratings":
                for(int rating=9;rating>=5;rating--)rows.add(new Entry(rating+"+ rated",discover(type,"vote_average.gte="+rating+"&vote_count.gte=100&sort_by=vote_average.desc")));
                rows.add(new Entry("Most voted",discover(type,"sort_by=vote_count.desc")));break;
            case ":runtime":
                for(String[] range:new String[][]{{"Under 30 minutes","with_runtime.gte=1&with_runtime.lte=29"},{"30–60 minutes","with_runtime.gte=30&with_runtime.lte=60"},{"60–90 minutes","with_runtime.gte=60&with_runtime.lte=90"},{"90–120 minutes","with_runtime.gte=90&with_runtime.lte=120"},{"Over 2 hours","with_runtime.gte=121"}})rows.add(new Entry(range[0],discover(type,range[1])));break;
            case ":sort":
                String title=type.equals("movie")?"title":"name",original=type.equals("movie")?"original_title":"original_name";
                for(String[] sort:new String[][]{{"Most popular","popularity.desc"},{"Least popular","popularity.asc"},{"Highest rated","vote_average.desc"},{"Lowest rated","vote_average.asc"},{"Most voted","vote_count.desc"},{"Least voted","vote_count.asc"},{"Newest releases",date+".desc"},{"Oldest releases",date+".asc"},{"Title: A to Z",title+".asc"},{"Title: Z to A",title+".desc"},{"Original: A to Z",original+".asc"},{"Original: Z to A",original+".desc"}})rows.add(new Entry(sort[0],discover(type,"vote_count.gte=10&"+(sort[1].startsWith(date)?date+".lte="+today+"&":"")+"sort_by="+sort[1])));
                if(type.equals("movie")){rows.add(new Entry("Highest grossing",discover(type,"sort_by=revenue.desc")));rows.add(new Entry("Revenue: low to high",discover(type,"vote_count.gte=10&sort_by=revenue.asc")));}break;
            case ":types":
                if(type.equals("tv")){String[] names={"Documentary series","News programmes","Miniseries","Reality shows","Scripted series","Shows","Video series"};for(int i=0;i<names.length;i++)rows.add(new Entry(names[i],discover(type,"with_type="+i)));}break;
            case ":status":
                if(type.equals("tv")){String[] names={"Returning series","Planned","In production","Ended","Cancelled","Pilot"};for(int i=0;i<names.length;i++)rows.add(new Entry(names[i],discover(type,"with_status="+i)));}break;
            case ":releases":
                if(type.equals("movie")){String[] names={"Premieres","Limited theatrical","Theatrical releases","Digital releases","Physical releases","TV releases"};for(int i=0;i<names.length;i++)rows.add(new Entry(names[i],discover(type,"region="+region(country)+"&with_release_type="+(i+1))));}break;
            case ":availability":
                String[] names={"Subscription","Free","With adverts","Rent","Buy"},values={"flatrate","free","ads","rent","buy"};for(int i=0;i<names.length;i++)rows.add(new Entry(names[i],discover(type,"watch_region="+region(country)+"&with_watch_monetization_types="+values[i])));break;
            default:throw new IllegalArgumentException("Unknown category group");
        }
        return rows;
    }
    static Entry year(String type,int year){if(year<1874||year>9999)throw new IllegalArgumentException("Enter a year from 1874 to 9999");return new Entry(String.valueOf(year),discover(type,(type.equals("movie")?"primary_release_year":"first_air_date_year")+"="+year));}
    static String region(String region){if(region==null||!region.matches("[A-Z]{2}"))throw new IllegalArgumentException("Invalid region");return region;}
}
