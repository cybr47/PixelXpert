package sh.siava.pixelxpert.modpacks.android;

import static de.robv.android.xposed.XposedBridge.hookAllMethods;
import static de.robv.android.xposed.XposedBridge.hookMethod;
import static de.robv.android.xposed.XposedBridge.log;
import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.findClass;
import static de.robv.android.xposed.XposedHelpers.findMethodExact;
import static de.robv.android.xposed.XposedHelpers.getObjectField;
import static sh.siava.pixelxpert.modpacks.XPrefs.Xprefs;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.view.KeyEvent;
import android.view.ViewConfiguration;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import sh.siava.pixelxpert.modpacks.Constants;
import sh.siava.pixelxpert.modpacks.XposedModPack;
import sh.siava.pixelxpert.modpacks.utils.SystemUtils;

@SuppressWarnings("RedundantThrows")
public class ScreenOffKeys extends XposedModPack {
	public static final String listenPackage = Constants.SYSTEM_FRAMEWORK_PACKAGE;
	public static final int LONG_PRESS_POWER_SHUT_OFF_NO_CONFIRM = 3;
	private static boolean replaceAssistantWithTorch = false;
	private static boolean holdVolumeToSkip = false;
	private long wakeTime = 0;
	//    private boolean isVolumeLongPress = false;
	private boolean isVolDown = false;

	public ScreenOffKeys(Context context) {
		super(context);
	}

	@Override
	public void updatePrefs(String... Key) {
		holdVolumeToSkip = Xprefs.getBoolean("holdVolumeToSkip", false);
		replaceAssistantWithTorch = Xprefs.getBoolean("replaceAssistantwithTorch", false);

		//make sure camera manager is ready and registered flashlight events
		if(replaceAssistantWithTorch) //noinspection ResultOfMethodCallIgnored
			SystemUtils.CameraManager();
	}

	@Override
	public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpParam) throws Throwable {
		Class<?> PhoneWindowManagerClass;
		Method powerLongPressMethod;
		Method interceptKeyBeforeQueueingMethod;

		try {
			PhoneWindowManagerClass = findClass("com.android.server.policy.PhoneWindowManager", lpParam.classLoader);

			powerLongPressMethod = findMethodExact(PhoneWindowManagerClass, "powerLongPress", long.class);
			interceptKeyBeforeQueueingMethod = findMethodExact(PhoneWindowManagerClass, "interceptKeyBeforeQueueing", KeyEvent.class, int.class);

			Runnable mVolumeLongPress = () -> {
				try {
					Intent keyIntent = new Intent(Intent.ACTION_MEDIA_BUTTON, null);
					KeyEvent keyEvent = new KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), KeyEvent.ACTION_DOWN, (isVolDown) ? KeyEvent.KEYCODE_MEDIA_PREVIOUS : KeyEvent.KEYCODE_MEDIA_NEXT, 0);
					keyIntent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
					SystemUtils.AudioManager().dispatchMediaKeyEvent(keyEvent);

					keyEvent = KeyEvent.changeAction(keyEvent, KeyEvent.ACTION_UP);
					keyIntent.putExtra(Intent.EXTRA_KEY_EVENT, keyEvent);
					SystemUtils.AudioManager().dispatchMediaKeyEvent(keyEvent);

					SystemUtils.vibrate(VibrationEffect.EFFECT_TICK, VibrationAttributes.USAGE_ACCESSIBILITY);
				} catch (Throwable ignored) {
				}
			};

			hookMethod(interceptKeyBeforeQueueingMethod, new XC_MethodHook() {
				@Override
				protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
					if (!holdVolumeToSkip) return;
					try {
						Handler mHandler = (Handler) getObjectField(param.thisObject, "mHandler");

						KeyEvent e = (KeyEvent) param.args[0];
						int Keycode = e.getKeyCode();

						switch (e.getAction()) {
							case KeyEvent.ACTION_UP:
								if (mHandler.hasCallbacks(mVolumeLongPress)) {
									SystemUtils.AudioManager().adjustStreamVolume(AudioManager.STREAM_MUSIC, Keycode == KeyEvent.KEYCODE_VOLUME_DOWN ? AudioManager.ADJUST_LOWER : AudioManager.ADJUST_RAISE, 0);
									mHandler.removeCallbacks(mVolumeLongPress);
								}
								return;
							case KeyEvent.ACTION_DOWN:
								if (!SystemUtils.PowerManager().isInteractive() && (Keycode == KeyEvent.KEYCODE_VOLUME_DOWN || Keycode == KeyEvent.KEYCODE_VOLUME_UP) && SystemUtils.AudioManager().isMusicActive()) {
									isVolDown = (Keycode == KeyEvent.KEYCODE_VOLUME_DOWN);
									mHandler.postDelayed(mVolumeLongPress, ViewConfiguration.getLongPressTimeout());
									param.setResult(0);
								}
						}
					} catch (Throwable ignored) {
					}
				}
			});

			hookAllMethods(PhoneWindowManagerClass, "startedWakingUp", new XC_MethodHook() {
				@Override
				protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
					if (!replaceAssistantWithTorch) return;
					int r = (int) param.args[param.args.length-1];

					if (r == 1) {
						wakeTime = SystemClock.uptimeMillis();
					}
				}
			});


			hookMethod(powerLongPressMethod, new XC_MethodHook() {
				@Override
				protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
					boolean screenIsOn = SystemClock.uptimeMillis() - wakeTime > 1000;
					if (!replaceAssistantWithTorch
							|| (screenIsOn
								&& !SystemUtils.isFlashOn()))
						return;

					try {
						if ((int) callMethod(param.thisObject, "getResolvedLongPressOnPowerBehavior")
								== LONG_PRESS_POWER_SHUT_OFF_NO_CONFIRM) // this is a force shutdown event. never play with it
						{
							return;
						}

						SystemUtils.toggleFlash();

						SystemUtils.vibrate(VibrationEffect.EFFECT_TICK, VibrationAttributes.USAGE_ACCESSIBILITY);

						param.setResult(null);

						if(!screenIsOn)
							callMethod(SystemUtils.PowerManager(), "goToSleep", SystemClock.uptimeMillis());
					} catch (Throwable T) {
						log(T);
					}
				}
			});
		} catch (Throwable t) {
			log(t);
		}
	}

	@Override
	public boolean listensTo(String packageName) {
		return listenPackage.equals(packageName);
	}

}
