package com.andreszs.plugins.mlkitsuite;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;

final class MlKitSuccessBeep implements AutoCloseable {
    private final Context context;
    private final Handler mainHandler;
    private AudioTrack track;

    MlKitSuccessBeep(Context context, Handler mainHandler) {
        this.context = context.getApplicationContext();
        this.mainHandler = mainHandler;
    }

    void play() {
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null && audioManager.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) {
            return;
        }
        final int sampleRate = 22050;
        final int sampleCount = sampleRate / 10;
        byte[] pcm = new byte[sampleCount * 2];
        for (int index = 0; index < sampleCount; index++) {
            double time = index / (double) sampleRate;
            double remaining = (sampleCount - index) / (double) sampleRate;
            double envelope = Math.min(1d, time / 0.005d) * Math.min(1d, remaining / 0.025d);
            short sample = (short) Math.round(Math.sin(2d * Math.PI * 1800d * time)
                * envelope * Short.MAX_VALUE * 0.55d);
            pcm[index * 2] = (byte) (sample & 0xff);
            pcm[index * 2 + 1] = (byte) ((sample >>> 8) & 0xff);
        }
        try {
            AudioTrack nextTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build())
                .setAudioFormat(new AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.length)
                .build();
            if (nextTrack.write(pcm, 0, pcm.length) <= 0) {
                nextTrack.release();
                return;
            }
            close();
            track = nextTrack;
            nextTrack.play();
            mainHandler.postDelayed(() -> {
                if (track == nextTrack) close();
            }, 200L);
        } catch (Exception ignored) {
            close();
        }
    }

    @Override
    public void close() {
        if (track == null) return;
        try {
            track.stop();
        } catch (Exception ignored) { }
        track.release();
        track = null;
    }
}
