package com.returnzero.radio;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import io.vov.vitamio.MediaPlayer;
import io.vov.vitamio.MediaPlayer.OnCompletionListener;
import io.vov.vitamio.MediaPlayer.OnErrorListener;
import io.vov.vitamio.MediaPlayer.OnPreparedListener;
import android.media.RemoteControlClient;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;

import com.returnzero.radio.R;

/**
 * Service that handles media playback. This is the Service through which we
 * perform all the media handling in our application. Upon initialization, it
 * starts a {@link MusicRetriever} to scan the user's media. Then, it waits for
 * Intents (which come from our main activity, {@link MainActivity}, which
 * signal the service to perform specific operations: Play, Pause, Rewind, Skip,
 * etc.
 */
public class RadioService extends Service implements OnPreparedListener,
		OnErrorListener, MusicFocusable {

	// The tag we put on debug messages
	final static String TAG = "RadioPlayer";

	// These are the Intent actions that we are prepared to handle. Notice that
	// the fact these
	// constants exist in our class is a mere convenience: what really defines
	// the actions our
	// service can handle are the <action> tags in the <intent-filters> tag for
	// our service in
	// AndroidManifest.xml.
	
	// The UPDATE_ME action is for mainactivity to request the status of player
	public static final String ACTION_TOGGLE_PLAYBACK = "com.returnzero.radio.player.action.TOGGLE_PLAYBACK";
	public static final String ACTION_PLAY = "com.returnzero.radio.player.action.PLAY";
	public static final String ACTION_PAUSE = "com.returnzero.radio.player.action.PAUSE";
	public static final String ACTION_STOP = "com.returnzero.radio.player.action.STOP";
	public static final String ACTION_UPDATEME = "com.returnzero.radio.player.action.UPDATE_ME";

	// The volume we set the media player to when we lose audio focus, but are
	// allowed to reduce
	// the volume instead of stopping playback.
	public static final float DUCK_VOLUME = 0.1f;

	// our media player
	MediaPlayer mPlayer = null;

	// our AudioFocusHelper object, if it's available (it's available on SDK
	// level >= 8)
	// If not available, this will be null. Always check for null before using!
	AudioFocusHelper mAudioFocusHelper = null;

	// indicates the state our service:
	enum State {
		Stopped, // media player is stopped and not prepared to play
		Preparing, // media player is preparing...
		Playing, // playback active (media player ready!). (but the media player
					// may actually be
					// paused in this state if we don't have audio focus. But we
					// stay in this state
					// so that we know we have to resume playback once we get
					// focus back)
		Paused // playback paused (media player ready!)
	};

	State mState = State.Stopped;

	enum PauseReason {
		UserRequest, // paused by user request
		FocusLoss, // paused because of audio focus loss
	};

	// why did we pause? (only relevant if mState == State.Paused)
	PauseReason mPauseReason = PauseReason.UserRequest;

	// do we have audio focus?
	enum AudioFocus {
		NoFocusNoDuck, // we don't have audio focus, and can't duck
		NoFocusCanDuck, // we don't have focus, but can play at a low volume
						// ("ducking")
		Focused // we have full audio focus
	}

	AudioFocus mAudioFocus = AudioFocus.NoFocusNoDuck;

	// whether the song we are playing is streaming from the network

	// Wifi lock that we hold when streaming files from the internet, in order
	// to prevent the
	// device from shutting off the Wifi radio
	WifiLock mWifiLock;

	Uri radioStationUri;
	String radioStationTitle;

	// The ID we use for the notification (the onscreen alert that appears at
	// the notification
	// area at the top of the screen as an icon -- and as text as well if the
	// user expands the
	// notification area).
	final int NOTIFICATION_ID = 1;

	// our RemoteControlClient object, which will use remote control APIs
	// available in
	// SDK level >= 14, if they're available.
	RemoteControlClientCompat mRemoteControlClientCompat;

	// Dummy album art we will pass to the remote control (if the APIs are
	// available).
	Bitmap mDummyAlbumArt;

	// The component name of MusicIntentReceiver, for use with media button and
	// remote control
	// APIs
	ComponentName mMediaButtonReceiverComponent;

	AudioManager mAudioManager;
	NotificationManager mNotificationManager;

	Notification mNotification = null;

	/**
	 * Makes sure the media player exists and has been reset. This will create
	 * the media player if needed, or reset the existing media player if one
	 * already exists.
	 */
	void createMediaPlayerIfNeeded() {
		if (mPlayer == null) {
			mPlayer = new MediaPlayer(this);

			// Make sure the media player will acquire a wake-lock while
			// playing. If we don't do
			// that, the CPU might go to sleep while the song is playing,
			// causing playback to stop.
			//
			// Remember that to use this, we have to declare the
			// android.permission.WAKE_LOCK
			// permission in AndroidManifest.xml.
			mPlayer.setWakeMode(getApplicationContext(),
					PowerManager.PARTIAL_WAKE_LOCK);

			// we want the media player to notify us when it's ready preparing,
			// and when it's done
			// playing:
			mPlayer.setOnPreparedListener(this);
			mPlayer.setOnErrorListener(this);
		} else
			mPlayer.reset();
	}

	@Override
	public void onCreate() {
		Log.i(TAG, "debug: Creating service");

		radioStationTitle = PersianReshape.reshape(getResources().getString(
				R.string.radio_javan));

		// Create the Wifi lock (this does not acquire the lock, this just
		// creates it)
		mWifiLock = ((WifiManager) getSystemService(Context.WIFI_SERVICE))
				.createWifiLock(WifiManager.WIFI_MODE_FULL, "mylock");

		mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
		mAudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

		// create the Audio Focus Helper, if the Audio Focus feature is
		// available (SDK 8 or above)
		if (android.os.Build.VERSION.SDK_INT >= 8)
			mAudioFocusHelper = new AudioFocusHelper(getApplicationContext(),
					this);
		else
			mAudioFocus = AudioFocus.Focused; // no focus feature, so we always
												// "have" audio focus

		mDummyAlbumArt = BitmapFactory.decodeResource(getResources(),
				R.drawable.dummy_album_art);

		mMediaButtonReceiverComponent = new ComponentName(this,
				MusicIntentReceiver.class);
	}

	/**
	 * Called when we receive an Intent. When we receive an intent sent to us
	 * via startService(), this is the method that gets called. So here we react
	 * appropriately depending on the Intent's action, which specifies what is
	 * being requested of us.
	 */
	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		Log.d("Service", "intentRecieved");
		String action = intent.getAction();
		if (action.equals(ACTION_TOGGLE_PLAYBACK))
			processTogglePlaybackRequest(intent);
		else if (action.equals(ACTION_PLAY))
			processPlayRequest(intent);
		else if (action.equals(ACTION_PAUSE))
			processPauseRequest();
		else if (action.equals(ACTION_STOP))
			processStopRequest();
		else if (action.equals(ACTION_UPDATEME))
			updateActivity();

		return START_NOT_STICKY; // Means we started the service, but don't want
									// it to
									// restart in case it's killed.
	}

	void processTogglePlaybackRequest(Intent intent) {
		if (mState == State.Paused || mState == State.Stopped) {
			processPlayRequest(intent);
		} else {
			processPauseRequest();
		}
	}

	void processPlayRequest(Intent intent) {
		Log.d("play", "proccessing play");
		tryToGetAudioFocus();
		radioStationUri = intent.getData();
		// actually play the song
		Log.d("getting from", radioStationUri.toString());

		if (mState == State.Stopped) {
			// If we're stopped, just go ahead to the next song and start
			// playing
			playRadio(radioStationUri.toString(), radioStationTitle);
		} else if (mState == State.Paused) {
			// If we're paused, just continue playback and restore the
			// 'foreground service' state.
			mState = State.Playing;
			updateActivity();
			setUpAsForeground(PersianReshape.reshape(getResources().getString(
					R.string.playing))
					+ ": " + radioStationTitle);
			configAndStartMediaPlayer();
		}

		if (mState == State.Playing) {
			// *****************************************************************
			//
			// HERE we should care about changing Stations
			// *****************************************************************
		}

		// Tell any remote controls that our playback state is 'playing'.
		if (mRemoteControlClientCompat != null) {
			mRemoteControlClientCompat
					.setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING);
		}
	}

	void processPauseRequest() {

		if (mState == State.Playing) {
			// Pause media player and cancel the 'foreground service' state.
			mState = State.Paused;
			updateActivity();
			mPlayer.pause();
			relaxResources(false); // while paused, we always retain the
									// MediaPlayer
			// do not give up audio focus
		}

		// Tell any remote controls that our playback state is 'paused'.
		if (mRemoteControlClientCompat != null) {
			mRemoteControlClientCompat
					.setPlaybackState(RemoteControlClient.PLAYSTATE_PAUSED);
		}
	}

	void processStopRequest() {
		processStopRequest(false);
	}

	void processStopRequest(boolean force) {
		if (mState == State.Playing || mState == State.Paused || force) {
			mState = State.Stopped;
			updateActivity();

			// let go of all resources...
			relaxResources(true);
			giveUpAudioFocus();

			// Tell any remote controls that our playback state is 'paused'.
			if (mRemoteControlClientCompat != null) {
				mRemoteControlClientCompat
						.setPlaybackState(RemoteControlClient.PLAYSTATE_STOPPED);
			}

			// service is no longer necessary. Will be started again if needed.
			stopSelf();
		}
	}

	/**
	 * Releases resources used by the service for playback. This includes the
	 * "foreground service" status and notification, the wake locks and possibly
	 * the MediaPlayer.
	 * 
	 * @param releaseMediaPlayer
	 *            Indicates whether the Media Player should also be released or
	 *            not
	 */
	void relaxResources(boolean releaseMediaPlayer) {
		// stop being a foreground service
		stopForeground(true);

		// stop and release the Media Player, if it's available
		if (releaseMediaPlayer && mPlayer != null) {
			mPlayer.reset();
			mPlayer.release();
			mPlayer = null;
		}

		// we can also release the Wifi lock, if we're holding it
		if (mWifiLock.isHeld())
			mWifiLock.release();
	}

	void giveUpAudioFocus() {
		if (mAudioFocus == AudioFocus.Focused && mAudioFocusHelper != null
				&& mAudioFocusHelper.abandonFocus())
			mAudioFocus = AudioFocus.NoFocusNoDuck;
	}

	/**
	 * Reconfigures MediaPlayer according to audio focus settings and
	 * starts/restarts it. This method starts/restarts the MediaPlayer
	 * respecting the current audio focus state. So if we have focus, it will
	 * play normally; if we don't have focus, it will either leave the
	 * MediaPlayer paused or set it to a low volume, depending on what is
	 * allowed by the current focus settings. This method assumes mPlayer !=
	 * null, so if you are calling it, you have to do so from a context where
	 * you are sure this is the case.
	 */
	void configAndStartMediaPlayer() {
		if (mAudioFocus == AudioFocus.NoFocusNoDuck) {
			// If we don't have audio focus and can't duck, we have to pause,
			// even if mState
			// is State.Playing. But we stay in the Playing state so that we
			// know we have to resume
			// playback once we get the focus back.
			if (mPlayer.isPlaying())
				mPlayer.pause();
			return;
		} else if (mAudioFocus == AudioFocus.NoFocusCanDuck)
			mPlayer.setVolume(DUCK_VOLUME, DUCK_VOLUME); // we'll be relatively
															// quiet
		else
			mPlayer.setVolume(1.0f, 1.0f); // we can be loud

		if (!mPlayer.isPlaying())
			mPlayer.start();
	}

	void tryToGetAudioFocus() {
		if (mAudioFocus != AudioFocus.Focused && mAudioFocusHelper != null
				&& mAudioFocusHelper.requestFocus())
			mAudioFocus = AudioFocus.Focused;
	}

	/**
	 * Starts playing the next song. If manualUrl is null, the next song will be
	 * randomly selected from our Media Retriever (that is, it will be a random
	 * song in the user's device). If manualUrl is non-null, then it specifies
	 * the URL or path to the song that will be played next.
	 */
	void playRadio(String url, String station) {
		mState = State.Stopped;
		relaxResources(false); // release everything except MediaPlayer

		try {
			if (url != null) {
				// set the source of the media player to a manual URL or path
				createMediaPlayerIfNeeded();
				mPlayer.setDataSource(url);
			}

			mState = State.Preparing;
			setUpAsForeground(PersianReshape.reshape(getResources().getString(
					R.string.loading))
					+ " " + station);

			// Use the media button APIs (if available) to register ourselves
			// for media button
			// events

			MediaButtonHelper.registerMediaButtonEventReceiverCompat(
					mAudioManager, mMediaButtonReceiverComponent);

			// Use the remote control APIs (if available) to set the playback
			// state

			if (mRemoteControlClientCompat == null) {
				Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
				intent.setComponent(mMediaButtonReceiverComponent);
				mRemoteControlClientCompat = new RemoteControlClientCompat(
						PendingIntent.getBroadcast(this /* context */, 0 /*
																		 * requestCode
																		 * ,
																		 * ignored
																		 */,
								intent /* intent */, 0 /* flags */));
				RemoteControlHelper.registerRemoteControlClient(mAudioManager,
						mRemoteControlClientCompat);
			}

			mRemoteControlClientCompat
					.setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING);

			mRemoteControlClientCompat
					.setTransportControlFlags(RemoteControlClient.FLAG_KEY_MEDIA_PLAY
							| RemoteControlClient.FLAG_KEY_MEDIA_PAUSE
							| RemoteControlClient.FLAG_KEY_MEDIA_NEXT
							| RemoteControlClient.FLAG_KEY_MEDIA_STOP);

			// Update the remote controls
			mRemoteControlClientCompat
					.editMetadata(true)
					.putString(MediaMetadataRetriever.METADATA_KEY_ARTIST, " ")
					.putString(MediaMetadataRetriever.METADATA_KEY_ALBUM, " ")
					.putString(MediaMetadataRetriever.METADATA_KEY_TITLE,
							station)
					.putLong(MediaMetadataRetriever.METADATA_KEY_DURATION, 0)
					// TODO: fetch real item artwork
					.putBitmap(
							RemoteControlClientCompat.MetadataEditorCompat.METADATA_KEY_ARTWORK,
							mDummyAlbumArt).apply();

			// starts preparing the media player in the background. When it's
			// done, it will call
			// our OnPreparedListener (that is, the onPrepared() method on this
			// class, since we set
			// the listener to 'this').
			//
			// Until the media player is prepared, we *cannot* call start() on
			// it!
			mPlayer.prepareAsync();

			// If we are streaming from the internet, we want to hold a Wifi
			// lock, which prevents
			// the Wifi radio from going to sleep while the song is playing. If,
			// on the other hand,
			// we are *not* streaming, we want to release the lock if we were
			// holding it before.

			mWifiLock.acquire();

		} catch (IOException ex) {
			Log.e("MusicService",
					"IOException playing next song: " + ex.getMessage());
			ex.printStackTrace();
		}
	}

	/** Called when media player is done preparing. */
	public void onPrepared(MediaPlayer player) {
		// The media player is done preparing. That means we can start playing!
		mState = State.Playing;
		updateActivity();
		updateNotification(PersianReshape.reshape(getResources().getString(
				R.string.playing))
				+ ": " + radioStationTitle);
		configAndStartMediaPlayer();
	}

	/** Updates the notification. */
	void updateNotification(String text) {
		PendingIntent pi = PendingIntent.getActivity(getApplicationContext(),
				0, new Intent(getApplicationContext(), MainActivity.class),
				PendingIntent.FLAG_UPDATE_CURRENT);
		mNotification.setLatestEventInfo(getApplicationContext(), "IRIB Radio",
				text, pi);
		mNotificationManager.notify(NOTIFICATION_ID, mNotification);
	}

	/**
	 * Configures service as a foreground service. A foreground service is a
	 * service that's doing something the user is actively aware of (such as
	 * playing music), and must appear to the user as a notification. That's why
	 * we create the notification here.
	 */
	void setUpAsForeground(String text) {
		PendingIntent pi = PendingIntent.getActivity(getApplicationContext(),
				0, new Intent(getApplicationContext(), MainActivity.class),
				PendingIntent.FLAG_UPDATE_CURRENT);
		mNotification = new Notification();
		mNotification.tickerText = text;
		mNotification.icon = R.drawable.ic_radio;
		mNotification.flags |= Notification.FLAG_ONGOING_EVENT;
		mNotification.setLatestEventInfo(getApplicationContext(),
				PersianReshape
						.reshape(getResources().getString(R.string.radio)),
				text, pi);
		startForeground(NOTIFICATION_ID, mNotification);
	}

	/**
	 * Called when there's an error playing media. When this happens, the media
	 * player goes to the Error state. We warn the user about the error and
	 * reset the media player.
	 */
	public boolean onError(MediaPlayer mp, int what, int extra) {
		Toast.makeText(getApplicationContext(),
				"Media player error! Resetting.", Toast.LENGTH_SHORT).show();
		Log.e(TAG,
				"Error: what=" + String.valueOf(what) + ", extra="
						+ String.valueOf(extra));

		mState = State.Stopped;
		updateActivity();

		relaxResources(true);
		giveUpAudioFocus();
		return true; // true indicates we handled the error
	}

	public void onGainedAudioFocus() {
		Toast.makeText(getApplicationContext(), "gained audio focus.",
				Toast.LENGTH_SHORT).show();
		mAudioFocus = AudioFocus.Focused;

		// restart media player with new focus settings
		if (mState == State.Playing)
			configAndStartMediaPlayer();
	}

	public void onLostAudioFocus(boolean canDuck) {
		Toast.makeText(getApplicationContext(),
				"lost audio focus." + (canDuck ? "can duck" : "no duck"),
				Toast.LENGTH_SHORT).show();
		mAudioFocus = canDuck ? AudioFocus.NoFocusCanDuck
				: AudioFocus.NoFocusNoDuck;

		// start/restart/pause media player with new focus settings
		if (mPlayer != null && mPlayer.isPlaying())
			configAndStartMediaPlayer();
	}

	@Override
	public void onDestroy() {
		// Service is being killed, so make sure we release our resources
		mState = State.Stopped;
		updateActivity();
		relaxResources(true);
		giveUpAudioFocus();
	}

	@Override
	public IBinder onBind(Intent arg0) {
		return null;
	}

	// this method updates activity when the status of the player changes

	public void updateActivity() {
		if (mState == State.Playing) {
			Intent intent = new Intent(MainActivity.STATUS_PLAYING);
			sendBroadcast(intent);
		} else {
			Intent intent = new Intent(MainActivity.STATUS_PAUSED);
			sendBroadcast(intent);
		}
	}
}
