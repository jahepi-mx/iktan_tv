package com.iktan.youtube;

import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseExpandableListAdapter;
import android.widget.ExpandableListView;
import android.widget.TextView;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    private YouTubePlayer youTubePlayer = null;
    private TextView videoTitleText;
    private SeasonAdapter seasonAdapter;

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
    protected void onCreate(Bundle savedInstanceState) {
        // Force the app's language to Spanish (Mexico) for audio track prioritization
        Locale locale = new Locale("es", "MX");
        Locale.setDefault(locale);
        Configuration config = new Configuration();
        config.setLocale(locale);
        getResources().updateConfiguration(config, getResources().getDisplayMetrics());

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

        // 3. Setup the Player
        YouTubePlayerView youTubePlayerView = findViewById(R.id.youtube_player_view);
        getLifecycle().addObserver(youTubePlayerView);

        IFramePlayerOptions iFramePlayerOptions = new IFramePlayerOptions.Builder(getApplicationContext())
                .controls(1).build();

        youTubePlayerView.initialize(new AbstractYouTubePlayerListener() {
            @Override
            public void onReady(@NonNull YouTubePlayer initializedYouTubePlayer) {
                youTubePlayer = initializedYouTubePlayer;
                playCurrentVideo();
            }
            @Override
            public void onStateChange(@NonNull YouTubePlayer youTubePlayer, @NonNull PlayerConstants.PlayerState state) {
                if (state == PlayerConstants.PlayerState.ENDED) playNextVideo();
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

    @Override
    public void onBackPressed() {
        View topControls = findViewById(R.id.top_controls);
        View bottomControls = findViewById(R.id.bottom_controls);
        View leftMenu = findViewById(R.id.season_expandable_list);

        if (leftMenu.getVisibility() == View.GONE) {
            topControls.setVisibility(View.VISIBLE);
            bottomControls.setVisibility(View.VISIBLE);
            leftMenu.setVisibility(View.VISIBLE);
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