package com.chocolateam.galileospaceship;


import android.content.ComponentName;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementsEvent;
import android.os.Bundle;
import android.os.IBinder;
import android.support.v4.app.Fragment;
import android.support.v4.app.FragmentManager;
import android.support.v4.app.FragmentPagerAdapter;
import android.support.v4.app.FragmentStatePagerAdapter;
import android.support.v4.view.ViewPager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import com.chocolateam.galileospaceship.R;
import com.galfins.gnss_compare.CalculationModule;
import com.galfins.gnss_compare.CalculationModulesArrayList;
import com.galfins.gnss_compare.Constellations.SatelliteParameters;
import com.galfins.gnss_compare.GNSSCoreServiceActivity;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Observable;
import java.util.Observer;

/**
 * Created by Lionel Garcia on 25/01/2018.
 */

public class SpaceshipViewActivity extends GNSSCoreServiceActivity {

    private final String TAG = this.getClass().getSimpleName();

    protected int locationFuncLevel = 0;

    static final int NUM_PANELS = 3;
    SpacecraftPagerAdapter mAdapter;
    ViewPager mPager;
    Date mInitialTime;

    private Thread listThread;

    static ListViewFragment mListViewFragment;
    static SkyViewFragment mSkyViewFragment;
    static RadarViewFragment mRadarViewFragment;

    public Observer shipUpdater = new Observer() {
        @Override
        public void update(final Observable o, Object arg) {

        Log.e(TAG, "-- observer tick");

        CalculationModulesArrayList CMArrayList = gnssBinder.getCalculationModules();

        if (mListViewFragment.isCreated()) {
            mListViewFragment.resetSatelliteList();
        }
        if (mSkyViewFragment.isCreated()) {
            mSkyViewFragment.resetSatellites();
        }
        if (mRadarViewFragment.isCreated()) {
            mRadarViewFragment.resetSatellites();
        }

        for (final CalculationModule calculationModule : CMArrayList) {

            final String calcName = calculationModule.getConstellation().getName();
            final String currentConstellation = mListViewFragment.getSelectedConstellation();

            Log.e(TAG, "ConstSize: " + calculationModule.getConstellation().getVisibleConstellationSize());
            Log.e(TAG, "ConstName: " + calculationModule.getConstellation().getName());


            final List<SatelliteParameters> satellites = calculationModule.getConstellation().getSatellites();
            final List<SatelliteParameters> satellitesOnlyVisible = calculationModule.getConstellation().getUnusedSatellites();

            List<SatelliteParameters> satellitesAllTemp  = calculationModule.getConstellation().getSatellites();
            satellitesAllTemp.addAll(satellitesOnlyVisible);

            final List<SatelliteParameters> satellitesAll = satellitesAllTemp;

            Log.e(TAG, "Satellite list not empty? Used: " + satellites.size() + " Unused but visible: " + satellitesOnlyVisible.size());

            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (!satellitesAll.isEmpty()) {

                        Log.e(TAG, "Comparing Selected Const: " + currentConstellation + " with Const from calcModule " + calcName);

                        if (currentConstellation.equals(calcName)) {
                            Log.e(TAG, "SATPOS: " + currentConstellation +" " + String.valueOf(satellites.size()));

                            // TODO: check if satellitesAll is okay, or if only satellites should be used
                            if (mListViewFragment.isCreated()) {
                                mListViewFragment.addSatellites(satellitesAll);
                                mListViewFragment.setLatLong(calculationModule.getPose().getGeodeticLatitude(), calculationModule.getPose().getGeodeticLongitude());
                                mListViewFragment.setAltitude(calculationModule.getPose().getGeodeticHeight());
                                //TODO:
                                // mListViewFragment.setSpeedIndicator( ?? );
                            }

                            if (mSkyViewFragment.isCreated()) {
                                mSkyViewFragment.addSatellites(satellitesAll);
                            }

                            if (mRadarViewFragment.isCreated()) {
                                mRadarViewFragment.setLatLngXYZ(calculationModule.getPose().getGeodeticLatitude(), calculationModule.getPose().getGeodeticLongitude(),
                                        calculationModule.getPose().getX(), calculationModule.getPose().getY(), calculationModule.getPose().getZ());
                                mRadarViewFragment.addSatellites(satellitesAll);
                            }
                        }

                        /** Update the respective views **/
                        if (mListViewFragment.isCreated()) {
                            mListViewFragment.updateViewedSatellites();
                        }
                        if (mSkyViewFragment.isCreated()) {
                            mSkyViewFragment.updateSatView();
                        }
                        if (mRadarViewFragment.isCreated()) {
                            mRadarViewFragment.updateSatellites();
                            mRadarViewFragment.setSatCounts();
                            mRadarViewFragment.setTimeUTC();
                            mRadarViewFragment.setclock(mInitialTime);
                        }
                    }
                }
            });

        }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.requestWindowFeature(Window.FEATURE_NO_TITLE);
        this.getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.spacecraft_pager);

        mListViewFragment = new ListViewFragment();
        mSkyViewFragment = new SkyViewFragment();
        mRadarViewFragment = new RadarViewFragment();

        mInitialTime = Calendar.getInstance().getTime();

        mAdapter = new SpacecraftPagerAdapter(getSupportFragmentManager());

        mPager = findViewById(R.id.pager);
        mPager.setAdapter(mAdapter);

        locationFuncLevel = getIntent().getExtras().getInt("location_functionality");
        boolean isNavDefault = (locationFuncLevel == LOCATION_DEFAULT_NAV);
        boolean isNavGpsOnly = (locationFuncLevel == LOCATION_GPS_ONLY);

        Bundle bundle = new Bundle();
        bundle.putBoolean("isNavDefault", isNavDefault);
        bundle.putBoolean("isNavGpsOnly", isNavGpsOnly);
        mListViewFragment.setArguments(bundle);
        mSkyViewFragment.setArguments(bundle);
        mRadarViewFragment.setArguments(bundle);
    }

    public static class SpacecraftPagerAdapter extends FragmentStatePagerAdapter {
        public SpacecraftPagerAdapter(FragmentManager fm) {
            super(fm);
        }

        @Override
        public int getCount() {
            return NUM_PANELS;
        }

        @Override
        public Fragment getItem(int position) {
            switch (position) {
                case 0:
                    return mListViewFragment;
                case 1:
                    return mSkyViewFragment;
                case 2:
                    return mRadarViewFragment;
                default:
                    return new Fragment();
            }
        }
    }

    public void backToMenu(View view) {
        finish();
    }

    // Methods to fill the UI

        // Left panel - mListViewFragment

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        super.onServiceConnected(name, binder);
        if (locationFuncLevel > LOCATION_DEFAULT_NAV) {
            gnssBinder.addObserver(shipUpdater);
            Log.e(TAG, "-- observer ADDED");
        }
    }

    @Override
    protected void onPause() {
        if (gnssBinder != null) {
            gnssBinder.removeObserver(shipUpdater);
            Log.e(TAG, "-- observer REMOVED");
        }
        super.onPause();
    }

    public void jumpToListView(View view) {
        mPager.setCurrentItem(0);
    }

    public void jumpToSkyView(View view) {
        mPager.setCurrentItem(1);
    }

    public void jumpToRadarView(View view) {
        mPager.setCurrentItem(2);
    }
}
