package com.leaves.app.shareme.service;

import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.support.annotation.Nullable;
import android.widget.Toast;


import com.koushikdutta.async.http.WebSocket;
import com.koushikdutta.async.http.server.AsyncHttpServer;
import com.koushikdutta.async.http.server.AsyncHttpServerRequest;
import com.leaves.app.shareme.Constant;
import com.leaves.app.shareme.bean.Frame;
import com.leaves.app.shareme.bean.Media;
import com.leaves.app.shareme.eventbus.RxBus;
import com.leaves.app.shareme.eventbus.TimeSeekEvent;
import com.leaves.app.shareme.ui.activity.MainActivity;

import java.util.concurrent.Callable;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.functions.Consumer;
import io.reactivex.schedulers.Schedulers;

/**
 * Created by Leaves on 2016/11/7.
 */

public class MusicServerService extends AbsMusicService implements MediaPlayer.OnPreparedListener,
        MediaPlayer.OnErrorListener, MediaPlayer.OnCompletionListener ,WebSocket.StringCallback{
    private MediaPlayer mMediaPlayer = null;

    private Disposable mTimeSeekDisposable;
    private CompositeDisposable mCompositeDisposable;


    private WifiManager.WifiLock mWifiLock;
    private Observable<Long> timeSeek;

    private boolean isPrepared = false;
    private ServerBinder mBinder;
    private Frame mFrame;

    private AsyncHttpServer mWebSocketServer;
    private WebSocket mConnectedWebSocket;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (mCompositeDisposable == null || !mCompositeDisposable.isDisposed()) {
            mCompositeDisposable = new CompositeDisposable();
        }
        //先建立webSocket服务器，通知client连接
        mWebSocketServer = new AsyncHttpServer();
        mWebSocketServer.websocket(Constant.WebSocket.REGEX, null, new AsyncHttpServer.WebSocketRequestCallback() {
            @Override
            public void onConnected(WebSocket webSocket, AsyncHttpServerRequest request) {
                mConnectedWebSocket = webSocket;
                webSocket.setStringCallback(MusicServerService.this);
            }
        });
        mWebSocketServer.listen(Constant.WebSocket.PORT);
        return START_STICKY;
    }


    @Override
    protected void stop() {
        if (mMediaPlayer != null) {
            mMediaPlayer.stop();
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
        unregisterTimeSeek();
        stopForeground(true);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (mCompositeDisposable == null || !mCompositeDisposable.isDisposed()) {
            mCompositeDisposable = new CompositeDisposable();
        }
        mMediaPlayer = new MediaPlayer(); // initialize it here
        mMediaPlayer.setOnPreparedListener(this);
        mMediaPlayer.setOnErrorListener(this);
        mMediaPlayer.setOnCompletionListener(this);
        mMediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        timeSeek = Observable.fromCallable(new Callable<Long>() {
            @Override
            public Long call() throws Exception {
                if (mMediaPlayer != null) {
                    return (long) mMediaPlayer.getCurrentPosition() * 1000L;
                }
                return 0L;
            }
        }).observeOn(Schedulers.newThread()).repeat().observeOn(AndroidSchedulers.mainThread());
        isPrepared = false;
    }

    @Override
    public void onStringAvailable(String s) {

    }

    @Override
    protected Intent getNotificationIntent() {
        return new Intent(this, MainActivity.class);
    }

    @Override
    protected void reset() {

    }

    protected void pause() {
        if (mMediaPlayer != null && mMediaPlayer.isPlaying()) {
            mMediaPlayer.pause();
        }
        unregisterTimeSeek();
    }

    @Override
    protected void start(boolean invalidate) {
        //notifyThe client
        if (mConnectedWebSocket != null) {
            mConnectedWebSocket.send("start Play");
        }
        if (invalidate) {
            mMediaPlayer.reset();
            Uri uri = Uri.parse(mMedia.getSrc());
            try {
                mMediaPlayer.setDataSource(this, uri);
                mMediaPlayer.prepareAsync(); // prepare async to not block main thread
                isPrepared = false;
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else {
            if (mMediaPlayer != null && isPrepared) {
                mMediaPlayer.start();
            }
        }
    }

//    private void playAsServer(Media media) {
//        if (media == null) {
//            return;
//        }
//        if (mMediaPlayer == null) {
//            initMediaPlayer();
//        }
//        if (mPlayingMedia != null && media.getSrc().equals(mPlayingMedia.getSrc()) && isPrepared) {
//            mMediaPlayer.start();
//            registerTimeSeek();
//            return;
//        }
//        mMediaPlayer.reset();
//        Uri uri = Uri.parse(media.getSrc());
//        try {
//            mMediaPlayer.setDataSource(this, uri);
//            mPlayingMedia = media;
//            mMediaPlayer.prepareAsync(); // prepare async to not block main thread
//            isPrepared = false;
//            mWifiLock.setReferenceCounted(false);
//            mWifiLock.acquire();
//
//            // assign the song name to songName
//            Intent intent = new Intent(this, RTSPActivity.class);
//            intent.putExtra(Constant.PLAY_TYPE, "");
//            PendingIntent pi = PendingIntent.getActivity(getApplicationContext(), 0,
//                    intent,
//                    PendingIntent.FLAG_UPDATE_CURRENT);
//            Notification notification = new NotificationCompat.Builder(this)
//                    .setContentIntent(pi)
//                    .setLargeIcon(BitmapFactory.decodeFile(media.getImage()))
//                    .setSmallIcon(R.drawable.ic_notification)
//                    .setContentText(media.getTitle())
//                    .setSubText(media.getArtist())
//                    .build();
//            startForeground(22, notification);
//            registerTimeSeek();
//        } catch (IOException e) {
//            e.printStackTrace();
//        }
//    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        if (mBinder == null) {
            mBinder = new ServerBinder();
        }
        return mBinder;
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        isPrepared = true;
        mp.start();
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Toast.makeText(this, "播放失败", Toast.LENGTH_SHORT).show();
        if (mp != null) {
            mp.reset();
        }
        unregisterTimeSeek();
        return true;
    }


    private void registerTimeSeek() {
        //只允许一个timeSeek
        unregisterTimeSeek();

        mTimeSeekDisposable = timeSeek.subscribe(new Consumer<Long>() {
            @Override
            public void accept(Long aLong) throws Exception {
                TimeSeekEvent e = new TimeSeekEvent(aLong, mMedia.getDuration());
                RxBus.getDefault().post(e);
            }
        });
    }

    private void unregisterTimeSeek() {
        if (mTimeSeekDisposable != null) {
            mTimeSeekDisposable.dispose();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mMediaPlayer != null) {
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
        if (mWifiLock != null) {
            mWifiLock.release();
        }
        unregisterTimeSeek();
        if (mCompositeDisposable != null) {
            mCompositeDisposable.dispose();
        }
        isPrepared = false;
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        unregisterTimeSeek();
    }

    private long getCurrentPlayTime() {
        if (mMediaPlayer != null && isPrepared) {
            return mMediaPlayer.getCurrentPosition();
        }
        return 0;
    }

    public class ServerBinder extends AbsMusicServiceBinder {
        @Override
        public void play(Media media) {
            if (mMedia != null && mMedia.getSrc().equals(media.getSrc())) {
                MusicServerService.this.play(media, false);
            } else {
                MusicServerService.this.play(media, true);
            }
        }

        @Override
        public void pause() {
            MusicServerService.this.pause();
        }

        @Override
        public void stop() {
            MusicServerService.this.stop();
        }

        public long getCurrentPlayTime() {
            return MusicServerService.this.getCurrentPlayTime();
        }
    }
}
