package com.example.videoplayer;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * 该类中所有的时间变量单位都是毫秒，需要用到秒和微秒的时候会通过乘除法进行转换
 */
public class VideoThreadAnother extends Thread {
    private static final String TAG = "VideoThreadAnother";

    private MediaCodec videoCodec;
    private MediaExtractor videoExtractor;

    private boolean isOver;
    private boolean isPlay;
    private boolean isSeek;

    private long videoThreadDuration;
    private long newTime;
    private long startTime;
    private long pauseTime;
    private long startPauseTime;

    public boolean init(Surface surface, String path){
        try {
            videoExtractor = new MediaExtractor();
            videoExtractor.setDataSource(path);
            for(int i = 0;i < videoExtractor.getTrackCount();i++){
                MediaFormat mediaFormat = videoExtractor.getTrackFormat(i);
                String mime = mediaFormat.getString(MediaFormat.KEY_MIME);
                if(mime.startsWith("video/")){
                    videoCodec = MediaCodec.createDecoderByType(mime);
                    videoThreadDuration = mediaFormat.getLong(MediaFormat.KEY_DURATION) / 1000;
                    Log.e(TAG, "videoThreadDuration:"+videoThreadDuration);
                    videoExtractor.selectTrack(i);
                    try{
                        videoCodec.configure(mediaFormat, surface, null, 0);
                    }catch (IllegalStateException e){
                        e.printStackTrace();
                        return false;
                    }
                    videoCodec.start();
                    break;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        return true;
    }

    @Override
    public void run() {
        isPlay = true;
        boolean isInput = true;
        boolean firstOutput = true;
        startTime = 0;
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while(!Thread.interrupted()){
            if(!isPlay)
                continue;
            if(isInput) {
                if(isSeek){
                    // MediaExtractor.seekTo方法得到的视频帧都是关键帧
                    // 在我所使用的测试文件上，seekTo方法得到的关键帧与想要跳转的目标位置常常有三四秒的误差
                    // 而MediaExtractor.seekTo方法得到的音频帧误差很小，基本上在10毫秒以内
                    // 为了解决拖动进度条后常常先播放音频帧的不同步问题，使用MediaExtractor.seekTo方法寻址视频关键帧之后
                    // 根据寻址得到的视频帧的时间戳，再寻址需要播放的音频帧
                    videoExtractor.seekTo(newTime * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    AudioThreadAnother.seekTo((int) videoExtractor.getSampleTime() / 1000000);
//                    if(videoExtractor.getSampleFlags() == MediaExtractor.SAMPLE_FLAG_SYNC)
//                        Log.e(TAG, "关键帧！！！"););
                    videoCodec.flush();
                    firstOutput = true;
                    synchronized (this){
                        isSeek = false;
                        pauseTime = 0;
                    }
                }
                int inputIndex = videoCodec.dequeueInputBuffer(10000);
                if(inputIndex >= 0){
                    ByteBuffer inputBuffer = videoCodec.getInputBuffer(inputIndex);
                    int bufferSize = videoExtractor.readSampleData(inputBuffer, 0);
                    if(bufferSize >= 0){
                        videoCodec.queueInputBuffer(inputIndex, 0, bufferSize, videoExtractor.getSampleTime(), 0);
                        videoExtractor.advance();
                    }else{
                        isInput = false;
                        videoCodec.queueInputBuffer(inputIndex, 0, 0,0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        Log.e(TAG,"input buffers end");
                    }
                }
            }

            int outputIndex = videoCodec.dequeueOutputBuffer(bufferInfo, 10000);
            switch(outputIndex){
                case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                    Log.e(TAG,"MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED");
                    break;
                case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                    Log.e(TAG,"MediaCodec.INFO_OUTPUT_FORMAT_CHANGED");
                    break;
                case MediaCodec.INFO_TRY_AGAIN_LATER:
                    Log.e(TAG,"MediaCodec.INFO_TRY_AGAIN_LATER");
                    break;
                default:
                    try{
                        long presentationTime = bufferInfo.presentationTimeUs / 1000;
                        if(newTime >= 0 && presentationTime > newTime){
                            if(firstOutput){
                                startTime = System.currentTimeMillis();
                                firstOutput = false;
                                newTime = presentationTime;
                            }
                            long sleepTime = presentationTime - AudioThread.getCurrentPosition();
                            if(sleepTime > 0){
                                Thread.sleep(sleepTime);
                            }
                            videoCodec.releaseOutputBuffer(outputIndex, true);
                        }else
                            videoCodec.releaseOutputBuffer(outputIndex, false);
                    }catch (InterruptedException e) {
                        Log.e(TAG,"Thread interrupted. Exiting...");
                        Thread.currentThread().interrupt();
                    }
                    break;
            }

            if((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                isOver = true;
                Log.e(TAG,"video frame has all presented");
                break;
            }

        }
        videoCodec.stop();
        videoCodec.release();
        videoExtractor.release();
    }

    public boolean isOver(){
        return isOver;
    }

    /**
     * VideoThread提供的跳转方法，方法参数得到的是秒，需要转换为毫秒
     * @param progress 为跳转时间，单位为秒
     */
    public void seekTo(int progress){
        isSeek = true;
        newTime = progress * 1000;
    }


    public void replay(){
        isPlay = true;
        pauseTime += System.currentTimeMillis() - startPauseTime;
    }

    public void pause(){
        if(isPlay) {
            isPlay = false;
            startPauseTime = System.currentTimeMillis();
        }
    }

    /**
     * 返回视频轨道的总播放时长
     * @return 返回的总播放时长，单位为秒
     */
    public int getVideoThreadDuration(){
        if(videoThreadDuration != 0)
            return (int) videoThreadDuration / 1000;
        else
            return 0;
    }

    /**
     * 获取当前的播放进度
     * 播放进度 = 现在的系统时间 - 开始的系统时间 - 中间的暂停时间
     * 当拖动进度条，播放进度需重新计算
     * 重新计算时，需要更新开始的系统时间，重置中间的暂停时间
     * 更新之后的播放进度 = 现在的系统时间 - 开始的系统时间 - 中间的暂停时间 + 拖动后的进度
     * 因为现在的同步策略为将VideoThread同步到AudioThread线程，所以该方法已弃用
     * 要获取播放进度请调用AudioThread.getCurrentPosition()
     * @return 返回时间，单位是秒
     */
    @Deprecated
    public int getCurrentTime(){
        return (int) (System.currentTimeMillis() - startTime - pauseTime + newTime) / 1000;
    }

}