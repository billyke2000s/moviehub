package local.tv.home;

import android.app.Activity;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.*;

/** Foreground Android player; codec and stream support require device testing. */
public class PlaybackActivity extends Activity {
    private VideoView video;
    private int position;
    private String mediaId;
    private boolean live,remember,foreground,resumePlaying=true;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        mediaId=getIntent().getStringExtra("id");live=getIntent().getBooleanExtra("live",false);remember=getSharedPreferences("ui",MODE_PRIVATE).getBoolean("resume",true);
        String url=getIntent().getStringExtra("url");boolean http=getSharedPreferences("ui",MODE_PRIVATE).getBoolean("http",false);
        try{url=Playlist.networkUrl(url,http);}catch(Exception e){Toast.makeText(this,"Media URL is unavailable",Toast.LENGTH_LONG).show();finish();return;}
        video=new VideoView(this);setContentView(video);MediaController controls=new MediaController(this);controls.setAnchorView(video);video.setMediaController(controls);
        position=state==null?(remember&&!live?getSharedPreferences("positions",MODE_PRIVATE).getInt(mediaId,0):0):state.getInt("position");
        video.setOnPreparedListener(player->{if(position>0&&!live)video.seekTo(position);if(foreground&&resumePlaying)video.start();});
        video.setOnErrorListener((player,what,extra)->{Toast.makeText(this,"This stream could not be played",Toast.LENGTH_LONG).show();finish();return true;});
        video.setOnCompletionListener(player->{getSharedPreferences("positions",MODE_PRIVATE).edit().remove(mediaId).apply();position=0;finish();});
        video.setVideoPath(url);video.requestFocus();
    }
    @Override public void onPause(){foreground=false;super.onPause();if(video!=null){resumePlaying=video.isPlaying();position=video.getCurrentPosition();video.pause();if(remember&&!live)getSharedPreferences("positions",MODE_PRIVATE).edit().putInt(mediaId,position).apply();}}
    @Override public void onResume(){super.onResume();foreground=true;if(video!=null&&resumePlaying){if(position>0&&!live)video.seekTo(position);video.start();}}
    @Override public void onSaveInstanceState(Bundle out){super.onSaveInstanceState(out);if(video!=null)out.putInt("position",video.getCurrentPosition());}
    @Override public void onDestroy(){if(video!=null)video.stopPlayback();super.onDestroy();}
}
