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
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class MyWatchFace extends CanvasWatchFaceService
{
    private static final String TAG=MyWatchFace.class.getSimpleName();

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    private static final Typeface BOLD_TYPEFACE=
            Typeface.create(Typeface.SANS_SERIF,Typeface.BOLD);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private static class EngineHandler extends Handler {
        private final WeakReference<MyWatchFace.Engine> mWeakReference;

        public EngineHandler(MyWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            MyWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                }
            }
        }
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener, GoogleApiClient.ConnectionCallbacks,GoogleApiClient.OnConnectionFailedListener
    {
        private static final String WEATHER_PATH = "/weather";
        private static final String KEY_TIME="time";
        private static final String KEY_HIGH = "high";
        private static final String KEY_LOW = "low";
        private static final String KEY_WEATHER_ID = "weatherId";

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        Calendar mCalendar;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        GoogleApiClient client=new GoogleApiClient.Builder(MyWatchFace.this)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .addApi(Wearable.API)
                .build();

        boolean mRegisteredTimeZoneReceiver = false;

        Paint mBackgroundPaint;
        Paint mTextTimeHoursPaint;
        Paint mTextTimeMinutesPaint;
        Paint mTextDatePaint;
        Paint mTextTempHighPaint;
        Paint mTextTempLowPaint;

        Paint mTextTimeHoursAmbientPaint;
        Paint mTextTimeMinutesAmbientPaint;
        Paint mTextTempHighAmbientPaint;
        Paint mTextTempLowAmbientPaint;

        Bitmap mWeatherIcon;
        String mWeatherHigh;
        String mWeatherLow;

        boolean mAmbient;

        float mTimeTopOffset;
        float mDateTopOffset;
        float mLineTopOffset;
        float mTempTopOffset;

        float mXOffset;
        float mYOffset;
;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(MyWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());

            Resources resources = MyWatchFace.this.getResources();
            mTimeTopOffset=resources.getDimension(R.dimen.digital_time_top_offset);

            mXOffset=resources.getDimension(R.dimen.digital_x_offset);
            mYOffset=resources.getDimension(R.dimen.digital_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTextTimeHoursPaint=createTextPaint(resources.getColor(R.color.digital_text),BOLD_TYPEFACE);
            mTextTimeMinutesPaint=createTextPaint(resources.getColor(R.color.digital_text),NORMAL_TYPEFACE);
            mTextDatePaint=createTextPaint(resources.getColor(R.color.digital_text),NORMAL_TYPEFACE);
            mTextTempHighPaint=createTextPaint(resources.getColor(R.color.digital_text),BOLD_TYPEFACE);
            mTextTempLowPaint=createTextPaint(resources.getColor(R.color.digital_text),NORMAL_TYPEFACE);

            mTextTimeHoursAmbientPaint=createTextPaint(resources.getColor(R.color.digital_text),BOLD_TYPEFACE);
            mTextTimeMinutesAmbientPaint=createTextPaint(resources.getColor(R.color.digital_text),NORMAL_TYPEFACE);
            mTextTempHighAmbientPaint=createTextPaint(resources.getColor(R.color.digital_text),BOLD_TYPEFACE);
            mTextTempLowAmbientPaint=createTextPaint(resources.getColor(R.color.digital_text),NORMAL_TYPEFACE);

            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor,Typeface typeface)
        {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible)
            {
                client.connect();
                registerReceiver();
                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
            else
            {
                unregisterReceiver();
                if(client!=null && client.isConnected())
                {
                    Wearable.DataApi.removeListener(client,this);
                    client.disconnect();
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
            MyWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            MyWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = MyWatchFace.this.getResources();
            boolean isRound = insets.isRound();

            mDateTopOffset=resources.getDimension(isRound?
                    R.dimen.digital_date_top_offset_round:R.dimen.digital_date_top_offset);
            mLineTopOffset=resources.getDimension(isRound?
                    R.dimen.digital_line_top_offset_round:R.dimen.digital_line_top_offset);
            mTempTopOffset=resources.getDimension(isRound?
                    R.dimen.digital_temp_top_offset_round:R.dimen.digital_temp_top_offset);

            float timeTextSize=resources.getDimension(isRound?
                    R.dimen.digital_time_text_size_round:R.dimen.digital_time_text_size);
            float dateTextSize=resources.getDimension(isRound?
                    R.dimen.digital_date_text_size_round:R.dimen.digital_date_text_size);
            float tempTextSize=resources.getDimension(isRound?
                    R.dimen.digital_temp_text_size_round:R.dimen.digital_temp_text_size);

            mTextTimeHoursPaint.setTextSize(timeTextSize);
            mTextTimeMinutesPaint.setTextSize(timeTextSize);
            mTextDatePaint.setTextSize(dateTextSize);
            mTextTempHighPaint.setTextSize(tempTextSize);
            mTextTempLowPaint.setTextSize(tempTextSize);

            mTextTimeHoursAmbientPaint.setTextSize(timeTextSize);
            mTextTimeMinutesAmbientPaint.setTextSize(timeTextSize);
            mTextTempHighAmbientPaint.setTextSize(tempTextSize);
            mTextTempLowAmbientPaint.setTextSize(tempTextSize);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
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
                    mTextTimeHoursPaint.setAntiAlias(!inAmbientMode);
                    mTextTimeHoursPaint.setAntiAlias(!inAmbientMode);
                    mTextTimeMinutesPaint.setAntiAlias(!inAmbientMode);
                    mTextDatePaint.setAntiAlias(!inAmbientMode);
                    mTextTempHighPaint.setAntiAlias(!inAmbientMode);
                    mTextTempLowPaint.setAntiAlias(!inAmbientMode);

                    mTextTimeHoursAmbientPaint.setAntiAlias(!inAmbientMode);
                    mTextTimeMinutesAmbientPaint.setAntiAlias(!inAmbientMode);
                    mTextTempHighAmbientPaint.setAntiAlias(!inAmbientMode);
                    mTextTempLowAmbientPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }
            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM in ambient mode or H:MM:SS in interactive mode.
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            boolean is24Hour= DateFormat.is24HourFormat(MyWatchFace.this);
            int minute=mCalendar.get(Calendar.MINUTE);
            int am_pm=mCalendar.get(Calendar.AM_PM);

            String hourText;
            String minuteText;
            int hour;
            if(is24Hour)
                hour=mCalendar.get(Calendar.HOUR_OF_DAY);
            else
            {
                hour=mCalendar.get(Calendar.HOUR);
                if(hour==0)
                    hour=12;
            }

            hourText=String.format("%02d:",hour);
            minuteText=String.format("%02d",minute);
            String amPmText=String.format(Utility.getAmPmString(getResources(),am_pm));

            float hourTextLen=mTextTimeHoursPaint.measureText(hourText);
            float minuteTextLen=mTextTimeMinutesPaint.measureText(minuteText);
            float xOffsetTime=(hourTextLen+minuteTextLen)/2;

            if(!is24Hour)
                xOffsetTime+=(mTextTimeMinutesPaint.measureText(amPmText)/2);

            float xOffsetTimeFromCenter=bounds.centerX()-xOffsetTime;

            if(mAmbient)
            {
                canvas.drawText(hourText,xOffsetTimeFromCenter,mTimeTopOffset,mTextTimeHoursAmbientPaint);
                canvas.drawText(minuteText,xOffsetTimeFromCenter+hourTextLen,mTimeTopOffset,mTextTimeMinutesAmbientPaint);
                if(!is24Hour)
                    canvas.drawText(amPmText,xOffsetTimeFromCenter+hourTextLen+minuteTextLen,mTimeTopOffset,mTextTimeMinutesAmbientPaint);
            }
            else
            {
                canvas.drawText(hourText,xOffsetTimeFromCenter,mTimeTopOffset,mTextTimeHoursPaint);
                canvas.drawText(minuteText,xOffsetTimeFromCenter+hourTextLen,mTimeTopOffset,mTextTimeMinutesPaint);
                if(!is24Hour)
                    canvas.drawText(amPmText,xOffsetTimeFromCenter+hourTextLen+minuteTextLen,mTimeTopOffset,mTextTimeMinutesPaint);
            }


            Resources resources=getResources();

            if(!mAmbient)
            {
                //Drawing the day and date only in interactive mode.
                String dayOfTheWeek=Utility.getDayOfWeekString(resources,mCalendar.get(Calendar.DAY_OF_WEEK));
                String monthOfTheYear=Utility.getMonthOfYearString(resources,mCalendar.get(Calendar.MONTH));

                int dayOfMonth=mCalendar.get(Calendar.DAY_OF_MONTH);
                int year=mCalendar.get(Calendar.YEAR);

                String dayDateText=String.format("%s, %s %d %d",dayOfTheWeek,monthOfTheYear,dayOfMonth,year);
                float xOffsetDate=mTextDatePaint.measureText(dayDateText)/2;
                canvas.drawText(dayDateText,bounds.centerX()-xOffsetDate,mDateTopOffset,mTextDatePaint);
            }

            //Drawing high and low temp if we have it.
            if(mWeatherHigh!=null && mWeatherLow!=null && mWeatherIcon!=null)
            {
                canvas.drawLine(bounds.centerX()-20,mLineTopOffset,bounds.centerX()+20,mLineTopOffset,mTextDatePaint);

                float highTempLen;
                float lowTempLen;
                float xOffsetTemp;

                if(mAmbient)
                {
                    highTempLen=mTextTempHighAmbientPaint.measureText(mWeatherHigh);
                    lowTempLen=mTextTempLowAmbientPaint.measureText(mWeatherLow);
                    xOffsetTemp=bounds.centerX()-((highTempLen+lowTempLen+20)/2);
                    canvas.drawText(mWeatherHigh,xOffsetTemp,mTempTopOffset,mTextTempHighAmbientPaint);
                    canvas.drawText(mWeatherLow,xOffsetTemp+highTempLen+20,mTempTopOffset,mTextTempLowAmbientPaint);
                }
                else
                {
                    highTempLen=mTextTempHighPaint.measureText(mWeatherHigh);
                    lowTempLen=mTextTempLowPaint.measureText(mWeatherLow);
                    xOffsetTemp=bounds.centerX()-((highTempLen+lowTempLen+20+mWeatherIcon.getWidth()+20)/2);
                    canvas.drawBitmap(mWeatherIcon,xOffsetTemp,mTempTopOffset-mWeatherIcon.getHeight(),null);
                    canvas.drawText(mWeatherHigh,xOffsetTemp+mWeatherIcon.getWidth()+20,mTempTopOffset,mTextTempHighPaint);
                    canvas.drawText(mWeatherLow,xOffsetTemp+mWeatherIcon.getWidth()+20+highTempLen+20,mTempTopOffset,mTextTempLowPaint);
                }
            }
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

        //has to be implemented for using DataApi.DataListener


        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.d(TAG, "Data Received from phone");
            for (DataEvent dataEvent : dataEventBuffer)
            {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED)
                {
                    DataMap dataMap = DataMapItem.fromDataItem(dataEvent.getDataItem()).getDataMap();
                    String path = dataEvent.getDataItem().getUri().getPath();
                    Log.d(TAG, "MyPath:" + path);

                    if(path.equals(WEATHER_PATH))
                    {
                        if(dataMap.containsKey(KEY_TIME))
                            Log.d(TAG,"TIME:"+dataMap.getString(KEY_TIME));

                        if(dataMap.containsKey(KEY_HIGH))
                        {
                            mWeatherHigh=dataMap.getString(KEY_HIGH);
                            Log.d(TAG,"High Temp is:"+mWeatherHigh);
                        }
                        else
                        {
                            Log.d(TAG,"High Temp Not Available");
                        }

                        if (dataMap.containsKey(KEY_LOW))
                        {
                            mWeatherLow=dataMap.getString(KEY_LOW);
                            Log.d(TAG,"Low Temp is:"+mWeatherLow);
                        }
                        else
                        {
                            Log.d(TAG,"Low Temp Not Available");
                        }

                        if(dataMap.containsKey(KEY_WEATHER_ID))
                        {
                            int weatherId=dataMap.getInt(KEY_WEATHER_ID);
                            Log.d(TAG,"weatherId:"+weatherId);
                            Drawable b=getResources().getDrawable(Utility.getIconResourceForWeatherCondition(weatherId));
                            Bitmap icon=((BitmapDrawable)b).getBitmap();
                            float scaledWidth=(mTextTempHighPaint.getTextSize()/icon.getHeight())*icon.getWidth();
                            mWeatherIcon=Bitmap.createScaledBitmap(icon,(int)scaledWidth,(int)mTextTempHighPaint.getTextSize(),true);
                        }
                        else
                        {
                            Log.d(TAG,"Weather Id Not Available");
                        }
                        invalidate();
                    }
                }
            }
        }

        //have to be implemented for using GoogleApiClient.ConnectionCallbacks
        @Override
        public void onConnected(Bundle bundle)
        {
            Log.d(TAG,"Connected to Google Api Client");
            Wearable.DataApi.addListener(client,Engine.this);
        }

        @Override
        public void onConnectionSuspended(int i)
        {
            Log.d(TAG,"Disconnected from Google Api Client");
        }

        //has to be implemented for using GoogleApiClient.OnConnectionFailedListener
        @Override
        public void onConnectionFailed(ConnectionResult connectionResult)
        {
            Log.d(TAG,"Google Api Client Failure");
        }
    }
}
