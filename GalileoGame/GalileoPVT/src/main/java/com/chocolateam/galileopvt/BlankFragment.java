package com.chocolateam.galileopvt;

import android.Manifest;
import android.content.Context;
import android.location.GnssClock;
import android.location.GnssMeasurement;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.cts.nano.Ephemeris;
import android.os.Bundle;
import android.os.StrictMode;
import android.os.SystemClock;
import android.support.v4.app.Fragment;
import android.support.v4.content.ContextCompat;
import android.telephony.CellInfo;
import android.telephony.CellInfoLte;
import android.telephony.TelephonyManager;
import android.telephony.gsm.GsmCellLocation;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.location.cts.nano.Ephemeris.GpsEphemerisProto;
import android.location.cts.nano.Ephemeris.GpsNavMessageProto;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static android.content.Context.LOCATION_SERVICE;

/**
 * Created by Peter Vanik on 16/03/2018.
 * Class containing calculated measurement attributes of a Satellite
 */

public class BlankFragment extends Fragment implements Runnable, LocationListener {
    public static final int MIN_CARRIER_TO_NOISE = 18;
    public static final double MIN_SAT_ELEVATION = Math.toRadians(10.0);
    private static String CONSTELLATION_SWITCH = "GPS"; // possible values: GPS, GALILEO

    private Context context;
    private LocationManager mLocationManager;
    private GnssClock receiverClock;
    private long fullBiasNanos;
    private boolean fullBiasNanosSet;
    private double biasNanos;
    private boolean biasNanosSet;
    private Collection<GnssMeasurement> noisySatellites;
    private ArrayList<GnssMeasurement> galileoSatellites;
    private ArrayList<GnssMeasurement> gpsSatellites;
    private ArrayList<Satellite> pseudoSats;

    private Ephemeris.GpsNavMessageProto navMsg;

    private static double[] userPositionECEFmeters;
    private static double latitudeDegrees;
    private static double longitudeDegrees;
    private double altitudeMeters;
    private static double receiverClockErrorMeters;

    private double myTimeStamp;
    private double aggrDiffMinute;
    private long numberOfPVTcalculations;

    public BlankFragment() {
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);
        run();
    }

    public void run() {
        biasNanosSet = false;
        fullBiasNanosSet = false;
        mLocationManager =
                (LocationManager) getActivity().getSystemService(LOCATION_SERVICE);

        // Set initial location TODO cellID()
        // Hardcoded Noordwijk
        userPositionECEFmeters = new double[3];
        userPositionECEFmeters [0] = 3904174;
        userPositionECEFmeters [1] = 301788;
        userPositionECEFmeters [2] = 5017699;
        latitudeDegrees = 52.21831;
        longitudeDegrees = 4.42004;
        altitudeMeters = 0.0;
        receiverClockErrorMeters = 0.0;
        myTimeStamp = 0.0;
        aggrDiffMinute = 0.0;
        numberOfPVTcalculations = 0;

        /****************************************************
                       Obtain Navigation message
        ****************************************************/

        NavThread navThread = new NavThread();

        try {
            long[] mReferenceLocation = new long[] {0,0};
            navMsg = new NavThread().execute(mReferenceLocation).get();
            Log.e("Obtaining navigation message...", "SUCCESSFUL");
        } catch (Exception e) {
            e.printStackTrace();
            Log.e("Obtaining navigation message...", "FAILED");
        }

        /*******************************************************************************
         GNSS Measurements Event for obtaining receiver clock and Satellite measurements
         ******************************************************************************/
        GnssMeasurementsEvent.Callback gnssMeasurementsEventCallback = new GnssMeasurementsEvent.Callback() {
            @Override
            public void onGnssMeasurementsReceived (GnssMeasurementsEvent eventArgs) {
                super.onGnssMeasurementsReceived (eventArgs);
                noisySatellites = eventArgs.getMeasurements();
                receiverClock = eventArgs.getClock();
                fullBiasNanos = receiverClock.getFullBiasNanos();
                biasNanos = receiverClock.getBiasNanos();

                // Reset list of Galileo and GPS satellites
                galileoSatellites = new ArrayList<>();
                gpsSatellites = new ArrayList<>();

                // Filter for clock discontinuity
                if (receiverClock.getHardwareClockDiscontinuityCount() == 0) {

                    // Display number of all visible satellites
                    int gpscount = 0;
                    int galcount = 0;
                    for (GnssMeasurement n : noisySatellites) {
                        if (n.getConstellationType() == GnssStatus.CONSTELLATION_GPS){
                            gpscount+=1;
                        } else if (n.getConstellationType() == GnssStatus.CONSTELLATION_GALILEO){
                            galcount+=1;
                        }
                    }
                    Log.e("total noisy gps: ", String.valueOf(gpscount));
                    Log.e("total noisy galileo: ", String.valueOf(galcount));

                    /***************************************************
                     * Clean the list of satellites in measurement event
                     **************************************************/
                    for (GnssMeasurement m : noisySatellites) {
                        // Filter satellites for bad carrier to noise ratio
                        if (m.getCn0DbHz() >= MIN_CARRIER_TO_NOISE) {
                            if (CONSTELLATION_SWITCH.equals("GPS")) {
                                if (m.getConstellationType() == GnssStatus.CONSTELLATION_GPS) {
                                    if (
                                            (
                                                    (m.getState() & GnssMeasurement.STATE_TOW_DECODED) == GnssMeasurement.STATE_TOW_DECODED
                                                            ||
                                                            (m.getState() & GnssMeasurement.STATE_TOW_KNOWN) == GnssMeasurement.STATE_TOW_KNOWN
                                            ) &&
                                                    (m.getState() & GnssMeasurement.STATE_CODE_LOCK) == GnssMeasurement.STATE_CODE_LOCK
                                            ) {
                                        gpsSatellites.add(m);
                                    }
                                }
                            } else if (CONSTELLATION_SWITCH.equals("GALILEO")) {
                                if (m.getConstellationType() == GnssStatus.CONSTELLATION_GALILEO) {
                                    if (
                                            (m.getState() & GnssMeasurement.STATE_GAL_E1C_2ND_CODE_LOCK) == GnssMeasurement.STATE_GAL_E1C_2ND_CODE_LOCK
                                                    ||
                                                    (m.getState() & GnssMeasurement.STATE_TOW_DECODED) == GnssMeasurement.STATE_TOW_DECODED
                                                    ||
                                                    (m.getState() & GnssMeasurement.STATE_TOW_KNOWN) == GnssMeasurement.STATE_TOW_KNOWN
                                            ) {
                                        galileoSatellites.add(m);
                                    }
                                }
                            }
                        }
                    }
                    Log.e("Total cleaned GPS: ", String.valueOf(gpsSatellites.size()));
                    Log.e("Total cleaned Galileo: ", String.valueOf(galileoSatellites.size()));

                    /*********************************************************************************
                     For every cleaned satellite in constellation, compute pseudorange and corrections
                     ********************************************************************************/
                    // TODO only start computing satellite data if there are enough for a PVT (>3)
                    if (CONSTELLATION_SWITCH.equals("GPS") && (gpsSatellites.size() > 0)) {
                        pseudoSats = new ArrayList<>();

                        for (int i = 0; i < gpsSatellites.size(); i++) {
                            Satellite pseudosat = new Satellite(gpsSatellites.get(i).getSvid(), CONSTELLATION_SWITCH, navMsg, fullBiasNanos, userPositionECEFmeters, gpsSatellites.get(i).getState());

                            // Pseudorange
                            pseudosat.computeGnssTime(
                                    receiverClock.getTimeNanos(), gpsSatellites.get(i).getTimeOffsetNanos(),
                                    fullBiasNanos,  biasNanos);
                            pseudosat.computeWeekNumberNanos(fullBiasNanos);
                            pseudosat.computeReceivedTime();
                            pseudosat.computeTransmittedTime(gpsSatellites.get(i).getReceivedSvTimeNanos() + (long)gpsSatellites.get(i).getTimeOffsetNanos());
                            pseudosat.computePseudoRange();
                            Log.e("Pseudorange: ", String.valueOf(pseudosat.getPseudoRange()));

                            // Satellite clock correction
                            pseudosat.computeSatClockCorrectionAndRecomputeTransmissionTime(receiverClockErrorMeters);
                            Log.e("Sat clock correction meters: ", String.valueOf(pseudosat.getSatelliteClockCorrectionMeters()));
                            pseudosat.computeSatellitePosition();

                            // Satellite elevation and atmospheric corrections
                            pseudosat.computeSatElevationRadians();
                            Log.e("Sat elevation in radians: ", String.valueOf(pseudosat.getSatElevationRadians()));
                            pseudosat.computeTroposphericCorrection_GPS(Math.toRadians(latitudeDegrees), altitudeMeters);
                            Log.e("Tropo correction meters: ", String.valueOf(pseudosat.getTroposphericCorrectionMeters()));
                            double alpha[] = navMsg.iono.alpha;
                            double beta[] = navMsg.iono.beta;
                            pseudosat.computeIonosphericCorrection_GPS(alpha, beta);
                            Log.e("IONO correction meters: ", String.valueOf(pseudosat.getIonosphericCorrectionSeconds()*pseudosat.LIGHTSPEED));

                            // Corrected pseudorange
                            pseudosat.computeCorrectedRange();
                            Log.e("CORRECTED RANGE: ", String.valueOf(pseudosat.getCorrectedRange()));
                            pseudoSats.add(pseudosat);
                            Log.e("",""); // empty line
                        }
                    }
                    /*********************************** GALILEO **********************************/
                    // TODO only start computing satellite data if there are enough for a PVT (>3)
                    else if (CONSTELLATION_SWITCH.equals("GALILEO") && (galileoSatellites.size() > 0)) {

                        pseudoSats = new ArrayList<>(galileoSatellites.size());

                        for (int i = 0; i < galileoSatellites.size(); i++) {
                            Satellite pseudosat = new Satellite(galileoSatellites.get(i).getSvid(), CONSTELLATION_SWITCH, navMsg, fullBiasNanos, userPositionECEFmeters, galileoSatellites.get(i).getState());

                            // Pseudorange
                            pseudosat.computeGnssTime(
                                    receiverClock.getTimeNanos(), galileoSatellites.get(i).getTimeOffsetNanos(),
                                    fullBiasNanos,  biasNanos);
                            pseudosat.computeWeekNumberNanos(fullBiasNanos);
                            pseudosat.computeMillisecondsNumberNanos(fullBiasNanos);
                            pseudosat.computeReceivedTime();
                            pseudosat.computeTransmittedTime(galileoSatellites.get(i).getReceivedSvTimeNanos() + (long)galileoSatellites.get(i).getTimeOffsetNanos()); // TODO test the time offset nano
                            pseudosat.computePseudoRange();
                            Log.e("Pseudorange: ", String.valueOf(pseudosat.getPseudoRange()));
                            // TODO if a pseudorange is > 3, there's a clock error so stop the thread and execute run() again
                            // Satellite clock correction
                            pseudosat.computeSatClockCorrectionAndRecomputeTransmissionTime(receiverClockErrorMeters);
                            Log.e("Sat clock correction meters: ", String.valueOf(pseudosat.getSatelliteClockCorrectionMeters()));
                            pseudosat.computeSatellitePosition();
                            //pseudosat.computeMySatPos();

                            // Satellite elevation and atmospheric corrections less frequently
                            if ( pseudosat.getGnssTime() % 10 < 2){
                                pseudosat.computeSatElevationRadians();
                                pseudosat.computeTroposphericCorrection_GPS(Math.toRadians(latitudeDegrees), altitudeMeters);
                                double alpha[] = navMsg.iono.alpha;
                                double beta[] = navMsg.iono.beta;
                                pseudosat.computeIonosphericCorrection_GPS(alpha, beta);
                            }
                            Log.e("Sat elevation in radians: ", String.valueOf(pseudosat.getSatElevationRadians()));
                            Log.e("Tropo correction meters: ", String.valueOf(pseudosat.getTroposphericCorrectionMeters()));
                            Log.e("IONO correction meters: ", String.valueOf(pseudosat.getIonosphericCorrectionSeconds()*pseudosat.LIGHTSPEED));

                            // Corrected pseudorange
                            pseudosat.computeCorrectedRange();
                            Log.e("CORRECTED RANGE: ", String.valueOf(pseudosat.getCorrectedRange()));
                            pseudoSats.add(pseudosat);
                            Log.e("",""); // empty line
                        }
                    }

                    /************************************************************************************
                     If there are enough satellites with pseudorange, perform linearisation and get x y z
                     ***********************************************************************************/
                    if (pseudoSats.size() > 3) {
                        Log.e("---------------------------", "PVT computation in progress-----------------------");
                        ArrayList<double[]> satCoords = new ArrayList<>();
                        double[] correctedRanges = new double[pseudoSats.size()];
                        double[] satClockErrors = new double[pseudoSats.size()];
                        double[] userPosECEFandReceiverClockError = new double[4];
                        for (int i = 0; i < userPositionECEFmeters.length; i++) {
                            userPosECEFandReceiverClockError[i] = userPositionECEFmeters[i];
                        }
                        userPosECEFandReceiverClockError[3] = 0.0; // initial clock error
                        double[] satElevations = new double[pseudoSats.size()];

                        for (int i = 0; i < pseudoSats.size(); i++) {
                            Satellite thisSat = pseudoSats.get(i);
                            satCoords.add(thisSat.getSatPositionECEFmeters());
                            correctedRanges[i] = thisSat.getCorrectedRange();
                            satClockErrors[i] = thisSat.getSatelliteClockCorrectionMeters();// getMySatClockOffsetSeconds(thisSat.getTransmittedTime())*Satellite.LIGHTSPEED; // using my offset instead of Google's
                            satElevations[i] = Math.toDegrees(thisSat.getSatElevationRadians());
                        }

                        userPosECEFandReceiverClockError = LeastSquares.recursiveLsq(satCoords, correctedRanges, userPosECEFandReceiverClockError, satElevations);

                        userPositionECEFmeters[0] = userPosECEFandReceiverClockError[0];
                        userPositionECEFmeters[1] = userPosECEFandReceiverClockError[1];
                        userPositionECEFmeters[2] = userPosECEFandReceiverClockError[2];
                        receiverClockErrorMeters = userPosECEFandReceiverClockError[3];
                        Log.e("RX ERROR meters", String.valueOf(receiverClockErrorMeters));
                        Ecef2LlaConverter.GeodeticLlaValues lla =
                                Ecef2LlaConverter.convertECEFToLLACloseForm(userPositionECEFmeters[0],
                                        userPositionECEFmeters[1], userPositionECEFmeters[2]);
                        latitudeDegrees = Math.toDegrees(lla.latitudeRadians);
                        longitudeDegrees = Math.toDegrees(lla.longitudeRadians);
                        altitudeMeters = lla.altitudeMeters;
                        Log.e("USER Latitude deg: ", String.valueOf(latitudeDegrees));
                        Log.e("USER Longitude deg: ", String.valueOf(longitudeDegrees));
                        Log.e("USER altitude: ", String.valueOf(altitudeMeters));

                        double homeLat = 52.160982;
                        double homeLon = 4.496887;
                        double diffHomeLatE6 = Math.abs(latitudeDegrees-homeLat)*1e6;
                        double diffHomeLonE6 = Math.abs(longitudeDegrees-homeLon)*1e6;
                        Log.e("","");
                        Log.e("difference to home lat E6: ", String.valueOf(diffHomeLatE6));
                        Log.e("difference to home lon E6: ", String.valueOf(diffHomeLonE6));
                        Log.e("difference combined:: ", String.valueOf(diffHomeLonE6 + diffHomeLonE6));
                        Log.e("","");

                        if (myTimeStamp == 0.0) {
                            myTimeStamp = System.currentTimeMillis();
                        }
                        if ((System.currentTimeMillis() - myTimeStamp) <= 60000 ) {
                            aggrDiffMinute += diffHomeLonE6 + diffHomeLonE6;
                            numberOfPVTcalculations += 1;
                        } else {
                            Log.e("Aggregated latlong difference in 1 min: ", String.valueOf(aggrDiffMinute));
                            Log.e("Total pvt calculations in 1 min: ", String.valueOf(numberOfPVTcalculations));
                            Log.e("Average difference per PVT calc in 1 min: ", String.valueOf(aggrDiffMinute/numberOfPVTcalculations));
                        }
                    }
                } else {
                    Log.e("CLOCK DISCONTINUITY", "Hardware clock discontinuity is not zero.");
                }
            }
        };
        ContextCompat.checkSelfPermission(this.getContext(), Manifest.permission.ACCESS_FINE_LOCATION); // To avoid errors with registering callbacks
        mLocationManager.registerGnssMeasurementsCallback(gnssMeasurementsEventCallback);
        mLocationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER, 0, 0, this
        );
    }

    /***
     Misc
     ***/

    public void setContext(Context context) {
        this.context = context;
    }

    public static double[] getUserPositionECEFmeters() {
        return userPositionECEFmeters;
    }

    public void switchConstellation(String constellation) {
        CONSTELLATION_SWITCH = constellation;
    }

    public static double getReceiverClockErrorMeteres() { return receiverClockErrorMeters;}

    public static double getUserLatitudeDegrees() { return latitudeDegrees; }

    public static double getUserLongitudeDegrees() { return longitudeDegrees; }

    public void cellIDLocation(){
        // Update cellCID, cellMCC, cellMNC, cellID, cellLAC from Telephony API
        ContextCompat.checkSelfPermission(this.getContext(), Manifest.permission.ACCESS_FINE_LOCATION);
        ContextCompat.checkSelfPermission(this.getContext(), Manifest.permission.ACCESS_COARSE_LOCATION);
        TelephonyManager telephonyManager = (TelephonyManager) getActivity().getSystemService(Context.TELEPHONY_SERVICE);
        GsmCellLocation cellLocation = (GsmCellLocation) telephonyManager.getCellLocation();
        int cellID = cellLocation.getCid();
        int cellLAC = cellLocation.getLac();
        String cellPosition = cellLocation.toString();

        //Log.e("All cell info", String.valueOf(telephonyManager.getAllCellInfo()));
        List<CellInfo> cellInfoList = telephonyManager.getAllCellInfo();
        CellInfoLte cellInfoLte = (CellInfoLte) cellInfoList.get(0);

        int cellCID = cellInfoLte.getCellIdentity().getCi();
        int cellMCC = cellInfoLte.getCellIdentity().getMcc();
        int cellMNC = cellInfoLte.getCellIdentity().getMnc();

        // Now we should have all the elements to request Google Network Location API

        for (int i = 0; i < cellInfoList.size(); i++){
            //Log.e("CELL ", String.valueOf(cellInfoList.get(i)));
        }
    }

    /****************************
     LocationListener boilerplate
     ***************************/
    @Override
    public void onLocationChanged(Location location) {

    }

    @Override
    public void onStatusChanged(String s, int i, Bundle bundle) {

    }

    @Override
    public void onProviderEnabled(String s) {

    }

    @Override
    public void onProviderDisabled(String s) {

    }
}
