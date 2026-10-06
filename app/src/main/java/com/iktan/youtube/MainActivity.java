package com.iktan.youtube;

import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import android.widget.BaseExpandableListAdapter;
import android.widget.ExpandableListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants;
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer;
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener;
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions;
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    private YouTubePlayer youTubePlayer = null;
    private TextView videoTitleText;
    private SeasonAdapter seasonAdapter;

    // Spanish audio auto-selection
    private static final String TAG = "IktanAudio";
    private WebView playerWebView;

    // Remote control / playback state
    private static final float SEEK_SECONDS = 10f;
    private static final int MAX_CONSECUTIVE_ERRORS = 5;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private boolean isPlaying = false;
    private float currentSecond = 0f;
    private int consecutiveErrors = 0;
    private long lastBackPressTime = 0;

    // Injected directly INTO the YouTube embed iframe (https://www.youtube.com) at document start.
    // The library page runs on https://com.iktan.youtube, so it can't reach the iframe itself;
    // this script runs inside the iframe and calls the internal player's setAudioTrack().
    private static final String SPANISH_AUDIO_JS =
            "(function(){" +
            "if(window.__iktanEs)return;window.__iktanEs=true;" +
            "if(location.pathname.indexOf('/embed/')!==0)return;" +
            "function log(m){try{IktanAudioBridge.log(m);}catch(e){console.log('IktanAudio '+m);}}" +
            "function info(t){var s='';if(!t)return s;" +
            " try{if(t.getLanguageInfo){var li=t.getLanguageInfo();s+=' '+(li.getId?li.getId():'')+' '+(li.getName?li.getName():'');}}catch(e){}" +
            " try{for(var k in t){var v=t[k];if(typeof v==='string')s+=' '+v;" +
            "  else if(v&&typeof v==='object'&&!Array.isArray(v)){var sub='',cap=false;" +
            "   for(var k2 in v){var w=v[k2];if(typeof w==='string'){if(w.indexOf('timedtext')>=0||w.indexOf('caption')>=0)cap=true;sub+=' '+w;}}" +
            "   if(!cap)s+=sub;}}}catch(e){}" +
            " return s.toLowerCase();}" +
            "function isEs(s){return /(^|[^a-z])es([-_.][a-z0-9]+)?([^a-z]|$)/.test(s)||s.indexOf('espa')>=0||s.indexOf('spanish')>=0;}" +
            "var lastVid=null,tries=0;" +
            "setInterval(function(){try{" +
            " var p=document.getElementById('movie_player')||document.querySelector('.html5-video-player');" +
            " if(!p||!p.getAvailableAudioTracks)return;" +
            " try{var ct=p.getOption&&p.getOption('captions','track');" +
            "  if(ct&&ct.languageCode){p.setOption('captions','track',{});if(p.unloadModule){p.unloadModule('captions');p.unloadModule('cc');}log('captions off '+ct.languageCode);}}catch(e){}" +
            " var vid='';try{vid=p.getVideoData().video_id;}catch(e){}" +
            " if(vid!==lastVid){lastVid=vid;tries=0;}" +
            " if(tries<0||tries>20)return;" +
            " var tracks=p.getAvailableAudioTracks()||[];if(!tracks.length)return;" +
            " tries++;" +
            " if(tries===1){var dump=[];for(var d=0;d<tracks.length;d++)dump.push(info(tracks[d]));log('tracks '+vid+' ('+tracks.length+'): '+dump.join(' || '));}" +
            " var cur=null;try{cur=p.getAudioTrack&&p.getAudioTrack();}catch(e){}" +
            " if(cur&&isEs(info(cur))){log('already spanish '+vid+':'+info(cur));tries=-1;return;}" +
            " var best=null;for(var i=0;i<tracks.length;i++){var s=info(tracks[i]);" +
            "  if(isEs(s)){if(s.indexOf('419')>=0||s.indexOf('latin')>=0){best=tracks[i];break;}if(!best)best=tracks[i];}}" +
            " if(!best){var all=[];for(var j=0;j<tracks.length;j++)all.push(info(tracks[j]));log('nospanish '+vid+':'+all.join(' | '));tries=-1;return;}" +
            " p.setAudioTrack(best);log('set '+vid+':'+info(best));" +
            "}catch(e){log('error '+e);}},1000);" +
            "})();";

    // Flat lists for playback
    private List<String> videoIds = new ArrayList<>();
    private List<String> videoTitles = new ArrayList<>();
    private int currentIndex = 0;

    // Structured data for the Left Menu
    private List<String> seasonGroups = new ArrayList<>();
    private Map<String, List<VideoItem>> seasonEpisodesMap = new HashMap<>();

    // Simple class to hold episode data for the menu
    class VideoItem {
        String title;
        int globalIndex;
        VideoItem(String title, int globalIndex) {
            this.title = title;
            this.globalIndex = globalIndex;
        }
    }

    @Override
    protected void attachBaseContext(android.content.Context newBase) {
        // Force the app's language to Spanish (Mexico) properly for WebViews on Android 7+
        Locale locale = new Locale("es", "ES");
        Locale.setDefault(locale);
        Configuration config = new Configuration();
        config.setLocale(locale);
        super.attachBaseContext(newBase.createConfigurationContext(config));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Hide system navigation bars and keep screen on
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        videoTitleText = findViewById(R.id.video_title_text);

        // 1. Load data from JSON and structure it into Seasons
        loadPlaylistFromJson();
        buildSeasonData();

        // Retrieve the last watched episode index (defaults to 0 on first launch)
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        currentIndex = prefs.getInt("last_watched_episode", 0);

        // 2. Setup the Expandable Left Menu
        ExpandableListView expandableListView = findViewById(R.id.season_expandable_list);

        seasonAdapter = new SeasonAdapter();
        expandableListView.setAdapter(seasonAdapter);

        expandableListView.setOnChildClickListener((parent, v, groupPosition, childPosition, id) -> {
            // Find the global index of the clicked episode and play it
            VideoItem selectedVideo = seasonEpisodesMap.get(seasonGroups.get(groupPosition)).get(childPosition);
            currentIndex = selectedVideo.globalIndex;
            if (youTubePlayer != null) {
                playCurrentVideo();
            }
            return true;
        });

        // Open the menu on the episode that is currently playing
        focusCurrentEpisode();

        // 3. Setup the Player
        YouTubePlayerView youTubePlayerView = findViewById(R.id.youtube_player_view);
        getLifecycle().addObserver(youTubePlayerView);

        IFramePlayerOptions iFramePlayerOptions = new IFramePlayerOptions.Builder(getApplicationContext())
                .controls(1)
                .build();

        // Must be registered BEFORE initialize() so it applies to the YouTube iframe when it loads
        installSpanishAudioScript(youTubePlayerView);

        youTubePlayerView.initialize(new AbstractYouTubePlayerListener() {
            @Override
            public void onReady(@NonNull YouTubePlayer initializedYouTubePlayer) {
                youTubePlayer = initializedYouTubePlayer;
                playCurrentVideo();
            }
            @Override
            public void onStateChange(@NonNull YouTubePlayer youTubePlayer, @NonNull PlayerConstants.PlayerState state) {
                isPlaying = state == PlayerConstants.PlayerState.PLAYING;
                if (isPlaying) consecutiveErrors = 0;
                if (state == PlayerConstants.PlayerState.ENDED) playNextVideo();
            }
            @Override
            public void onCurrentSecond(@NonNull YouTubePlayer youTubePlayer, float second) {
                currentSecond = second;
            }
            @Override
            public void onError(@NonNull YouTubePlayer youTubePlayer, @NonNull PlayerConstants.PlayerError error) {
                // Removed/blocked video: skip to the next one, but stop if many fail in a row (e.g. no internet)
                Log.w(TAG, "Player error " + error + " on " + videoIds.get(currentIndex));
                if (++consecutiveErrors <= MAX_CONSECUTIVE_ERRORS) {
                    uiHandler.postDelayed(MainActivity.this::playNextVideo, 2000);
                }
            }
        }, true, iFramePlayerOptions);

        // 4. Setup Controls
        View topControls = findViewById(R.id.top_controls);
        View bottomControls = findViewById(R.id.bottom_controls);

        findViewById(R.id.previous_video_button).setOnClickListener(view -> playPreviousVideo());
        findViewById(R.id.next_video_button).setOnClickListener(view -> playNextVideo());

        findViewById(R.id.fullscreen_button).setOnClickListener(view -> {
            topControls.setVisibility(View.GONE);
            bottomControls.setVisibility(View.GONE);
            expandableListView.setVisibility(View.GONE); // Hide the left menu too
        });
    }

    private void playCurrentVideo() {
        if (videoIds.isEmpty() || youTubePlayer == null) return;
        uiHandler.removeCallbacksAndMessages(null);
        currentSecond = 0f;
        youTubePlayer.loadVideo(videoIds.get(currentIndex), 0f);
        videoTitleText.setText(videoTitles.get(currentIndex));

        // Save the progress instantly whenever a video starts
        SharedPreferences.Editor editor = getPreferences(MODE_PRIVATE).edit();
        editor.putInt("last_watched_episode", currentIndex);
        editor.apply();

        if (seasonAdapter != null) {
            seasonAdapter.notifyDataSetChanged();
        }
    }

    private void playNextVideo() {
        if (videoIds.isEmpty() || youTubePlayer == null) return;
        currentIndex = (currentIndex + 1) % videoIds.size();
        playCurrentVideo();
    }

    private void playPreviousVideo() {
        if (videoIds.isEmpty() || youTubePlayer == null) return;
        currentIndex = (currentIndex - 1 < 0) ? videoIds.size() - 1 : currentIndex - 1;
        playCurrentVideo();
    }

    // Expands the current episode's season and puts the remote's highlight on that episode
    private void focusCurrentEpisode() {
        ExpandableListView list = findViewById(R.id.season_expandable_list);
        for (int g = 0; g < seasonGroups.size(); g++) {
            List<VideoItem> episodes = seasonEpisodesMap.get(seasonGroups.get(g));
            for (int c = 0; c < episodes.size(); c++) {
                if (episodes.get(c).globalIndex == currentIndex) {
                    final int group = g, child = c;
                    list.post(() -> {
                        list.expandGroup(group);
                        list.setSelectedChild(group, child, true);
                        list.requestFocus();
                    });
                    return;
                }
            }
        }
    }

    // Lets the remote's media buttons control the video no matter where the highlight is
    private boolean handleMediaKey(KeyEvent event) {
        int code = event.getKeyCode();
        boolean isMediaKey = code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY
                || code == KeyEvent.KEYCODE_MEDIA_PAUSE || code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                || code == KeyEvent.KEYCODE_MEDIA_REWIND || code == KeyEvent.KEYCODE_MEDIA_NEXT
                || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS;
        if (!isMediaKey) return false;
        if (youTubePlayer == null || event.getAction() != KeyEvent.ACTION_DOWN) return true;

        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (isPlaying) youTubePlayer.pause(); else youTubePlayer.play();
                break;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                youTubePlayer.play();
                break;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                youTubePlayer.pause();
                break;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                currentSecond += SEEK_SECONDS;
                youTubePlayer.seekTo(currentSecond);
                break;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                currentSecond = Math.max(0f, currentSecond - SEEK_SECONDS);
                youTubePlayer.seekTo(currentSecond);
                break;
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                if (event.getRepeatCount() == 0) playNextVideo();
                break;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                if (event.getRepeatCount() == 0) playPreviousVideo();
                break;
        }
        return true;
    }

    // Registers SPANISH_AUDIO_JS to run inside every https://www.youtube.com frame of the player WebView
    private void installSpanishAudioScript(View youTubePlayerView) {
        playerWebView = findWebView(youTubePlayerView);
        if (playerWebView == null) {
            Log.w(TAG, "Player WebView not found");
            return;
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            Log.w(TAG, "DOCUMENT_START_SCRIPT not supported; update Android System WebView");
            return;
        }
        // JS interfaces are exposed to iframes too, so the script can log back to Logcat
        playerWebView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void log(String message) { Log.d(TAG, message); }
        }, "IktanAudioBridge");

        Set<String> origins = new HashSet<>();
        origins.add("https://www.youtube.com");
        WebViewCompat.addDocumentStartJavaScript(playerWebView, SPANISH_AUDIO_JS, origins);
        Log.d(TAG, "Spanish audio script installed");
    }

    // The library hides its WebView inside YouTubePlayerView, so search the hierarchy for it
    private WebView findWebView(View view) {
        if (view instanceof WebView) return (WebView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                WebView found = findWebView(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    // The Shield remote's OK button (DPAD_CENTER) isn't treated as a click by the WebView,
    // so YouTube's settings menu items can't be selected. Translate it to ENTER.
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (handleMediaKey(event)) return true;
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_BUTTON_A) {
            View focused = getCurrentFocus();
            if (focused instanceof WebView) {
                KeyEvent enter = new KeyEvent(event.getDownTime(), event.getEventTime(),
                        event.getAction(), KeyEvent.KEYCODE_ENTER, event.getRepeatCount(),
                        event.getMetaState(), event.getDeviceId(), event.getScanCode(),
                        event.getFlags(), event.getSource());
                return focused.dispatchKeyEvent(enter);
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        View topControls = findViewById(R.id.top_controls);
        View bottomControls = findViewById(R.id.bottom_controls);
        View leftMenu = findViewById(R.id.season_expandable_list);

        if (leftMenu.getVisibility() == View.GONE) {
            topControls.setVisibility(View.VISIBLE);
            bottomControls.setVisibility(View.VISIBLE);
            leftMenu.setVisibility(View.VISIBLE);
            focusCurrentEpisode();
        } else if (System.currentTimeMillis() - lastBackPressTime > 2000) {
            // Avoid closing the app by accident: require a second Back press within 2 seconds
            lastBackPressTime = System.currentTimeMillis();
            Toast.makeText(this, "Presiona Atrás otra vez para salir", Toast.LENGTH_SHORT).show();
        } else {
            super.onBackPressed();
        }
    }

    private void loadPlaylistFromJson() {
        try {
            InputStream is = getAssets().open("iktan_playlist_minified.json");
            int size = is.available();
            byte[] buffer = new byte[size];
            is.read(buffer);
            is.close();
            JSONArray jsonArray = new JSONArray(new String(buffer, "UTF-8"));
            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject obj = jsonArray.getJSONObject(i);
                videoIds.add(obj.getString("id"));
                videoTitles.add(obj.getString("title"));
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    // Maps the flat JSON items into their respective Seasons
    private void buildSeasonData() {
        int[] boundaries = {0, 52, 112, 153, 205, 269, 316, 368, 420, 467, 513};

        for (int i = 0; i < 10; i++) {
            String seasonName = "Temporada " + (i + 1);
            seasonGroups.add(seasonName);

            List<VideoItem> episodes = new ArrayList<>();
            for (int j = boundaries[i]; j < boundaries[i + 1] && j < videoIds.size(); j++) {
                episodes.add(new VideoItem(videoTitles.get(j), j));
            }
            seasonEpisodesMap.put(seasonName, episodes);
        }
    }

    // Custom Adapter that styles the menu and makes it compatible with the Shield D-pad
    private class SeasonAdapter extends BaseExpandableListAdapter {
        @Override public int getGroupCount() { return seasonGroups.size(); }
        @Override public int getChildrenCount(int groupPosition) { return seasonEpisodesMap.get(seasonGroups.get(groupPosition)).size(); }
        @Override public Object getGroup(int groupPosition) { return seasonGroups.get(groupPosition); }
        @Override public Object getChild(int groupPosition, int childPosition) { return seasonEpisodesMap.get(seasonGroups.get(groupPosition)).get(childPosition); }
        @Override public long getGroupId(int groupPosition) { return groupPosition; }
        @Override public long getChildId(int groupPosition, int childPosition) { return childPosition; }
        @Override public boolean hasStableIds() { return false; }

        // Setup the dynamic text colors (Black when focused, White when unfocused)
        private android.content.res.ColorStateList getDynamicTextColor() {
            int[][] states = new int[][] {
                    new int[] { android.R.attr.state_focused },
                    new int[] {}
            };
            int[] colors = new int[] { Color.WHITE, Color.BLACK };
            return new android.content.res.ColorStateList(states, colors);
        }

        @Override
        public View getGroupView(int groupPosition, boolean isExpanded, View convertView, ViewGroup parent) {
            TextView tv = (TextView) convertView;
            if (tv == null) {
                tv = new TextView(MainActivity.this);
                tv.setTextSize(18f);
                tv.setTypeface(null, Typeface.BOLD);

                // Apply the custom rounded yellow highlight for the remote
                tv.setBackgroundResource(R.drawable.tv_focus_bg);
                // Must set padding AFTER background resource in Android
                tv.setPadding(80, 40, 32, 40);
                tv.setTextColor(getDynamicTextColor());
            }
            tv.setText((String) getGroup(groupPosition));
            return tv;
        }

        @Override
        public View getChildView(int groupPosition, int childPosition, boolean isLastChild, View convertView, ViewGroup parent) {
            TextView tv = (TextView) convertView;
            if (tv == null) {
                tv = new TextView(MainActivity.this);
                tv.setTextSize(14f);
                tv.setTypeface(null, Typeface.BOLD);
                tv.setTextColor(getDynamicTextColor());
            }

            VideoItem episode = (VideoItem) getChild(groupPosition, childPosition);

            // Check if this specific episode is the one currently playing
            if (episode.globalIndex == currentIndex) {
                tv.setBackgroundResource(R.drawable.playing_episode_bg);
                tv.setText("▶ " + episode.title);
            } else {
                tv.setBackgroundResource(R.drawable.episode_focus_bg);
                tv.setText(episode.title);
            }

            // Padding MUST be set after the background resource in Android
            tv.setPadding(80, 24, 24, 24);

            return tv;
        }

        @Override public boolean isChildSelectable(int groupPosition, int childPosition) { return true; }
    }
}