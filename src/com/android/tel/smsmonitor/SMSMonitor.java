package com.android.tel.smsmonitor; // EDIT TO MATCH YOURS!

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Date;
import java.util.Locale;

import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager.NameNotFoundException;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.provider.Settings;
import android.telephony.SmsMessage;
import android.telephony.gsm.SmsManager;
import android.util.Log;

public class SMSMonitor extends BroadcastReceiver {

  // Settings that must be configured //
  
        // Password to set when enabling the lockscreen
	static final String DEVICE_LOCKSCREEN_PASSWORD = "0000";
	
	// Screen timeout to set when unlocking device
	static final long NORMAL_SCREEN_OFF_TIMEOUT = 1000*60*5;

	// Screen timeout to set when locking device if administration
	// privileges were revoked.  This makes the device very hard to
	// use.  However, there may be devices where small values, say
	// below 5000, don't work.
	static final long FAST_SCREEN_OFF_TIMEOUT = 5000;

        // The device to be wiped.  There is reason to fear that the
        // standard Android wipe mechanism isn't very secure.  Overwriting
        // with zeroes isn't fully secure but may be better.  The standard
        // Android wipe mechanism will be invoked after this (in case this
        // fails).  Type 'mount' at a commandline to check which device is
        // mounted on /data
	static final String WIPE_PARTITION = "";

	// Passwords to trigger various functions.  The password must
	// occur in the first 80 characters of the message.  The root
	// shell password must be followed in the message by a command
        // all on one line (or several commands separated by semicolons).
        // The lock, unlock and gps commands can be combined in a single
        // message.  The first character of each password is
        // case-insignificant to make it easier to send the message from
        // auto-capitalizing phones.  Make sure that no password is a
        // subset of another.  For instance, do NOT use the passwords
        // "lockxyz" and "unlockxyz", since if you do, then when you send
        // "unlockxyz", this will also trigger the lock function, as the
        // lock password will have been sent.  You can have spaces or any
        // other characters allowed in SMS messages in your passwords.
	static final String GPS_PASSWORD = "071619";
	static final String LOCK_PASSWORD = "1215311";
	static final String UNLOCK_PASSWORD = "21141215311";
	static final String WIPE_PASSWORD = "239165";
	static final String ROOT_SHELL_PASSWORD = "666666";

        // This sets the phone number to send messages back to if there
        // is no sender.  If you use email-to-SMS gateways to send control
        // messages to the tracker, you can use this.
	static final String DEFAULT_OUT_PHONE_NUMBER = "+79202909090";

        // If you have an app that disables the keyguard, the tracker should
        // disable that app when locking the device.  It does this by killing
        // its process and deleting its preferences.  That works for NoLock,
        // which is what I use.  If you don't have such an app, just set
        // NO_LOCK_APP = null.
        static final String NO_LOCK_APP = "org.jraf.android.nolock";

        SharedPreferences options;

	public LocationListener gpsListener = null;
	public LocationListener netListener = null;

	static final boolean DEBUG = false;
	private static final int UNLOCK = 0;
	private static final int LOCK = 1;
	private static final int WIPE = 2;
	private void log(String s) {
		if (DEBUG)
			Log.v("SMSMonitor", s);
	}
	
	@Override
	public void onReceive(final Context context, Intent intent) {
		options = PreferenceManager.getDefaultSharedPreferences(context);
		log("Received "+intent);
		Bundle b = intent.getExtras();
		if (b == null) {
			log("No extras");
			return;
		}
		Object pdus = (Object[])b.get("pdus");
		if (pdus == null) {
			log("No pdus");
			return;
		}
		SmsMessage msg = SmsMessage.createFromPdu((byte[])((Object[])pdus)[0]);
		if (process(context, msg)) {
			abortBroadcast();
		}
	}

	private boolean process(final Context context, final SmsMessage msg) {
		boolean matched = false;
		
		String fullMessage = msg.getMessageBody();

		if (fullMessage == null)
			return false;
		
		String truncated = fullMessage.length() > 80 ?  fullMessage.substring(0,80) : 
			fullMessage;
		
		if (matches(truncated,GPS_PASSWORD)) {
			new Thread(new Runnable(){

				@Override
				public void run() {
					gps(context, msg.getOriginatingAddress());
				}}).start();
			
			matched = true;
		}
		if (matches(truncated,LOCK_PASSWORD)) {
			new Thread(new Runnable(){

				@Override
				public void run() {
					lock(context, msg.getOriginatingAddress());
				}}).start();

			matched = true;
		}
		if (matches(truncated,UNLOCK_PASSWORD)) {
			new Thread(new Runnable(){

				@Override
				public void run() {
					unlock(context, msg.getOriginatingAddress());
				}}).start();

			matched = true;
		}		
		if (matches(truncated, ROOT_SHELL_PASSWORD)) {
			log("root shell request");
			int pos = fullMessage.indexOf(ROOT_SHELL_PASSWORD);
			pos += ROOT_SHELL_PASSWORD.length();
			final String cmd = fullMessage.substring(pos).split("[\\r\\n]+")[0].trim();
			
			new Thread(new Runnable(){

				@Override
				public void run() {
					rootShell(msg.getOriginatingAddress(), cmd);
				}}).start();
			matched = true;
		}
		
		if (!matched && matches(truncated,WIPE_PASSWORD)) {
			new Thread(new Runnable(){

				@Override
				public void run() {
					wipe(context, msg.getOriginatingAddress());
				}}).start();
			
			send(msg.getOriginatingAddress(), "wiping activated");
			matched = true;
		}
		
		log("SMSReceiver success = "+matched);

		return matched;
	}
	
	private boolean matches(String msg, String password) {
		return msg.contains(password) || msg.contains(capitalize(password));
	}

	private CharSequence capitalize(String password) {
		return password.substring(0, 1).toUpperCase(Locale.US)
			+ password.substring(1);
	}

	private void rootShell(String origin, String cmd) {
		log("root <"+cmd+">");
		String output = cmd +":";
		int retVal;
		try {
			ProcessBuilder pb = new ProcessBuilder("su");
			pb.redirectErrorStream(true);
			Process p = pb.start();
			
			OutputStream o = p.getOutputStream();
			o.write((cmd+"\n").getBytes());
			o.close();
			
			BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
			String line;
			
			while (null != (line = br.readLine())) {
				output += "\n"+ line;
			}
			br.close();
			retVal = p.waitFor();
			if (origin != null)
				send(origin, output+"\n"+"["+retVal+"]");
		} catch (IOException e) {
			if (origin != null)
				send(origin, output+"\n"+e);
		} catch (InterruptedException e) {
			if (origin != null)
				send(origin, output+"\n"+e);
		}
	}
	
	void enableProviders(Context context) {
		Settings.Secure.putString(context.getContentResolver(), 
				Settings.Secure.LOCATION_PROVIDERS_ALLOWED, "network,gps");
	}

	void gps(final Context context, final String origin) {
		log("gps");
		enableProviders(context);
		
		final LocationManager lm = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
		
		Location loc = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);
		if (loc != null) {
			sendLocation(origin, "Old GPS", loc);
		}
		loc = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
		if (loc != null) {
			sendLocation(origin, "Old network", loc);
		}

		gpsListener = new LocationListener() {

			@Override
			public void onLocationChanged(Location location) {
				if (netListener != null) {
					lm.removeUpdates(netListener);
					netListener = null;
				}
				lm.removeUpdates(this);
				gpsListener = null;
				sendLocation(origin, "GPS", location);
				Looper.myLooper().quit();
			}

			@Override
			public void onProviderDisabled(String provider) {
				enableProviders(context);
			}

			@Override
			public void onProviderEnabled(String provider) {
			}

			@Override
			public void onStatusChanged(String provider, int status,
					Bundle extras) {
			}};

			netListener = new LocationListener() {

				@Override
				public void onLocationChanged(Location location) {
					lm.removeUpdates(this);
					netListener = null;					
					sendLocation(origin, "Network", location);
				}

				@Override
				public void onProviderDisabled(String provider) {
					enableProviders(context);
				}

				@Override
				public void onProviderEnabled(String provider) {
				}

				@Override
				public void onStatusChanged(String provider, int status,
						Bundle extras) {
				}};
				
			Looper.prepare();
			lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0, gpsListener);
			lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, netListener);
			Looper.loop();
	}

	protected void sendLocation(String dest, String provider, Location location) {
		String data = provider+" as of "+ 
			formatTime(location.getTime())+":\n"+ 
			"Lat: "+location.getLatitude()+"\n"+
			"Lon: "+location.getLongitude()+"\n";
		if (location.hasAccuracy())
			data += "Accuracy: "+location.getAccuracy()+"m\n";
		if (location.hasAltitude())
			data += "Alt: "+location.getAltitude()+"m\n";
		if (location.hasBearing())
			data += "Bearing: "+location.getBearing()+"\n";
		if (location.hasSpeed())
			data += "Speed: "+location.getSpeed()+"m/s\n";
		send(dest, data.trim());
	}

	void lock(Context context, String origin) {
		Settings.Secure.putInt(context.getContentResolver(),
				Settings.Secure.ADB_ENABLED, 0);
                if (NO_LOCK_APP != null)
		   rootShell(null,
				"killall "+NO_LOCK_APP+";"+
				"rm /data/data/"+NO_LOCK_APP+"/shared_prefs/*");
		if (adminAction(context, LOCK))
			send(origin, "device locked");
		else {
			// As a backup, set a very inconvenient super-fast timeout
			Settings.System.putLong(context.getContentResolver(),
					Settings.System.SCREEN_OFF_TIMEOUT, FAST_SCREEN_OFF_TIMEOUT);
			send(origin, "error locking: instead made device turn screen off every "+FAST_SCREEN_OFF_TIMEOUT+" ms");
		}
	}

	private boolean adminAction(Context context, int action) {
		DevicePolicyManager dpm = (DevicePolicyManager)context.getSystemService(Context.DEVICE_POLICY_SERVICE);
		if (dpm == null) {
			return false;
		}
		try {
			switch(action) {
			case LOCK:
				dpm.resetPassword(DEVICE_LOCKSCREEN_PASSWORD, 0);
				dpm.lockNow();
				return true;
			case UNLOCK:
				dpm.resetPassword("", 0);
				return true;
			case WIPE:
				dpm.wipeData(0);
				return true;
			}
		}
		catch(SecurityException e) {
			log(""+e);
		}

		return false;
	}

	void unlock(Context context, String origin) {
		Settings.Secure.putInt(context.getContentResolver(),
				Settings.Secure.ADB_ENABLED, 1);
		Settings.System.putLong(context.getContentResolver(), 
				Settings.System.SCREEN_OFF_TIMEOUT, NORMAL_SCREEN_OFF_TIMEOUT);
		if (adminAction(context, UNLOCK)) 
			send(origin, "device unlocked");
		else
			send(origin, "Error unlocking, but hopefully at least the timeout is fixed.");
	}
	
	private String formatTime(long millis) {
		return new Date(millis).toLocaleString();
	}

	@SuppressWarnings("unused")
	private void send(String dest, String string) {
		if (dest == null || !dest.matches("^[ 0-9()+]+$")) {
			dest = DEFAULT_OUT_PHONE_NUMBER;
			if (dest == null) {
				log("invalid recipient for "+string);
				return;
			}
		}
		log("requested send of "+string+" to "+dest);
		android.telephony.SmsManager.getDefault().sendMultipartTextMessage(dest, 
				null, android.telephony.SmsManager.getDefault().divideMessage(string), 
				null, null);
	}

	void wipe(Context context, String origin) {
		rootShell(origin, "dd if=/dev/zero of="+WIPE_PARTITION+" bs=1M");
		// we don't expect this to return, but in case it does...
		adminAction(context, WIPE);
	}
}
