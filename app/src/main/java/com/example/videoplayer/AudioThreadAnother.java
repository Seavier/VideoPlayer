package com.example.videoplayer;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

public class AudioThreadAnother extends Thread{
    private static final String TAG ="AudioThreadAnother";
    private static long CURRENT_POSITION;
    private static long NEW_TIME;
    private static boolean IS_SEEK;

    private MediaCodec audioCodec;
    private MediaExtractor audioExtractor;
    private AudioTrack mAudioTrack;

    private boolean isOver;
    private boolean isPlay;

    private int mSampleRate;
    private int audioBufferSize;
    private long audioThreadDuration;
    private long startTime;
    private long baseTime;
    private long pauseTime;
    private long startPauseTime;

    public boolean init(String path){
        CURRENT_POSITION = 0;
        NEW_TIME = 0;
        try{
            audioExtractor = new MediaExtractor();
            audioExtractor.setDataSource(path);
            for(int i = 0;i < audioExtractor.getTrackCount();i++){
                MediaFormat mediaFormat = audioExtractor.getTrackFormat(i);
                String mime = mediaFormat.getString(MediaFormat.KEY_MIME);
                if(mime.startsWith("audio/")){
                    audioExtractor.selectTrack(i);
                    audioCodec = MediaCodec.createDecoderByType(mime);
                    if(mediaFormat.containsKey(MediaFormat.KEY_DURATION))
                        audioThreadDuration = mediaFormat.getLong(MediaFormat.KEY_DURATION) / 1000;
                    Log.e(TAG, "audioThreadDuration:"+audioThreadDuration);
                    mSampleRate = mediaFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    int channelCount = mediaFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    int bufferSize = 0;
                    if(mediaFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                        bufferSize = mediaFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                    int minBufferSize = AudioTrack.getMinBufferSize(mSampleRate,
                            (channelCount == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO),
                            AudioFormat.ENCODING_PCM_16BIT);
                    audioBufferSize = (minBufferSize > bufferSize? minBufferSize : bufferSize);
                    int encoding = getPCMEncoding(mediaFormat);

                    AudioFormat audioFormat = new AudioFormat.Builder()
                            .setSampleRate(mSampleRate)
                            .setEncoding(encoding)
                            .setChannelMask((channelCount == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO))
                            .build();
                    AudioAttributes audioAttributes = new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                            .build();
                    mAudioTrack = new AudioTrack(audioAttributes, audioFormat, audioBufferSize,
                            AudioTrack.MODE_STREAM, AudioManager.AUDIO_SESSION_ID_GENERATE);
                    mAudioTrack.play();

                    try{
                        audioCodec.configure(mediaFormat,null,null,0);
                    }catch(IllegalArgumentException e){
                        e.printStackTrace();
                        return false;
                    }
                    audioCodec.start();
                    break;
                }
            }
        }catch(IOException e){
            e.printStackTrace();
        }
        return true;
    }

    @Override
    public void run() {
        isPlay = true;
        startTime = 0;
        boolean isInput = true;
        boolean firstOutput = true;
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        while(!Thread.interrupted()){
            if(!isPlay)
                continue;
            if(isInput){
                if(IS_SEEK){
                    // baseTime的值其实就是等于newTime，另外添加这一个变量的原因是：
                    // 在多线程的环境中，有可能出现seekTo方法更改newTime值之后，音频线程正好在计算当前音频帧的休眠时间阶段的情况
                    // 如果使用的是newTime，sleepTime = presentationTime - (System.currentTimeMillis() - startTime - pauseTime + newTime)
                    // 这种时候会导致休眠时间计算错误，无法正确播放音频帧
                    // 因此另外添加一个变量baseTime，使得基准时间的改变只发生在新一轮循环的开始阶段
                    baseTime = NEW_TIME;
                    audioExtractor.seekTo(baseTime * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                    Log.e(TAG, "audioExtractor seekTo " + baseTime * 1000);
                    audioCodec.flush();
                    mAudioTrack.flush();
                    firstOutput = true;
                    synchronized (this){
                        IS_SEEK = false;
                        pauseTime = 0;
                        // 防止视频线程先解码出第一个可播放缓冲区后通过getCurrentPosition方法得到一个拖动滑块之前的未更新的currentPosition
                        // 因此导致视频线程休眠时间错误无法正确播放视频帧
                        CURRENT_POSITION = audioExtractor.getSampleTime() / 1000;
                    }
                }
                int inputIndex = audioCodec.dequeueInputBuffer(10000);
                if(inputIndex >= 0){
                    ByteBuffer inputBuffer = audioCodec.getInputBuffer(inputIndex);
                    int bufferSize = audioExtractor.readSampleData(inputBuffer, 0);
                    if(bufferSize >= 0){
                        audioCodec.queueInputBuffer(inputIndex,0,bufferSize,audioExtractor.getSampleTime(),0);
                        audioExtractor.advance();
                    }else{
                        isInput = false;
                        audioCodec.queueInputBuffer(inputIndex,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        Log.e(TAG,"input buffers end");
                    }
                }
            }

            int outputIndex = audioCodec.dequeueOutputBuffer(bufferInfo, 10000);
            switch(outputIndex){
                case MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED:
                    Log.e(TAG,"MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED");
                    break;
                case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                    Log.e(TAG,"MediaCodec.INFO_OUTPUT_FORMAT_CHANGED");
                    MediaFormat format = audioCodec.getOutputFormat();
                    Log.d(TAG, "New format " + format);
                    mAudioTrack.setPlaybackRate(format.getInteger(MediaFormat.KEY_SAMPLE_RATE));
                    break;
                case MediaCodec.INFO_TRY_AGAIN_LATER:
                    Log.e(TAG,"MediaCodec.INFO_TRY_AGAIN_LATER");
                    break;
                default:
                    try {
                        long presentationTime = bufferInfo.presentationTimeUs / 1000;
                        if (firstOutput) {
                            startTime = System.currentTimeMillis();
                            firstOutput = false;
                            baseTime = presentationTime;
                        }
                        long sleepTime = presentationTime - (System.currentTimeMillis() - startTime - pauseTime + baseTime);
                        CURRENT_POSITION = System.currentTimeMillis() - startTime - pauseTime + baseTime;
//                        long sleepTime = presentationTime - mAudioTrack.getPlaybackHeadPosition() * 1000 / mSampleRate;
                        if(sleepTime > 0){
                            Log.e(TAG, "AudioThread sleep Time: " + sleepTime);
                            Thread.sleep(sleepTime);
                        }
                        ByteBuffer outputBuffer = audioCodec.getOutputBuffer(outputIndex);
                        byte[] bytes = new byte[bufferInfo.size];
                        outputBuffer.get(bytes, bufferInfo.offset, bufferInfo.size);
                        mAudioTrack.write(bytes, bufferInfo.offset, bufferInfo.size);
                        outputBuffer.clear();
                        audioCodec.releaseOutputBuffer(outputIndex, false);
                    }catch(InterruptedException e){
                        Log.e(TAG, "Thread interrupted. Exiting...");
                        Thread.currentThread().interrupt();
                    }
                    break;
            }

            if((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                isOver = true;
                Log.e(TAG,"audio frame has all presented");
                break;
            }
        }
        audioCodec.stop();
        audioCodec.release();
        audioExtractor.release();
        mAudioTrack.stop();
        mAudioTrack.release();
    }

    private int getPCMEncoding(MediaFormat mediaFormat){
        int encoding = 0;
        if(!mediaFormat.containsKey(MediaFormat.KEY_PCM_ENCODING))
            return AudioFormat.ENCODING_PCM_16BIT;
        else{
            encoding = mediaFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
            switch(encoding){
                case 3:
                    encoding = AudioFormat.ENCODING_PCM_8BIT;
                    break;
                default:
                    encoding = AudioFormat.ENCODING_PCM_16BIT;
                    break;
            }
            return encoding;
        }
    }

    /**
     * AudioThread提供的跳转方法
     * 该方法为静态方法，为了方便拿不到AudioThread实例的VideoThread直接调用
     * @param progress 为跳转时间，单位为秒
     */
    public static void seekTo(int progress){
        IS_SEEK = true;
        NEW_TIME = progress * 1000;
    }

    /**
     * AudioThread提供的当前播放进度访问方法，帮助VideoThread同步到音频进度
     * 该方法同样为静态方法，为了方便拿不到AudioThread实例的VideoThread直接调用
     * @return currentPosition单位为毫秒
     */
    public static long getCurrentPosition(){
        return CURRENT_POSITION;
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


    public boolean isOver(){
        return isOver;
    }

    /**
     * 返回视频轨道的总播放时长
     * @return 返回的总播放时长，单位为秒
     */
    public int getAudioThreadDuration(){
        if(audioThreadDuration != 0)
            return (int) audioThreadDuration / 1000;
        else
            return 0;
    }
}
