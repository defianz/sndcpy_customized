package com.rom1v.sndcpy;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public class RecordService extends Service {

    private static final String TAG = "sndcpy";
    private static final String CHANNEL_ID = "sndcpy";
    private static final int NOTIFICATION_ID = 1;

    private static final String ACTION_RECORD = "com.rom1v.sndcpy.RECORD";
    private static final String ACTION_STOP = "com.rom1v.sndcpy.STOP";
    private static final String EXTRA_MEDIA_PROJECTION_DATA = "mediaProjectionData";

    private static final int MSG_CONNECTION_ESTABLISHED = 1;

    private static final String SOCKET_NAME = "sndcpy";


    private static final int SAMPLE_RATE = 60000;
    private static final int CHANNELS = 1;

    private final Handler handler = new ConnectionHandler(this);
    private MediaProjectionManager mediaProjectionManager;
    private MediaProjection mediaProjection;
    private Thread recorderThread;
    private Thread ServerThread;
    private AudioRecord recorder;
    private LocalServerSocket localServerSocket;
    private LocalSocket socket;

    public static void start(Context context, Intent data) {
        Intent intent = new Intent(context, RecordService.class);
        intent.setAction(ACTION_RECORD);
        intent.putExtra(EXTRA_MEDIA_PROJECTION_DATA, data);
        context.startForegroundService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Notification notification = createNotification(false);

        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_NONE);
        getNotificationManager().createNotificationChannel(channel);

        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (isRunning()) {
            return START_NOT_STICKY;
        }

        Intent data = intent.getParcelableExtra(EXTRA_MEDIA_PROJECTION_DATA);
        mediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, data);
        if (mediaProjection != null) {
            startServer();
        } else {
            Log.w(TAG, "Failed to capture audio");
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification createNotification(boolean established) {
        Notification.Builder notificationBuilder = new Notification.Builder(this, CHANNEL_ID);
        notificationBuilder.setContentTitle(getString(R.string.app_name));
        int textRes = established ? R.string.notification_forwarding : R.string.notification_waiting;
        notificationBuilder.setContentText(getText(textRes));
        notificationBuilder.setSmallIcon(R.drawable.ic_album_black_24dp);
        notificationBuilder.addAction(createStopAction());
        return notificationBuilder.build();
    }


    private Intent createStopIntent() {
        Intent intent = new Intent(this, RecordService.class);
        intent.setAction(ACTION_STOP);
        return intent;
    }

    private Notification.Action createStopAction() {
        Intent stopIntent = createStopIntent();
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_ONE_SHOT);
        Icon stopIcon = Icon.createWithResource(this, R.drawable.ic_close_24dp);
        String stopString = getString(R.string.action_stop);
        Notification.Action.Builder actionBuilder = new Notification.Action.Builder(stopIcon, stopString, stopPendingIntent);
        return actionBuilder.build();
    }

    private static AudioPlaybackCaptureConfiguration createAudioPlaybackCaptureConfig(MediaProjection mediaProjection) {
        AudioPlaybackCaptureConfiguration.Builder confBuilder = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection);
        confBuilder.addMatchingUsage(AudioAttributes.USAGE_MEDIA);
        confBuilder.addMatchingUsage(AudioAttributes.USAGE_GAME);
        confBuilder.addMatchingUsage(AudioAttributes.USAGE_UNKNOWN);
        return confBuilder.build();
    }

    private static AudioFormat createAudioFormat() {
        AudioFormat.Builder builder = new AudioFormat.Builder();
        builder.setEncoding(AudioFormat.ENCODING_PCM_16BIT);
        builder.setSampleRate(SAMPLE_RATE);
        builder.setChannelMask(CHANNELS == 2 ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO);
        return builder.build();
    }

    private static AudioRecord createAudioRecord(MediaProjection mediaProjection) {
        AudioRecord.Builder builder = new AudioRecord.Builder();
        builder.setAudioFormat(createAudioFormat());
        builder.setBufferSizeInBytes(2 * AudioRecord.getMinBufferSize(SAMPLE_RATE,CHANNELS,AudioFormat.ENCODING_PCM_16BIT));
        builder.setAudioPlaybackCaptureConfig(createAudioPlaybackCaptureConfig(mediaProjection));
        return builder.build();
    }


    private void startServer(){
        ServerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    localServerSocket = new LocalServerSocket(SOCKET_NAME);
                    while(true){
                        socket = localServerSocket.accept();
                        startRecording();
                    }
                } catch ( Exception e ){
                    System.out.println(e.toString());
                }
            }
        });
        ServerThread.start();
    }

    private void startRecording() {
        recorder = createAudioRecord(mediaProjection);
        recorderThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try  {
                    handler.sendEmptyMessage(MSG_CONNECTION_ESTABLISHED);
                    recorder.startRecording();
                    int BUFFER_MS = 15; // do not buffer more than BUFFER_MS milliseconds
                    int sizeInByte = 2 * AudioRecord.getMinBufferSize(SAMPLE_RATE,CHANNELS,AudioFormat.ENCODING_PCM_16BIT);

//                    byte[] buf = new byte[SAMPLE_RATE * CHANNELS * BUFFER_MS / 1000];
                    byte[] buf = new byte[sizeInByte];
                    while (true) {
                        int r = recorder.read(buf, 0, buf.length);
                        byte[] headerBuf = writeWavHeader((short)1 ,SAMPLE_RATE,(short)16, buf.length);
                        int hLen = headerBuf.length;
                        byte[] sendBuf = new byte[hLen+buf.length];

                        for (int i = 0; i < hLen; i++) {
                            sendBuf[i] = headerBuf[i];
                        }

                        for (int i = 0; i < buf.length; i++) {
                            sendBuf[i+hLen] = buf[i];
                        }

                        socket.getOutputStream().write(sendBuf, 0, sendBuf.length);
                    }
                } catch (IOException e) {
                    // ignore
                    System.out.println(e.toString());
                } finally {
                    recorder.stop();
                    try{
                        socket.close();
                    } catch (Exception e){
                        System.out.println(e.toString());
                    }
                }
            }
        });
        recorderThread.start();
    }

    private boolean isRunning() {
        return recorderThread != null;
    }


    public static byte[] writeWavHeader(short channels, int sampleRate, short bitDepth, int bufLength) throws IOException {
        // WAV 포맷에 필요한 little endian 포맷으로 다중 바이트의 수를 raw byte로 변환한다.
        byte[] littleBytes = ByteBuffer
                .allocate(22)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort(channels)
                .putInt(sampleRate)
                .putInt(sampleRate * channels * (bitDepth / 8))
                .putShort((short) (channels * (bitDepth / 8)))
                .putShort(bitDepth)
                .putInt(bufLength+36)
                .putInt(bufLength)
                .array();
        // 최고를 생성하지는 않겠지만, 적어도 쉽게만 가자.
        return new byte[]{
                'R', 'I', 'F', 'F', // Chunk ID
                littleBytes[14], littleBytes[15], littleBytes[16], littleBytes[17], // Chunk Size (나중에 업데이트 될것)
                'W', 'A', 'V', 'E', // Format
                'f', 'm', 't', ' ', //Chunk ID
                16, 0, 0, 0, // Chunk Size
                1, 0, // AudioFormat
                littleBytes[0], littleBytes[1], // Num of Channels
                littleBytes[2], littleBytes[3], littleBytes[4], littleBytes[5], // SampleRate
                littleBytes[6], littleBytes[7], littleBytes[8], littleBytes[9], // Byte Rate
                littleBytes[10], littleBytes[11], // Block Align
                littleBytes[12], littleBytes[13], // Bits Per Sample
                'd', 'a', 't', 'a', // Chunk ID
                littleBytes[18],littleBytes[19],littleBytes[20],littleBytes[21],
        };
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopForeground(true);
        if (recorderThread != null) {
            recorderThread.interrupt();
            recorderThread = null;
        }
    }

    private NotificationManager getNotificationManager() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static final class ConnectionHandler extends Handler {

        private RecordService service;

        ConnectionHandler(RecordService service) {
            this.service = service;
        }

        @Override
        public void handleMessage(Message message) {
            if (!service.isRunning()) {
                // if the VPN is not running anymore, ignore obsolete events
                return;
            }

            if (message.what == MSG_CONNECTION_ESTABLISHED) {
                Notification notification = service.createNotification(true);
                service.getNotificationManager().notify(NOTIFICATION_ID, notification);
            }
        }
    }
}