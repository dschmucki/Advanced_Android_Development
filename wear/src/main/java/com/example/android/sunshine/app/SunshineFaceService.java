/*
 * Copyright (C) 2014 The Android Open Source Project
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
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.Time;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineFaceService extends CanvasWatchFaceService {

    public static final String TAG = SunshineFaceService.class.getSimpleName();

    private static final Typeface LIGHT_TYPEFACE = Typeface.create("sans-serif-light", Typeface.NORMAL);
    private static final Typeface NORMAL_TYPEFACE = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update every 10 seconds.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(10);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    private int weatherId = 800;
    private String highTemp = "10°";
    private String lowTemp = "5°";

    @Override
    public Engine onCreateEngine() {
        Log.d(TAG, "onCreateEngine()");
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineFaceService.Engine> mWeakReference;

        public EngineHandler(SunshineFaceService.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineFaceService.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

        static final String DATA_PATH = "/weather";
        static final String DATA_WEATHER_ID = "weatherId";
        static final String DATA_MAX_TEMP = "highTemp";
        static final String DATA_MIN_TEMP = "lowTemp";


        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;

        Paint mTextPaintHours;
        Paint mTextPaintMinutes;
        Paint mTextPaintDate;
        Paint mTextPaintDateAmbient;
        Paint mTextPaintHigh;
        Paint mTextPaintLow;
        Paint mLinePaint;
        Paint mLinePaintAmbient;

        Rect mHoursBounds;
        Rect mColonsBounds;
        Rect mMinutesBounds;
        Rect mDateBounds;
        Rect mWeatherBitmapBounds;
        Rect mHighBounds;
        Rect mLowBounds;

        SimpleDateFormat mSimpleDateFormat;

        boolean mAmbient;
        Time mTime;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mTime.clear(intent.getStringExtra("time-zone"));
                mTime.setToNow();
            }
        };

        float mXOffsetHours;
        float mXOffsetMinutes;
        float mYOffsetTime;
        float mYOffsetDate;
        float mYOffsetTemp;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        private GoogleApiClient googleApiClient = new GoogleApiClient.Builder(SunshineFaceService.this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            Log.d(TAG, "onCreate()");

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            Resources resources = SunshineFaceService.this.getResources();
            mXOffsetHours = resources.getDimension(R.dimen.hours_x_offset);
            mXOffsetMinutes = resources.getDimension(R.dimen.minutes_x_offset);
            mYOffsetTime = resources.getDimension(R.dimen.time_y_offset);
            mYOffsetDate = resources.getDimension(R.dimen.date_y_offset);
            mYOffsetTemp = resources.getDimension(R.dimen.temp_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextPaintHours = createTextPaint(resources.getColor(R.color.white), NORMAL_TYPEFACE);
            mTextPaintMinutes = createTextPaint(resources.getColor(R.color.white), LIGHT_TYPEFACE);
            mTextPaintDate = createTextPaint(resources.getColor(R.color.light_blue), NORMAL_TYPEFACE);
            mTextPaintDateAmbient = createTextPaint(resources.getColor(R.color.white), NORMAL_TYPEFACE);
            mTextPaintHigh = createTextPaint(resources.getColor(R.color.white), LIGHT_TYPEFACE);
            mTextPaintLow = createTextPaint(resources.getColor(R.color.light_blue), LIGHT_TYPEFACE);
            mLinePaint = new Paint();
            mLinePaint.setColor(resources.getColor(R.color.light_blue));
            mLinePaintAmbient = new Paint();
            mLinePaintAmbient.setColor(resources.getColor(R.color.white));

            mHoursBounds = new Rect();
            mColonsBounds = new Rect();
            mMinutesBounds = new Rect();
            mDateBounds = new Rect();
            mWeatherBitmapBounds = new Rect();
            mHighBounds = new Rect();
            mLowBounds = new Rect();

            mTime = new Time();
            mSimpleDateFormat = new SimpleDateFormat("EEE, MMM d yyyy");
        }

        @Override
        public void onDestroy() {
            Log.d(TAG, "onDestroy()");
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            Log.d(TAG, "onVisibilityChanged");


            if (visible) {
                Log.d(TAG, "onVisibilityChanged true");
                googleApiClient.connect();
                Log.d(TAG, "googleApiClient.connect() called");
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mTime.clear(TimeZone.getDefault().getID());
                mTime.setToNow();
            } else {
                Log.d(TAG, "onVisibilityChanged false");
                unregisterReceiver();

                if (googleApiClient != null && googleApiClient.isConnected()) {
                    Log.d(TAG, "disconnecting GoogleApiClient");
                    Wearable.DataApi.removeListener(googleApiClient, this);
                    googleApiClient.disconnect();
                }
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineFaceService.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineFaceService.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            Log.d(TAG, "onApplyWindowInsets()");

            Resources resources = SunshineFaceService.this.getResources();

            mTextPaintHours.setTextSize(resources.getDimension(R.dimen.time_size));
            mTextPaintMinutes.setTextSize(resources.getDimension(R.dimen.time_size));
            mTextPaintDate.setTextSize(resources.getDimension(R.dimen.date_size));
            mTextPaintDateAmbient.setTextSize(resources.getDimension(R.dimen.date_size));
            mTextPaintHigh.setTextSize(resources.getDimension(R.dimen.temp_size));
            mTextPaintLow.setTextSize(resources.getDimension(R.dimen.temp_size));
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            Log.d(TAG, "onPropertiesChanged()");
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTextPaintHours.setAntiAlias(!inAmbientMode);
                    mTextPaintMinutes.setAntiAlias(!inAmbientMode);
                    mTextPaintDate.setAntiAlias(!inAmbientMode);
                    mTextPaintDateAmbient.setAntiAlias(!inAmbientMode);
                    mTextPaintHigh.setAntiAlias(!inAmbientMode);
                    mTextPaintLow.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            Log.d(TAG, "onDraw()");
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            mTime.setToNow();

            String hours = String.format("%d", mTime.hour);
            mTextPaintHours.getTextBounds(hours, 0, hours.length(), mHoursBounds);
            String colons = ":";
            mTextPaintHours.getTextBounds(colons, 0, 1, mColonsBounds);
            String minutes = String.format("%02d", mTime.minute);
            mTextPaintMinutes.getTextBounds(minutes, 0, minutes.length(), mMinutesBounds);
            String date = mSimpleDateFormat.format(new Date(mTime.toMillis(false))).toUpperCase();
            mTextPaintDate.getTextBounds(date, 0, date.length(), mDateBounds);
            mTextPaintDateAmbient.getTextBounds(date, 0, date.length(), mDateBounds);
            Bitmap weatherBitmap = loadBitmap();
            mTextPaintHigh.getTextBounds(highTemp, 0, highTemp.length(), mHighBounds);
            mTextPaintLow.getTextBounds(lowTemp, 0, lowTemp.length(), mLowBounds);

            Paint textPaintDate = mTextPaintDate;
            Paint linePaint = mLinePaint;

            if (isInAmbientMode()) {
                textPaintDate = mTextPaintDateAmbient;
                linePaint = mLinePaintAmbient;
            } else {
                canvas.drawBitmap(weatherBitmap, bounds.centerX() - weatherBitmap.getWidth() - 40, mYOffsetTemp - (weatherBitmap.getHeight() / 2) - (mHighBounds.height() / 2), null);
                canvas.drawText(lowTemp, bounds.centerX() + 40, mYOffsetTemp, mTextPaintLow);
            }

            canvas.drawText(hours, bounds.centerX() - mHoursBounds.width() - 7, mYOffsetTime, mTextPaintHours);
            canvas.drawText(colons, bounds.centerX() - mColonsBounds.width() / 2, mYOffsetTime, mTextPaintHours);
            canvas.drawText(minutes, bounds.centerX() + 13, mYOffsetTime, mTextPaintMinutes);
            canvas.drawText(date, bounds.centerX() - mDateBounds.width() / 2, mYOffsetDate, textPaintDate);

            canvas.drawLine(bounds.centerX() - 30, bounds.centerY() + 25, bounds.centerX() + 30, bounds.centerY() + 25, linePaint);

            canvas.drawText(highTemp, bounds.centerX() - mHighBounds.width() / 2, mYOffsetTemp, mTextPaintHigh);

        }

        private Bitmap loadBitmap() {
            Resources resources = SunshineFaceService.this.getResources();
            Drawable drawable = resources.getDrawable(getArtResourceForWeatherCondition(weatherId), null);
            return ((BitmapDrawable) drawable).getBitmap();
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }


        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {

            Log.d(TAG, "onDataChanged: " + dataEventBuffer.getCount());
            for (DataEvent dataEvent : dataEventBuffer) {
                DataItem dataItem = dataEvent.getDataItem();

                DataItem item = dataEvent.getDataItem();
                if (dataItem.getUri().getPath().equals(DATA_PATH)) {
                    processWeatherDataFor(item);
                }
            }
        }

        private void processWeatherDataFor(DataItem item) {
            Log.d(TAG, "processing weather data " + item.toString());

            DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();

            weatherId = dataMap.getInt(DATA_WEATHER_ID);
            highTemp = dataMap.getString(DATA_MAX_TEMP, "0");
            lowTemp = dataMap.getString(DATA_MIN_TEMP, "0");

        }

        @Override
        public void onConnected(Bundle connectionHint) {
            Log.d(TAG, "onConnected: " + connectionHint);
            Wearable.DataApi.addListener(googleApiClient, Engine.this);
        }

        @Override
        public void onConnectionSuspended(int cause) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionSuspended: " + cause);
            }
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult result) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionFailed: " + result);
            }
        }

    }

    /**
     * Helper method to provide the art resource id according to the weather condition id returned
     * by the OpenWeatherMap call.
     *
     * @param weatherId from OpenWeatherMap API response
     * @return resource id for the corresponding icon. -1 if no relation is found.
     */
    public static int getArtResourceForWeatherCondition(int weatherId) {
        // Based on weather code data found at:
        // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
        if (weatherId >= 200 && weatherId <= 232) {
            return R.drawable.ic_storm;
        } else if (weatherId >= 300 && weatherId <= 321) {
            return R.drawable.ic_light_rain;
        } else if (weatherId >= 500 && weatherId <= 504) {
            return R.drawable.ic_rain;
        } else if (weatherId == 511) {
            return R.drawable.ic_snow;
        } else if (weatherId >= 520 && weatherId <= 531) {
            return R.drawable.ic_rain;
        } else if (weatherId >= 600 && weatherId <= 622) {
            return R.drawable.ic_snow;
        } else if (weatherId >= 701 && weatherId <= 761) {
            return R.drawable.ic_fog;
        } else if (weatherId == 761 || weatherId == 781) {
            return R.drawable.ic_storm;
        } else if (weatherId == 800) {
            return R.drawable.ic_clear;
        } else if (weatherId == 801) {
            return R.drawable.ic_light_clouds;
        } else if (weatherId >= 802 && weatherId <= 804) {
            return R.drawable.ic_cloudy;
        }
        return -1;
    }
}
