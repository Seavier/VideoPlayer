package com.example.videoplayer;

import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.widget.SeekBar;

/**
 * VideoPlayerAnother使用的是 VideoThreadAnother 和 AudioThreadAnother
 */
public class VideoPlayerAnother implements SurfaceHolder.Callback, SeekBar.OnSeekBarChangeListener{

    private static final String TAG = "VideoPlayer";
    private static final int VIDEO_ERROR = -1;
    private static final int VIDEO_READY = 0;
    private static final int VIDEO_PLAY = 1;
    private static final int VIDEO_PAUSE = 2;
    private static final int VIDEO_OVER = 3;

    private SurfaceView surfaceView;
    private SeekBar seekBar;
    private String path;

    private VideoThreadAnother videoThread;
    private AudioThreadAnother audioThread;
    private UpdateSeekBarThread updateSeekBarThread;
    private int videoStatus;
    private int videoDuration;

    VideoPlayerAnother(SurfaceView aSurfaceView, SeekBar aSeekBar, String aPath){
        surfaceView = aSurfaceView;
        seekBar = aSeekBar;
        path = aPath;

        surfaceView.getHolder().addCallback(this);
        seekBar.setOnSeekBarChangeListener(this);
        videoStatus = VIDEO_ERROR;
    }

    public void play(){
        if(videoStatus == VIDEO_PLAY && videoIsOverOrNo())
            videoStatus = VIDEO_OVER;
        switch(videoStatus){
            case VIDEO_ERROR:
                Log.e(TAG, "error occurs when play");
                break;
            case VIDEO_READY:
                videoThread.start();
                audioThread.start();
                updateSeekBarThread.start();
                videoStatus = VIDEO_PLAY;
                break;
            case VIDEO_PLAY:
                break;
            case VIDEO_PAUSE:
                videoThread.replay();
                audioThread.replay();
                videoStatus = VIDEO_PLAY;
                break;
            case VIDEO_OVER:
                if(videoPrepare()){
                    videoThread.start();
                    audioThread.start();
                    updateSeekBarThread.start();
                    videoStatus = VIDEO_PLAY;
                }
                break;
        }
    }

    public void pause(){
        if(videoStatus == VIDEO_PLAY){
            videoThread.pause();
            audioThread.pause();
            videoStatus = VIDEO_PAUSE;
        }
    }

    public void stop(){
        if((videoStatus == VIDEO_PLAY || videoStatus == VIDEO_PAUSE) && videoStop()){
            videoStatus = VIDEO_OVER;
        }
    }


    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if(videoPrepare()){
            videoStatus = VIDEO_READY;
        }else
            videoStatus = VIDEO_ERROR;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {

    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {

    }

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {

    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {

    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        if((videoStatus == VIDEO_PLAY || videoStatus == VIDEO_PAUSE))
            videoSeekTo(seekBar.getProgress());
    }

    private boolean videoPrepare(){
        videoThread = new VideoThreadAnother();
        audioThread = new AudioThreadAnother();
        updateSeekBarThread = new UpdateSeekBarThread();
        boolean videoOk = false;
        boolean audioOk = false;
        int tempVideoThreadTime = 0;
        int tempAudioThreadTime = 0;
        if(videoThread != null && videoThread.init(surfaceView.getHolder().getSurface(), path)) {
            videoOk = true;
            tempVideoThreadTime = videoThread.getVideoThreadDuration();
            Log.e(TAG,"tempVideoThreadTime: "+tempVideoThreadTime);
            Log.e(TAG, "VideoThread OK!");
        }
        if(audioThread != null && audioThread.init(path)) {
            audioOk = true;
            tempAudioThreadTime = audioThread.getAudioThreadDuration();
            Log.e(TAG,"tempAudioThreadTime: "+tempVideoThreadTime);
            Log.e(TAG,"AudioThread OK!");
        }
        videoDuration = Math.max(tempVideoThreadTime, tempAudioThreadTime);
        Log.e(TAG,"videoDuration: "+videoDuration);
        seekBar.setMax(videoDuration);
        return (videoOk && audioOk);
    }

    private void videoSeekTo(int progress){
        videoThread.seekTo(progress);// 先定位视频帧，再根据视频帧的时间戳定位音频帧
    }

    private boolean videoStop(){
        boolean videoStop = false;
        boolean audioStop = false;
        updateSeekBarThread.interrupt();
        if(videoThread != null){
            videoThread.interrupt();
            videoThread = null;
            videoStop = true;
        }
        if(audioThread != null){
            audioThread.interrupt();
            audioThread = null;
            audioStop = true;
        }
        return (videoStop && audioStop);
    }

    /**
     * 主动查询两个线程的状态，判断视频播放是否完成
     * @return 返回boolean值
     */
    private boolean videoIsOverOrNo(){
        boolean videoOver = false;
        boolean audioOver = false;
        if(videoThread != null){
            videoOver = videoThread.isOver();
        }
        if(audioThread != null){
            audioOver = audioThread.isOver();
        }
        return (videoOver && audioOver);
    }

    private class UpdateSeekBarThread extends Thread{
        @Override
        public void run() {
            while(!Thread.interrupted()){
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Log.e(TAG,"Thread interrupted. Exiting...");
                    Thread.currentThread().interrupt();
                }
                if(videoStatus != VIDEO_PLAY)
                    continue;
                if(videoIsOverOrNo())
                    break;
                int currentTime = (int) AudioThread.getCurrentPosition() / 1000;
                Log.e(TAG,"currentTime: "+currentTime);
                seekBar.setProgress(currentTime);
            }
            Log.e(TAG, "UpdateSeekBarThread end");
        }
    }
}
