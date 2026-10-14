package local.tv.home;
public class VideoFilesTest {
 public static void main(String[] args){
  if(VideoFiles.score("Show.S01E02.mkv","",1,2)!=2)throw new AssertionError();
  if(VideoFiles.score("Show.S01E20.mkv","",1,2)!=-1)throw new AssertionError();
  if(VideoFiles.score("sample.mkv","",-1,-1)!=-1)throw new AssertionError();
  if(VideoFiles.score("film.mp4","film.mp4",-1,-1)!=3)throw new AssertionError();
  if(VideoFiles.score("Show.1x02.mkv","",1,2)!=2)throw new AssertionError();
  if(VideoFiles.score("subtitle.srt","",1,2)!=-1)throw new AssertionError();
  System.out.println("6 episode/file checks passed");
 }
}
