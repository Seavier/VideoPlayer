package com.example.videoplayer;

import android.app.Activity;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;

public class MainActivity extends Activity implements View.OnClickListener{
    private static final String TAG = "MainActivity";
    private static final String FILE_PATH = Environment.getExternalStorageDirectory() + "/Movie/LinearMath.mp4";// machinelearning test LinearMath
    private SurfaceView surfaceView;
    private SeekBar seekBar;
    private Button playBtn;
    private Button pauseBtn;
    private Button stopBtn;
    private VideoPlayer videoPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        surfaceView = findViewById(R.id.surfaceView);
        seekBar = findViewById(R.id.seekBar);
        playBtn = findViewById(R.id.play);
        pauseBtn = findViewById(R.id.pause);
        stopBtn = findViewById(R.id.stop);
        playBtn.setOnClickListener(this);
        pauseBtn.setOnClickListener(this);
        stopBtn.setOnClickListener(this);
        checkMediaFormat();
        videoPlayer = new VideoPlayer(surfaceView, seekBar, FILE_PATH);
    }

    @Override
    public void onClick(View v) {
        switch(v.getId()){
            case R.id.play:
                videoPlayer.play();
                break;
            case R.id.pause:
                videoPlayer.pause();
                break;
            default:
                videoPlayer.stop();
                break;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        videoPlayer.stop();
        videoPlayer = null;
    }

    public void checkMediaFormat(){
        MediaExtractor mediaExtractor = new MediaExtractor();
        try{
            mediaExtractor.setDataSource(FILE_PATH);
            int count = mediaExtractor.getTrackCount();
            Log.e(TAG, "轨道数量 = "+count);
            for(int i = 0;i < count;i++){
                MediaFormat mediaFormat = mediaExtractor.getTrackFormat(i);
                String mime = mediaFormat.getString(MediaFormat.KEY_MIME);
                Log.e(TAG, "MIME:"+mime);
            }
        }catch (Exception e){
            e.printStackTrace();
        }
    }

}