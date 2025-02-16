/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.systemui.statusbar.phone;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.ContentResolver;
import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.os.UserHandle;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.internal.util.rr.ColorHelper;
import com.android.internal.util.rr.DeviceUtils;
import com.android.internal.util.darkkat.StatusBarColorHelper;
import com.android.systemui.BatteryLevelTextView;
import com.android.systemui.BatteryMeterView;
import com.android.systemui.Interpolators;
import com.android.systemui.R;
import com.android.systemui.SystemUIFactory;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.SignalClusterView;
import com.android.systemui.statusbar.StatusBarIconView;
import com.android.systemui.tuner.TunerService;
import com.android.systemui.tuner.TunerService.Tunable;

import java.io.PrintWriter;
import java.util.ArrayList;

/**
 * Controls everything regarding the icons in the status bar and on Keyguard, including, but not
 * limited to: notification icons, signal cluster, additional status icons, and clock in the status
 * bar.
 */
public class StatusBarIconController extends StatusBarIconList implements Tunable {

    public static final long DEFAULT_TINT_ANIMATION_DURATION = 120;
    public static final String ICON_BLACKLIST = "icon_blacklist";
    public static final int DEFAULT_ICON_TINT = Color.WHITE;
    private static final int STATUS_ICONS_COLOR         = 0;
    private static final int NETWORK_SIGNAL_COLOR       = 1;
    private static final int NO_SIM_COLOR               = 2;
    private static final int AIRPLANE_MODE_COLOR        = 3;
    private int mColorToChange;
    private int mStatusIconsColor;
    private int mStatusIconsColorOld;
    private int mStatusIconsColorTint;
    private int mNetworkSignalColor;
    private int mNetworkSignalColorOld;
    private int mNetworkSignalColorTint;
    private int mNoSimColor;
    private int mNoSimColorOld;
    private int mNoSimColorTint;
    private int mAirplaneModeColor;
    private int mAirplaneModeColorOld;
    private int mAirplaneModeColorTint;
    private int mNotificationIconsColor;
    private int mNotificationIconsColorTint;

    private Context mContext;
    private PhoneStatusBar mPhoneStatusBar;
    private DemoStatusIcons mDemoStatusIcons;

    private LinearLayout mSystemIconArea;
    private LinearLayout mStatusIcons;
    private SignalClusterView mSignalCluster;
    private LinearLayout mStatusIconsKeyguard;

    private NotificationIconAreaController mNotificationIconAreaController;
    private View mNotificationIconAreaInner;

    private BatteryMeterView mBatteryMeterView;
    private BatteryMeterView mBatteryMeterViewKeyguard;
    private ClockController mClockController;
    private View mCenterClockLayout;

    private TextView mCarrierLabel;
    private int mCarrierLabelMode;

	private TextView mWeather;
	private TextView mWeatherLeft;
    private ImageView mWeatherImageView;
    private ImageView mLeftWeatherImageView;
    private int mStatusBarWeatherEnabled;

    private int mIconSize;
    private int mIconHPadding;

    private int mIconTint = DEFAULT_ICON_TINT;
    private float mDarkIntensity;
    private final Rect mTintArea = new Rect();
    private static final Rect sTmpRect = new Rect();
    private static final int[] sTmpInt2 = new int[2];

    private boolean mTransitionPending;
    private boolean mTintChangePending;
    private float mPendingDarkIntensity;
    private ValueAnimator mTintAnimator;

    private int mDarkModeIconColorSingleTone;
    private int mLightModeIconColorSingleTone;

    private final Handler mHandler;
    private boolean mTransitionDeferring;
    private long mTransitionDeferringStartTime;
    private long mTransitionDeferringDuration;
	private Animator mColorTransitionAnimator;

    private final ArraySet<String> mIconBlacklist = new ArraySet<>();

    private BatteryLevelTextView mBatteryLevelView;
	public Boolean mColorSwitch = false;

    private final Runnable mTransitionDeferringDoneRunnable = new Runnable() {
        @Override
        public void run() {
            mTransitionDeferring = false;
        }
    };

    public StatusBarIconController(Context context, View statusBar, View keyguardStatusBar,
            PhoneStatusBar phoneStatusBar) {
        super(context.getResources().getStringArray(
                com.android.internal.R.array.config_statusBarIcons));
        mContext = context;
        mPhoneStatusBar = phoneStatusBar;
        mSystemIconArea = (LinearLayout) statusBar.findViewById(R.id.system_icon_area);
        mStatusIcons = (LinearLayout) statusBar.findViewById(R.id.statusIcons);
        mSignalCluster = (SignalClusterView) statusBar.findViewById(R.id.signal_cluster);

        mNotificationIconAreaController = SystemUIFactory.getInstance()
                .createNotificationIconAreaController(context, phoneStatusBar);
        mNotificationIconAreaInner =
                mNotificationIconAreaController.getNotificationInnerAreaView();
		mColorSwitch =  Settings.System.getInt(mContext.getContentResolver(),
				 Settings.System.STATUSBAR_COLOR_SWITCH, 0) == 1;

        ViewGroup notificationIconArea =
                (ViewGroup) statusBar.findViewById(R.id.notification_icon_area);
        notificationIconArea.addView(mNotificationIconAreaInner);

        mStatusIconsKeyguard = (LinearLayout) keyguardStatusBar.findViewById(R.id.statusIcons);

        mBatteryMeterView = (BatteryMeterView) statusBar.findViewById(R.id.battery);
        mBatteryMeterViewKeyguard = (BatteryMeterView) keyguardStatusBar.findViewById(R.id.battery);
        scaleBatteryMeterViews(context);

        mDarkModeIconColorSingleTone = context.getColor(R.color.dark_mode_icon_color_single_tone);
        mLightModeIconColorSingleTone = context.getColor(R.color.light_mode_icon_color_single_tone);
        mCarrierLabel = (TextView) statusBar.findViewById(R.id.statusbar_carrier_text);
		mWeather = (TextView) statusBar.findViewById(R.id.weather_temp);
		mWeatherLeft = (TextView) statusBar.findViewById(R.id.left_weather_temp);
        mWeatherImageView = (ImageView) statusBar.findViewById(R.id.weather_image);
        mLeftWeatherImageView = (ImageView) statusBar.findViewById(R.id.left_weather_image);
        mHandler = new Handler();
        mClockController = new ClockController(statusBar, mNotificationIconAreaController, mHandler);
        mCenterClockLayout = statusBar.findViewById(R.id.center_clock_layout);
        SettingsObserver settingsObserver = new SettingsObserver(mHandler);
        settingsObserver.observe();
        carrierLabelVisibility();
        loadDimens();

        mBatteryLevelView = (BatteryLevelTextView) statusBar.findViewById(R.id.battery_level);
		
		setUpCustomColors();
		mColorTransitionAnimator = createColorTransitionAnimator(0, 1);
        TunerService.get(mContext).addTunable(this, ICON_BLACKLIST);
    }
	
    private void setUpCustomColors() {
		mColorSwitch =  Settings.System.getInt(mContext.getContentResolver(),
				 Settings.System.STATUSBAR_COLOR_SWITCH, 0) == 1;
		if (mColorSwitch) {
        mStatusIconsColor = StatusBarColorHelper.getStatusIconsColor(mContext);
        mStatusIconsColorOld = mStatusIconsColor;
        mStatusIconsColorTint = mStatusIconsColor;
        mNetworkSignalColor = StatusBarColorHelper.getNetworkSignalColor(mContext);
        mNetworkSignalColorOld = mNetworkSignalColor;
        mNetworkSignalColorTint = mNetworkSignalColor;
        mNoSimColor = StatusBarColorHelper.getNoSimColor(mContext);
        mNoSimColorOld = mNoSimColor;
        mNoSimColorTint = mNoSimColor;
        mAirplaneModeColor = StatusBarColorHelper.getAirplaneModeColor(mContext);
        mAirplaneModeColorOld = mAirplaneModeColor;
        mAirplaneModeColorTint = mAirplaneModeColor;
        mNotificationIconsColor = StatusBarColorHelper.getNotificationIconsColor(mContext);
        mNotificationIconsColorTint = mNotificationIconsColor;
	}
     }

    public void setSignalCluster(SignalClusterView signalCluster) {
        mSignalCluster = signalCluster;
    }

    /**
     * Looks up the scale factor for status bar icons and scales the battery view by that amount.
     */
    private void scaleBatteryMeterViews(Context context) {
        Resources res = context.getResources();
        TypedValue typedValue = new TypedValue();

        res.getValue(R.dimen.status_bar_icon_scale_factor, typedValue, true);
        float iconScaleFactor = typedValue.getFloat();

        int batteryHeight = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_height);
        int batteryWidth = res.getDimensionPixelSize(R.dimen.status_bar_battery_icon_width);
        int marginBottom = res.getDimensionPixelSize(R.dimen.battery_margin_bottom);
        // Set the start margin of the battery view instead of
        // the end padding of the signal cluster to prevent
        // excess padding when the battery view is hidden
        int marginStart = res.getDimensionPixelSize(R.dimen.signal_cluster_battery_padding);

        LinearLayout.LayoutParams scaledLayoutParams = new LinearLayout.LayoutParams(
                (int) (batteryWidth * iconScaleFactor), (int) (batteryHeight * iconScaleFactor));

        scaledLayoutParams.setMarginsRelative(marginStart, 0, 0, marginBottom);

        mBatteryMeterView.setLayoutParams(scaledLayoutParams);
        mBatteryMeterViewKeyguard.setLayoutParams(scaledLayoutParams);
    }

    @Override
    public void onTuningChanged(String key, String newValue) {
        if (!ICON_BLACKLIST.equals(key)) {
            return;
        }
        mIconBlacklist.clear();
        mIconBlacklist.addAll(getIconBlacklist(newValue));

        boolean showClock = !mIconBlacklist.remove("clock");
        mClockController.setVisibility(showClock);

        ArrayList<StatusBarIconView> views = new ArrayList<StatusBarIconView>();
        // Get all the current views.
        for (int i = 0; i < mStatusIcons.getChildCount(); i++) {
            views.add((StatusBarIconView) mStatusIcons.getChildAt(i));
        }
        // Remove all the icons.
        for (int i = views.size() - 1; i >= 0; i--) {
            removeIcon(views.get(i).getSlot());
        }
        // Add them all back
        for (int i = 0; i < views.size(); i++) {
            setIcon(views.get(i).getSlot(), views.get(i).getStatusBarIcon());
        }
    }
    private void loadDimens() {
        mIconSize = mContext.getResources().getDimensionPixelSize(
                com.android.internal.R.dimen.status_bar_icon_size);
        mIconHPadding = mContext.getResources().getDimensionPixelSize(
                R.dimen.status_bar_icon_padding);
        mClockController.updateFontSize();
    }

    private void addSystemIcon(int index, StatusBarIcon icon) {
		mColorSwitch =  Settings.System.getInt(mContext.getContentResolver(),
				 Settings.System.STATUSBAR_COLOR_SWITCH, 0) == 1;
        String slot = getSlot(index);
        int viewIndex = getViewIndex(index);
        boolean blocked = mIconBlacklist.contains(slot);
        StatusBarIconView view = new StatusBarIconView(mContext, slot, null, blocked);
        view.set(icon);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, mIconSize);
        lp.setMargins(mIconHPadding, 0, mIconHPadding, 0);
        mStatusIcons.addView(view, viewIndex, lp);

        view = new StatusBarIconView(mContext, slot, null, blocked);
        view.set(icon);
        mStatusIconsKeyguard.addView(view, viewIndex, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, mIconSize));
        applyIconTint();
		if (mColorSwitch) {
		updateStatusIconsKeyguardColor();
	}
    }

    public void setIcon(String slot, int resourceId, CharSequence contentDescription) {
        int index = getSlotIndex(slot);
        StatusBarIcon icon = getIcon(index);
        if (icon == null) {
            icon = new StatusBarIcon(UserHandle.SYSTEM, mContext.getPackageName(),
                    Icon.createWithResource(mContext, resourceId), 0, 0, contentDescription);
            setIcon(slot, icon);
        } else {
            icon.icon = Icon.createWithResource(mContext, resourceId);
            icon.contentDescription = contentDescription;
            handleSet(index, icon);
        }
    }

    public void setExternalIcon(String slot) {
        int viewIndex = getViewIndex(getSlotIndex(slot));
        int height = mContext.getResources().getDimensionPixelSize(
                R.dimen.status_bar_icon_drawing_size);
        ImageView imageView = (ImageView) mStatusIcons.getChildAt(viewIndex);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setAdjustViewBounds(true);
        setHeightAndCenter(imageView, height);
        imageView = (ImageView) mStatusIconsKeyguard.getChildAt(viewIndex);
        imageView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        imageView.setAdjustViewBounds(true);
        setHeightAndCenter(imageView, height);
    }

    private void setHeightAndCenter(ImageView imageView, int height) {
        ViewGroup.LayoutParams params = imageView.getLayoutParams();
        params.height = height;
        if (params instanceof LinearLayout.LayoutParams) {
            ((LinearLayout.LayoutParams) params).gravity = Gravity.CENTER_VERTICAL;
        }
        imageView.setLayoutParams(params);
    }

    public void setIcon(String slot, StatusBarIcon icon) {
        setIcon(getSlotIndex(slot), icon);
    }

    public void removeIcon(String slot) {
        int index = getSlotIndex(slot);
        removeIcon(index);
    }

    public void setIconVisibility(String slot, boolean visibility) {
        int index = getSlotIndex(slot);
        StatusBarIcon icon = getIcon(index);
        if (icon == null || icon.visible == visibility) {
            return;
        }
        icon.visible = visibility;
        handleSet(index, icon);
    }

    @Override
    public void removeIcon(int index) {
        if (getIcon(index) == null) {
            return;
        }
        super.removeIcon(index);
        int viewIndex = getViewIndex(index);
        mStatusIcons.removeViewAt(viewIndex);
        mStatusIconsKeyguard.removeViewAt(viewIndex);
    }

    @Override
    public void setIcon(int index, StatusBarIcon icon) {
        if (icon == null) {
            removeIcon(index);
            return;
        }
        boolean isNew = getIcon(index) == null;
        super.setIcon(index, icon);
        if (isNew) {
            addSystemIcon(index, icon);
        } else {
            handleSet(index, icon);
        }
    }

    private void handleSet(int index, StatusBarIcon icon) {
		mColorSwitch =  Settings.System.getInt(mContext.getContentResolver(),
				 Settings.System.STATUSBAR_COLOR_SWITCH, 0) == 1;
        int viewIndex = getViewIndex(index);
        StatusBarIconView view = (StatusBarIconView) mStatusIcons.getChildAt(viewIndex);
        view.set(icon);
        view = (StatusBarIconView) mStatusIconsKeyguard.getChildAt(viewIndex);
        view.set(icon);
        applyIconTint();
		if(mColorSwitch) {
		updateStatusIconsKeyguardColor();
	}
    }

    public void updateNotificationIcons(NotificationData notificationData) {
        mNotificationIconAreaController.updateNotificationIcons(notificationData);
        carrierLabelVisibility();
    }

    public void hideSystemIconArea(boolean animate) {
        animateHide(mSystemIconArea, animate);
        animateHide(mCenterClockLayout, animate);
        if (Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_SHOW_CARRIER,  0,
                UserHandle.USER_CURRENT) == 2) {
        animateHide(mCarrierLabel,animate);
        }

        if (Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_SHOW_WEATHER_TEMP, 0,
                UserHandle.USER_CURRENT) == 0) {
        return;
        }
        if (Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_WEATHER_TEMP_STYLE, 0,
                UserHandle.USER_CURRENT) == 1) {
                if (mStatusBarWeatherEnabled == 1
                            || mStatusBarWeatherEnabled == 2
                            || mStatusBarWeatherEnabled == 5) {
               animateHide(mLeftWeatherImageView,animate);
               }
               if (mStatusBarWeatherEnabled == 0 || mStatusBarWeatherEnabled == 5) {
                   return;
               } else {
                  animateHide(mWeatherLeft,animate);
               }
        }

    }

    public void showSystemIconArea(boolean animate) {
        animateShow(mSystemIconArea, animate);
        animateShow(mCenterClockLayout, animate);
        if (Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_SHOW_CARRIER,  0,
                UserHandle.USER_CURRENT) == 2) {
        animateShow(mCarrierLabel,animate);
        }
        if (Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_SHOW_WEATHER_TEMP, 0,
                UserHandle.USER_CURRENT) == 0) {
        return;
        }
        if (Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_WEATHER_TEMP_STYLE, 0,
                UserHandle.USER_CURRENT) == 1) {
                if (mStatusBarWeatherEnabled == 1
                            || mStatusBarWeatherEnabled == 2
                            || mStatusBarWeatherEnabled == 5) {
                animateShow(mLeftWeatherImageView,animate);
                }
               if (mStatusBarWeatherEnabled == 0 || mStatusBarWeatherEnabled == 5) {
                   return;
               } else {
                  animateShow(mWeatherLeft,animate);
               }
        }
    }

    public void hideNotificationIconArea(boolean animate) {
        animateHide(mNotificationIconAreaInner, animate);
        animateHide(mCenterClockLayout, animate);
    }

    public void showNotificationIconArea(boolean animate) {
        animateShow(mNotificationIconAreaInner, animate);
        animateShow(mCenterClockLayout, animate);
    }

    public void setClockVisibility(boolean visible) {
        mClockController.setVisibility(visible);
    }

    public void dump(PrintWriter pw) {
        int N = mStatusIcons.getChildCount();
        pw.println("  icon views: " + N);
        for (int i=0; i<N; i++) {
            StatusBarIconView ic = (StatusBarIconView) mStatusIcons.getChildAt(i);
            pw.println("    [" + i + "] icon=" + ic);
        }
        super.dump(pw);
    }

    public void dispatchDemoCommand(String command, Bundle args) {
        if (mDemoStatusIcons == null) {
            mDemoStatusIcons = new DemoStatusIcons(mStatusIcons, mIconSize);
        }
        mDemoStatusIcons.dispatchDemoCommand(command, args);
    }

    /**
     * Hides a view.
     */
    private void animateHide(final View v, boolean animate) {
        v.animate().cancel();
        if (!animate) {
            v.setAlpha(0f);
            v.setVisibility(View.INVISIBLE);
            return;
        }
        v.animate()
                .alpha(0f)
                .setDuration(160)
                .setStartDelay(0)
                .setInterpolator(Interpolators.ALPHA_OUT)
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        v.setVisibility(View.INVISIBLE);
                    }
                });
    }

    /**
     * Shows a view, and synchronizes the animation with Keyguard exit animations, if applicable.
     */
    private void animateShow(View v, boolean animate) {
        v.animate().cancel();
        v.setVisibility(View.VISIBLE);
        if (!animate) {
            v.setAlpha(1f);
            return;
        }
        v.animate()
                .alpha(1f)
                .setDuration(320)
                .setInterpolator(Interpolators.ALPHA_IN)
                .setStartDelay(50)

                // We need to clean up any pending end action from animateHide if we call
                // both hide and show in the same frame before the animation actually gets started.
                // cancel() doesn't really remove the end action.
                .withEndAction(null);

        // Synchronize the motion with the Keyguard fading if necessary.
        if (mPhoneStatusBar.isKeyguardFadingAway()) {
            v.animate()
                    .setDuration(mPhoneStatusBar.getKeyguardFadingAwayDuration())
                    .setInterpolator(Interpolators.LINEAR_OUT_SLOW_IN)
                    .setStartDelay(mPhoneStatusBar.getKeyguardFadingAwayDelay())
                    .start();
        }
    }

    /**
     * Sets the dark area so {@link #setIconsDark} only affects the icons in the specified area.
     *
     * @param darkArea the area in which icons should change it's tint, in logical screen
     *                 coordinates
     */
    public void setIconsDarkArea(Rect darkArea) {
        if (darkArea == null && mTintArea.isEmpty()) {
            return;
        }
        if (darkArea == null) {
            mTintArea.setEmpty();
        } else {
            mTintArea.set(darkArea);
        }
        applyIconTint();
        mNotificationIconAreaController.setTintArea(darkArea);
    }

    public void setIconsDark(boolean dark, boolean animate) {
        if (!animate) {
            setIconTintInternal(dark ? 1.0f : 0.0f);
        } else if (mTransitionPending) {
            deferIconTintChange(dark ? 1.0f : 0.0f);
        } else if (mTransitionDeferring) {
            animateIconTint(dark ? 1.0f : 0.0f,
                    Math.max(0, mTransitionDeferringStartTime - SystemClock.uptimeMillis()),
                    mTransitionDeferringDuration);
        } else {
            animateIconTint(dark ? 1.0f : 0.0f, 0 /* delay */, DEFAULT_TINT_ANIMATION_DURATION);
        }
    }

    private void animateIconTint(float targetDarkIntensity, long delay,
            long duration) {
        if (mTintAnimator != null) {
            mTintAnimator.cancel();
        }
        if (mDarkIntensity == targetDarkIntensity) {
            return;
        }
        mTintAnimator = ValueAnimator.ofFloat(mDarkIntensity, targetDarkIntensity);
        mTintAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                setIconTintInternal((Float) animation.getAnimatedValue());
            }
        });
        mTintAnimator.setDuration(duration);
        mTintAnimator.setStartDelay(delay);
        mTintAnimator.setInterpolator(Interpolators.FAST_OUT_SLOW_IN);
        mTintAnimator.start();
    }

    private void setIconTintInternal(float darkIntensity) {
        mDarkIntensity = darkIntensity;
		mColorSwitch =  Settings.System.getInt(mContext.getContentResolver(),
				 Settings.System.STATUSBAR_COLOR_SWITCH, 0) == 1;
		mNotificationIconAreaController.setIconTint(mIconTint);
		if (mColorSwitch) {
        mStatusIconsColorTint = (int) ArgbEvaluator.getInstance().evaluate(darkIntensity,
                mStatusIconsColor, StatusBarColorHelper.getStatusIconsColorDark(mContext));
        mNetworkSignalColorTint = (int) ArgbEvaluator.getInstance().evaluate(darkIntensity,
                mNetworkSignalColor, StatusBarColorHelper.getNetworkSignalColorDark(mContext));
        mNoSimColorTint = (int) ArgbEvaluator.getInstance().evaluate(darkIntensity,
                mNoSimColor, StatusBarColorHelper.getNoSimColorDark(mContext));
        mAirplaneModeColorTint = (int) ArgbEvaluator.getInstance().evaluate(darkIntensity,
                mAirplaneModeColor, StatusBarColorHelper.getAirplaneModeColorDark(mContext));
        mNotificationIconsColorTint = (int) ArgbEvaluator.getInstance().evaluate(darkIntensity,
                mNotificationIconsColor, StatusBarColorHelper.getNotificationIconsColorDark(mContext));
	} else {
        mIconTint = (int) ArgbEvaluator.getInstance().evaluate(darkIntensity,
                mLightModeIconColorSingleTone, mDarkModeIconColorSingleTone);
	}
		
        applyIconTint();
    }

    private void deferIconTintChange(float darkIntensity) {
        if (mTintChangePending && darkIntensity == mPendingDarkIntensity) {
            return;
        }
        mTintChangePending = true;
        mPendingDarkIntensity = darkIntensity;
    }

    /**
     * @return the tint to apply to {@param view} depending on the desired tint {@param color} and
     *         the screen {@param tintArea} in which to apply that tint
     */
    public static int getTint(Rect tintArea, View view, int color) {
        if (isInArea(tintArea, view)) {
            return color;
        } else {
            return DEFAULT_ICON_TINT;
        }
    }

    /**
     * @return the dark intensity to apply to {@param view} depending on the desired dark
     *         {@param intensity} and the screen {@param tintArea} in which to apply that intensity
     */
    public static float getDarkIntensity(Rect tintArea, View view, float intensity) {
        if (isInArea(tintArea, view)) {
            return intensity;
        } else {
            return 0f;
        }
    }

    /**
     * @return true if more than half of the {@param view} area are in {@param area}, false
     *         otherwise
     */
    private static boolean isInArea(Rect area, View view) {
        if (area.isEmpty()) {
            return true;
        }
        sTmpRect.set(area);
        view.getLocationOnScreen(sTmpInt2);
        int left = sTmpInt2[0];

        int intersectStart = Math.max(left, area.left);
        int intersectEnd = Math.min(left + view.getWidth(), area.right);
        int intersectAmount = Math.max(0, intersectEnd - intersectStart);

        boolean coversFullStatusBar = area.top <= 0;
        boolean majorityOfWidth = 2 * intersectAmount > view.getWidth();
        return majorityOfWidth && coversFullStatusBar;
    }

    public void applyIconTint() {
	mColorSwitch =  Settings.System.getInt(mContext.getContentResolver(),
				 Settings.System.STATUSBAR_COLOR_SWITCH, 0) == 1;	
        for (int i = 0; i < mStatusIcons.getChildCount(); i++) {
            StatusBarIconView v = (StatusBarIconView) mStatusIcons.getChildAt(i);
			if (mColorSwitch) {
            v.setImageTintList(ColorStateList.valueOf(getTint(mTintArea, v, mStatusIconsColorTint)));
			 } else {
				 v.setImageTintList(ColorStateList.valueOf(getTint(mTintArea, v, mIconTint)));
			 }
        }
		if (mColorSwitch) {
        mSignalCluster.setIconTint(mNetworkSignalColorTint, mNoSimColorTint, mAirplaneModeColorTint, mDarkIntensity, mTintArea);
	} else {
		mSignalCluster.setIconStockTint(mIconTint, mDarkIntensity, mTintArea);
        mBatteryMeterView.setDarkIntensity(
                isInArea(mTintArea, mBatteryMeterView) ? mDarkIntensity : 0);
        mClockController.setTextColor(mTintArea, mIconTint);
		
        //mBatteryLevelView.setTextColor(getTint(mTintArea, mBatteryLevelView, mIconTint));
	}
		
        if (Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_CARRIER_COLOR,
                mContext.getResources().getColor(R.color.status_bar_clock_color),
                UserHandle.USER_CURRENT) == mContext.getResources().
                getColor(R.color.status_bar_clock_color)) {
        mCarrierLabel.setTextColor(mIconTint);
        }
		
        if (Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_WEATHER_IMAGE_COLOR, 0xFFFFFFFF,
                UserHandle.USER_CURRENT) == 0xFFFFFFFF) {
        mWeatherImageView.setImageTintList(ColorStateList.valueOf(mIconTint));
        mLeftWeatherImageView.setImageTintList(ColorStateList.valueOf(mIconTint));
        }
		
        if (Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_WEATHER_COLOR, 0xFFFFFFFF,
                UserHandle.USER_CURRENT) == 0xFFFFFFFF) {
        mWeather.setTextColor(mIconTint);
		mWeatherLeft.setTextColor(mIconTint);
		}
        mPhoneStatusBar.setTickerTint(mIconTint);
    }

    public void appTransitionPending() {
        mTransitionPending = true;
    }

    public void appTransitionCancelled() {
        if (mTransitionPending && mTintChangePending) {
            mTintChangePending = false;
            animateIconTint(mPendingDarkIntensity, 0 /* delay */, DEFAULT_TINT_ANIMATION_DURATION);
        }
        mTransitionPending = false;
    }

    public void appTransitionStarting(long startTime, long duration) {
        if (mTransitionPending && mTintChangePending) {
            mTintChangePending = false;
            animateIconTint(mPendingDarkIntensity,
                    Math.max(0, startTime - SystemClock.uptimeMillis()),
                    duration);

        } else if (mTransitionPending) {

            // If we don't have a pending tint change yet, the change might come in the future until
            // startTime is reached.
            mTransitionDeferring = true;
            mTransitionDeferringStartTime = startTime;
            mTransitionDeferringDuration = duration;
            mHandler.removeCallbacks(mTransitionDeferringDoneRunnable);
            mHandler.postAtTime(mTransitionDeferringDoneRunnable, startTime);
        }
        mTransitionPending = false;
    }

    public static ArraySet<String> getIconBlacklist(String blackListStr) {
        ArraySet<String> ret = new ArraySet<String>();
        if (blackListStr == null) {
            blackListStr = "rotate,headset";
        }
        String[] blacklist = blackListStr.split(",");
        for (String slot : blacklist) {
            if (!TextUtils.isEmpty(slot)) {
                ret.add(slot);
            }
        }
        return ret;
    }

    public void onDensityOrFontScaleChanged() {
        loadDimens();
        mNotificationIconAreaController.onDensityOrFontScaleChanged(mContext);
        updateClock();
        for (int i = 0; i < mStatusIcons.getChildCount(); i++) {
            View child = mStatusIcons.getChildAt(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, mIconSize);
            lp.setMargins(mIconHPadding, 0, mIconHPadding, 0);
            child.setLayoutParams(lp);
        }
        for (int i = 0; i < mStatusIconsKeyguard.getChildCount(); i++) {
            View child = mStatusIconsKeyguard.getChildAt(i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, mIconSize);
            child.setLayoutParams(lp);
        }
        scaleBatteryMeterViews(mContext);
    }

    private void updateClock() {
        mClockController.updateFontSize();
        mClockController.setPaddingRelative(
                mContext.getResources().getDimensionPixelSize(
                        R.dimen.status_bar_clock_starting_padding),
                0,
                mContext.getResources().getDimensionPixelSize(
                        R.dimen.status_bar_clock_end_padding),
                0);
    }
	
    public void carrierLabelVisibility() {
        final ContentResolver resolver = mContext.getContentResolver();

        mCarrierLabelMode = Settings.System.getIntForUser(resolver,
                Settings.System.STATUS_BAR_SHOW_CARRIER, 1, UserHandle.USER_CURRENT);

        boolean mUserDisabledStatusbarCarrier = false;

        if (mCarrierLabelMode == 0 || mCarrierLabelMode == 1) {
            mUserDisabledStatusbarCarrier = true;
        }

        boolean hideCarrier = Settings.System.getInt(resolver,
                Settings.System.HIDE_CARRIER_MAX_SWITCH, 0) == 1;

        int maxAllowedIcons = Settings.System.getInt(resolver,
                Settings.System.HIDE_CARRIER_MAX_NOTIFICATION, 1);

        boolean forceHideByNumberOfIcons = false;
        int currentVisibleNotificationIcons = 0;

        if (mNotificationIconAreaController != null) {
            currentVisibleNotificationIcons = mNotificationIconAreaController.getNotificationIconsCount();
        }

        if (mCarrierLabelMode == 2 || mCarrierLabelMode == 3) {
            if (hideCarrier && currentVisibleNotificationIcons >= maxAllowedIcons) {
               forceHideByNumberOfIcons = true;
            }
        }

        if (mCarrierLabel != null) {
            if (!forceHideByNumberOfIcons && !mUserDisabledStatusbarCarrier ) {
               mCarrierLabel.setVisibility(View.VISIBLE);
            } else {
               mCarrierLabel.setVisibility(View.GONE);
            }
        }
    }
	
    private ValueAnimator createColorTransitionAnimator(float start, float end) {
        ValueAnimator animator = ValueAnimator.ofFloat(start, end);

        animator.setDuration(500);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener(){
            @Override public void onAnimationUpdate(ValueAnimator animation) {
                float position = animation.getAnimatedFraction();
                int blendedFrame;
                int blended;
                if (mColorToChange == NETWORK_SIGNAL_COLOR) {
                    blended = ColorHelper.getBlendColor(
                            mNetworkSignalColorOld, mNetworkSignalColor, position);
                    mSignalCluster.applyNetworkSignalTint(blended);
                } else if (mColorToChange == NO_SIM_COLOR) {
                    blended = ColorHelper.getBlendColor(
                            mNoSimColorOld, mNoSimColor, position);
                    mSignalCluster.applyNoSimTint(blended);
                } else if (mColorToChange == AIRPLANE_MODE_COLOR) {
                    blended = ColorHelper.getBlendColor(
                            mAirplaneModeColorOld, mAirplaneModeColor, position);
                    mSignalCluster.applyAirplaneModeTint(blended);
                 } else if (mColorToChange == STATUS_ICONS_COLOR) {
                    blended = ColorHelper.getBlendColor(
                            mStatusIconsColorOld, mStatusIconsColor, position);
                    for (int i = 0; i < mStatusIcons.getChildCount(); i++) {
                        StatusBarIconView v = (StatusBarIconView) mStatusIcons.getChildAt(i);
                        v.setImageTintList(ColorStateList.valueOf(blended));
                    }
                }
            }
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mColorToChange == NETWORK_SIGNAL_COLOR) {
                    mNetworkSignalColorOld = mNetworkSignalColor;
                    mNetworkSignalColorTint = mNetworkSignalColor;
                } else if (mColorToChange == NO_SIM_COLOR) {
                    mNoSimColorOld = mNoSimColor;
                    mNoSimColorTint = mNoSimColor;
                } else if (mColorToChange == AIRPLANE_MODE_COLOR) {
                    mAirplaneModeColorOld = mAirplaneModeColor;
                    mAirplaneModeColorTint = mAirplaneModeColor;
                } else if (mColorToChange == STATUS_ICONS_COLOR) {
                    mStatusIconsColorOld = mStatusIconsColor;
                    mStatusIconsColorTint = mStatusIconsColor;
                }
            }
        });
        return animator;
    }

    public int getCurrentVisibleNotificationIcons() {
        return mNotificationIconAreaController.getNotificationIconsCount();
    }

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
    }

    void observe() {
         ContentResolver resolver = mContext.getContentResolver();
         resolver.registerContentObserver(Settings.System
                 .getUriFor(Settings.System.HIDE_CARRIER_MAX_SWITCH),
                 false, this, UserHandle.USER_CURRENT);
         resolver.registerContentObserver(Settings.System
                 .getUriFor(Settings.System.HIDE_CARRIER_MAX_NOTIFICATION),
                 false, this, UserHandle.USER_CURRENT);
         resolver.registerContentObserver(Settings.System
                 .getUriFor(Settings.System.STATUS_BAR_SHOW_WEATHER_TEMP),
                 false, this, UserHandle.USER_CURRENT);
         update();
    }

    @Override
    public void onChange(boolean selfChange, Uri uri) {
        super.onChange(selfChange, uri);

        if (uri.equals(Settings.System.getUriFor(
            Settings.System.HIDE_CARRIER_MAX_SWITCH))
            || uri.equals(Settings.System.getUriFor(
            Settings.System.HIDE_CARRIER_MAX_NOTIFICATION))) {
            carrierLabelVisibility();
            }
        }

	public void update() {
    mStatusBarWeatherEnabled = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.STATUS_BAR_SHOW_WEATHER_TEMP, 0,
                UserHandle.USER_CURRENT);
        }
    }
	
    public void updateStatusIconsColor() {
		mColorSwitch =  Settings.System.getInt(mContext.getContentResolver(),
				 Settings.System.STATUSBAR_COLOR_SWITCH, 0) == 1;
		if(mColorSwitch) {
        mStatusIconsColor = StatusBarColorHelper.getStatusIconsColor(mContext);
        if (mStatusIcons.getChildCount() > 0) {
            mColorToChange = STATUS_ICONS_COLOR;
            mColorTransitionAnimator.start();
        } else {
            mStatusIconsColorOld = mStatusIconsColor;
            mStatusIconsColorTint = mStatusIconsColor;
        }
	} else {
      	applyIconTint();
	}
    }

    public void updateStatusIconsKeyguardColor() {
		mColorSwitch =  Settings.System.getInt(mContext.getContentResolver(),
				 Settings.System.STATUSBAR_COLOR_SWITCH, 0) == 1;
		if(mColorSwitch) {
        if (mStatusIconsKeyguard.getChildCount() > 0) {
            for (int index = 0; index < mStatusIconsKeyguard.getChildCount(); index++) {
                StatusBarIconView v = (StatusBarIconView) mStatusIconsKeyguard.getChildAt(index);
                v.setImageTintList(ColorStateList.valueOf(mStatusIconsColor));
            }
        }
	} else {
	applyIconTint();
	}
    }


    public void updateNetworkIconColors() {
	mColorSwitch =  Settings.System.getInt(mContext.getContentResolver(),
				 Settings.System.STATUSBAR_COLOR_SWITCH, 0) == 1;
	if (mColorSwitch) {
        mNetworkSignalColor = StatusBarColorHelper.getNetworkSignalColor(mContext);
        mNoSimColor = StatusBarColorHelper.getNoSimColor(mContext);
        mAirplaneModeColor = StatusBarColorHelper.getAirplaneModeColor(mContext);
        mNetworkSignalColorOld = mNetworkSignalColor;
        mNoSimColorOld = mNoSimColor;
        mAirplaneModeColorOld = mAirplaneModeColor;
        mNetworkSignalColorTint = mNetworkSignalColor;
        mNoSimColorTint = mNoSimColor;
        mAirplaneModeColorTint = mAirplaneModeColor;

        mSignalCluster.setIconTint(mNetworkSignalColor, mNoSimColor, mAirplaneModeColor, mDarkIntensity, mTintArea);
		}
    }

    public void updateNetworkSignalColor() {
        mNetworkSignalColor = StatusBarColorHelper.getNetworkSignalColor(mContext);
        mColorToChange = NETWORK_SIGNAL_COLOR;
        mColorTransitionAnimator.start();
    }

    public void updateNoSimColor() {
        mNoSimColor = StatusBarColorHelper.getNoSimColor(mContext);
        mColorToChange = NO_SIM_COLOR;
        mColorTransitionAnimator.start();
    }

    public void updateAirplaneModeColor() {
        mAirplaneModeColor = StatusBarColorHelper.getAirplaneModeColor(mContext);
        mColorToChange = AIRPLANE_MODE_COLOR;
        mColorTransitionAnimator.start();
    }

	/*
    public void updateNotificationIconsColor() {
        mNotificationIconsColor = StatusBarColorHelper.getNotificationIconsColor(mContext);
        mNotificationIconsColorTint = mNotificationIconsColor;
        for (int i = 0; i < mNotificationIcons.getChildCount(); i++) {
            StatusBarIconView v = (StatusBarIconView) mNotificationIcons.getChildAt(i);
            boolean isPreL = Boolean.TRUE.equals(v.getTag(R.id.icon_is_pre_L));
            boolean colorize = !isPreL || isGrayscale(v);
            if (colorize) {
                v.setImageTintList(ColorStateList.valueOf(mNotificationIconsColor));
            }
        }
        mMoreIcon.setImageTintList(ColorStateList.valueOf(mNotificationIconsColor));
    }
		*/
}
