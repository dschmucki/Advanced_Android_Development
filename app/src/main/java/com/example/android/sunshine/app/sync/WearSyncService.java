package com.example.android.sunshine.app.sync;

import android.app.IntentService;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.util.Log;

import com.example.android.sunshine.app.Utility;
import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

/**
 * Created by domi on 01.03.16.
 */
public class WearSyncService extends IntentService implements
        GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {

    public static final String ACTION_SEND_WEAR_DATA = "com.example.android.sunshine.app.ACTION_SEND_WEAR_DATA";

    private static final String PATH = "/weather";
    private static final String DATA_WEATHER_ID = "weatherId";
    private static final String DATA_HIGH_TEMP = "highTemp";
    private static final String DATA_LOW_TEMP = "lowTemp";
    private static final String DATA_TIMESTAMP = "timestamp";

    private GoogleApiClient mGoogleApiClient;
    private PutDataMapRequest mRequestMap;

    public WearSyncService() {
        super(WearSyncService.class.getSimpleName());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mGoogleApiClient = new GoogleApiClient.Builder(WearSyncService.this)
                .addApi(Wearable.API)
                .addConnectionCallbacks(this)
                .addOnConnectionFailedListener(this)
                .build();
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null && ACTION_SEND_WEAR_DATA.equals(intent.getAction())) {
            mGoogleApiClient.connect();
        }
    }

    @Override
    public void onConnected(Bundle bundle) {
        updateWearable();
    }

    @Override
    public void onConnectionSuspended(int i) {
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
    }

    private void updateWearable() {
        String locationQuery = Utility.getPreferredLocation(this);
        Uri weatherUri = WeatherContract.WeatherEntry.buildWeatherLocationWithDate(locationQuery, System.currentTimeMillis());
        Cursor c = getContentResolver().query(
                weatherUri,
                new String[]{WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
                        WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
                        WeatherContract.WeatherEntry.COLUMN_MIN_TEMP
                }, null, null, null);
        if (c != null && c.moveToFirst()) {
            int weatherId = c.getInt(c.getColumnIndex(WeatherContract.WeatherEntry.COLUMN_WEATHER_ID));
            String highTemp = Utility.formatTemperature(this, c.getDouble(c.getColumnIndex(WeatherContract.WeatherEntry.COLUMN_MAX_TEMP)));
            String lowTemp = Utility.formatTemperature(this, c.getDouble(c.getColumnIndex(WeatherContract.WeatherEntry.COLUMN_MIN_TEMP)));
            mRequestMap = PutDataMapRequest.create(PATH);
            mRequestMap.getDataMap().putInt(DATA_WEATHER_ID, weatherId);
            mRequestMap.getDataMap().putString(DATA_HIGH_TEMP, highTemp);
            mRequestMap.getDataMap().putString(DATA_LOW_TEMP, lowTemp);
            mRequestMap.getDataMap().putLong(DATA_TIMESTAMP, System.currentTimeMillis());
            Log.d("WearSyncService", "weatherId: " + weatherId + " highTemp: " + highTemp + " lowTemp: " + lowTemp);

            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    PendingResult<DataApi.DataItemResult> result = Wearable.DataApi.putDataItem(mGoogleApiClient, mRequestMap.asPutDataRequest());
                }
            });
            thread.start();
        }
        if (c != null) {
            c.close();
        }
    }

    @Override
    public void onDestroy() {
        if (mGoogleApiClient.isConnected()) {
            mGoogleApiClient.disconnect();
        }
        super.onDestroy();
    }

}
