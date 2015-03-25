This is public domain software.


Root Tracker is source code for a super-simple Android 2.2+ device tracking system that responds to SMS messages
with embedded control passwords. Root Tracker is designed to be installed on a rooted device in /system/app so
it can survive a hard reset and so that it can have the requisite permissions for enabling GPS.

Root Tracker has no user interface, all the better for hiding it from a thief, and the whole code is designed
to be simple enough that you can easily verify that there are no back doors.

The code must be customized for you, as passwords and other configuration items are hard coded, and you should
change the package name and other details to make it sound like a system package, in order to even better hide
the package from a thief. Plus, I expect that different users will have different requirements.

By default, the package is com.android.tel.smsmonitor with name "SMSMonitor", but you should rename it.  
The class that responds to SMS requests is in com.android.tel.smsmonitor.SMSMonitor.  This class needs 
to be edited to set passwords, etc.  Note that the passwords shouldn't be used by you for anything else, 
as they are hidden merely by the obscurity of the package. But you are, of course, free to add public key 
encryption or hashing to get around this.

There is also a class, by default named com.android.tel.smsmonitor.AdminReceiver, which will appear in your
system settings list of Device Administrators.  You should rename that class as well for obscurity.  To use Root Tracker's
screen lock and unlock features (and a backup method of wiping), you should enable that class in the Device
Administrators list.  This is a vulnerability for the app if there is no lockscreen.  As a workaround, the
lock code sets the screen timeout to a very small number if Device Administrator status is denied, making it
very difficult for a casual user to use the device.  You need to experiment with what the smallest screen
timeout that your device accepts is.  On my device, 500ms actually works, but on some devices small numbers
may be ignored.

The screen lock code also disables adb and disables the NoLock app, if installed, which would block the
lockscreen.  If you have some other screenlock disabler, you will need to edit the lock method to disable
that.  This is yet another reason why this isn't designed for the casual user.