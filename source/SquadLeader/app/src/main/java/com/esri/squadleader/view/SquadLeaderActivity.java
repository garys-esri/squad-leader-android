/*******************************************************************************
 * Copyright 2013-2015 Esri
 * 
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 * 
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 ******************************************************************************/
package com.esri.squadleader.view;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.RadioGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.ToggleButton;

import com.esri.android.map.Callout;
import com.esri.android.map.MapView;
import com.esri.android.map.event.OnPanListener;
import com.esri.android.map.event.OnSingleTapListener;
import com.esri.android.runtime.ArcGISRuntime;
import com.esri.core.geometry.AngularUnit;
import com.esri.core.geometry.Point;
import com.esri.core.geometry.SpatialReference;
import com.esri.core.map.Graphic;
import com.esri.militaryapps.controller.ChemLightController;
import com.esri.militaryapps.controller.LocationController.LocationMode;
import com.esri.militaryapps.controller.LocationListener;
import com.esri.militaryapps.controller.MapConfigListener;
import com.esri.militaryapps.controller.MessageController;
import com.esri.militaryapps.controller.PositionReportController;
import com.esri.militaryapps.controller.SpotReportController;
import com.esri.militaryapps.model.Geomessage;
import com.esri.militaryapps.model.LayerInfo;
import com.esri.militaryapps.model.Location;
import com.esri.militaryapps.model.LocationProvider.LocationProviderState;
import com.esri.militaryapps.model.MapConfig;
import com.esri.militaryapps.model.SpotReport;
import com.esri.squadleader.R;
import com.esri.squadleader.controller.AdvancedSymbolController;
import com.esri.squadleader.controller.LocationController;
import com.esri.squadleader.controller.MapController;
import com.esri.squadleader.controller.MessageListener;
import com.esri.squadleader.controller.ViewshedController;
import com.esri.squadleader.model.BasemapLayer;
import com.esri.squadleader.util.Utilities;
import com.esri.squadleader.view.AddLayerDialogFragment.AddLayerListener;
import com.esri.squadleader.view.ClearMessagesDialogFragment.ClearMessagesHelper;
import com.esri.squadleader.view.GoToMgrsDialogFragment.GoToMgrsHelper;
import com.ipaulpro.afilechooser.utils.FileUtils;

import java.io.File;
import java.io.IOException;
import java.net.SocketException;
import java.util.Date;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

/**
 * The main activity for the Squad Leader application. Typically this displays a map with various other
 * controls.
 */
public class SquadLeaderActivity extends Activity
        implements AddLayerListener, ClearMessagesHelper, GoToMgrsHelper {
    
    private static final String TAG = SquadLeaderActivity.class.getSimpleName();
    private static final double MILLISECONDS_PER_HOUR = 1000 * 60 * 60;
    
    /**
     * A unique ID for the GPX file chooser.
     */
    private static final int REQUEST_CHOOSER = 30046;
    
    /**
     * A unique ID for getting a result from the settings activity.
     */
    private static final int SETTINGS_ACTIVITY = 5862;
    
    /**
     * A unique ID for getting a result from the spot report activity.
     */
    private static final int SPOT_REPORT_ACTIVITY = 15504;

    /**
     * A unique ID for adding a layer from a file.
     */
    private static final int ADD_LAYER_FROM_FILE = 31313;
    
    private static final String LAST_WKID_KEY = "lastWkid";
    
    private final Handler locationChangeHandler = new Handler() {
        
        private final SpatialReference SR = SpatialReference.create(4326);
        
        private Location previousLocation = null;
        
        @Override
        public void handleMessage(Message msg) {
            if (null != msg) {
                Location location = (Location) msg.obj;
                try {
                    TextView locationView = (TextView) findViewById(R.id.textView_displayLocation);
                    String mgrs = mapController.pointToMgrs(new Point(location.getLongitude(), location.getLatitude()), SR);
                    locationView.setText(getString(R.string.display_location) + mgrs);
                } catch (Throwable t) {
                    Log.i(TAG, "Couldn't set location text", t);
                }
                try {
                    double speedMph = location.getSpeedMph();
                    if (0 == Double.compare(speedMph, 0.0)
                            && null != previousLocation
                            && !mapController.getLocationController().getMode().equals(LocationMode.LOCATION_SERVICE)) {
                        //Calculate speed
                        double distanceInMiles = Utilities.calculateDistanceInMeters(previousLocation, location) / Utilities.METERS_PER_MILE;
                        double timeInHours = (location.getTimestamp().getTimeInMillis() - previousLocation.getTimestamp().getTimeInMillis()) /  MILLISECONDS_PER_HOUR;
                        speedMph = distanceInMiles / timeInHours;
                    }
                    ((TextView) findViewById(R.id.textView_displaySpeed)).setText(
                            getString(R.string.display_speed) + Double.toString(Math.round(10.0 * speedMph) / 10.0) + " mph");
                } catch (Throwable t) {
                    Log.i(TAG, "Couldn't set speed text", t);
                }
                try {
                    String headingString = LocationController.headingToString(location.getHeading(), angularUnitPreference, 0);
                    ((TextView) findViewById(R.id.textView_displayHeading)).setText(getString(R.string.display_heading) + headingString);
                } catch (Throwable t) {
                    Log.i(TAG, "Couldn't set heading text", t);
                }
                previousLocation = location;
            }
        };
    };
    
    private final OnSharedPreferenceChangeListener preferenceChangeListener = new OnSharedPreferenceChangeListener() {
        
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (key.equals(getString(R.string.pref_angularUnits))) {
                try {
                    int angularUnitWkid = Integer.parseInt(sharedPreferences.getString(key, "0"));
                    angularUnitPreference = (AngularUnit) AngularUnit.create(angularUnitWkid);
                } catch (Throwable t) {
                    Log.i(TAG, "Couldn't get " + getString(R.string.pref_angularUnits) + " value", t);
                }
            } else if (key.equals(getString(R.string.pref_messagePort))) {
                boolean needToReset = true;
                try {
                    final int newPort = Integer.parseInt(sharedPreferences.getString(key, Integer.toString(messagePortPreference)));
                    if (1023 < newPort && 65536 > newPort && newPort != messagePortPreference) {
                        messagePortPreference = newPort;
                        changePort(newPort);
                        needToReset = false;
                    }
                } catch (Throwable t) {
                    Log.i(TAG, "Couldn't get " + getString(R.string.pref_messagePort) + " value; sticking with default of " + messagePortPreference, t);
                } finally {
                    if (needToReset) {
                        Editor editor = sharedPreferences.edit();
                        editor.putString(key, Integer.toString(messagePortPreference));
                        editor.commit();
                    }
                }
            } else if (key.equals(getString(R.string.pref_positionReportPeriod))) {
                try {
                    positionReportsPeriodPreference = Integer.parseInt(sharedPreferences.getString(key, Integer.toString(positionReportsPeriodPreference)));
                    positionReportController.setPeriod(positionReportsPeriodPreference);
                    int newPeriod = positionReportController.getPeriod();
                    if (newPeriod != positionReportsPeriodPreference) {
                        sharedPreferences.edit().putString(getString(R.string.pref_positionReportPeriod), Integer.toString(newPeriod)).commit();
                        positionReportsPeriodPreference = newPeriod;
                    }
                } catch (Throwable t) {
                    Log.i(TAG, "Couldn't get " + key + " value", t);
                }
            } else if (key.equals(getString(R.string.pref_positionReports))) {
                try {
                    positionReportsPreference = sharedPreferences.getBoolean(key, false);
                    positionReportController.setEnabled(positionReportsPreference);
                } catch (Throwable t) {
                    Log.i(TAG, "Couldn't get " + key + " value", t);
                }
            } else if (key.equals(getString(R.string.pref_uniqueId))) {
                try {
                    uniqueIdPreference = sharedPreferences.getString(key, uniqueIdPreference);
                    positionReportController.setUniqueId(uniqueIdPreference);
                } catch (Throwable t) {
                    Log.i(TAG, "Couldn't get " + key + " value", t);
                }
            } else if (key.equals(getString(R.string.pref_username))) {
                try {
                    usernamePreference = sharedPreferences.getString(key, usernamePreference);
                    messageController.setSenderUsername(usernamePreference);
                    positionReportController.setUsername(usernamePreference);
                } catch (Throwable t) {
                    Log.i(TAG, "Couldn't get " + key + " value", t);
                }
            } else if (key.equals(getString(R.string.pref_viewshedObserverHeight))) {
                float observerHeight = Float.parseFloat(sharedPreferences.getString(key, "-1"));
                if (observerHeight >= 0f && null != viewshedController) {
                    viewshedController.setObserverHeight(observerHeight);
                }
            }
        }
    };
    
    private final RadioGroup.OnCheckedChangeListener chemLightCheckedChangeListener;
    private final OnSingleTapListener defaultOnSingleTapListener;
    
    private MapController mapController = null;
    private MessageController messageController;
    private ChemLightController chemLightController;
    private NorthArrowView northArrowView = null;
    private SpotReportController spotReportController = null;
    private AdvancedSymbolController mil2525cController = null;
    private PositionReportController positionReportController;
    private ViewshedController viewshedController = null;
    private AddLayerDialogFragment addLayerDialogFragment = null;
    private ClearMessagesDialogFragment clearMessagesDialogFragment = null;
    private GoToMgrsDialogFragment goToMgrsDialogFragment = null;
    private boolean wasFollowMeBeforeMgrs = false;
    private final Timer clockTimer = new Timer(true);
    private TimerTask clockTimerTask = null;
    private AngularUnit angularUnitPreference = null;
    private int messagePortPreference = 45678;
    private boolean positionReportsPreference = false;
    private int positionReportsPeriodPreference = 1000;
    private String usernamePreference = "Squad Leader";
    private String vehicleTypePreference = "Dismounted";
    private String uniqueIdPreference = UUID.randomUUID().toString();
    private String sicPreference = "SFGPEWRR-------";
    private Graphic poppedUpChemLight = null;
    private SpatialReference lastSpatialReference = null;
    
    public SquadLeaderActivity() throws SocketException {
        super();
        chemLightCheckedChangeListener = new RadioGroup.OnCheckedChangeListener() {
            
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                for (int j = 0; j < group.getChildCount(); j++) {
                    final ToggleButton view = (ToggleButton) group.getChildAt(j);
                    view.setChecked(view.getId() == checkedId);
                }
            }
        };
        
        defaultOnSingleTapListener = createDefaultOnSingleTapListener();
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        ArcGISRuntime.setClientId(getString(R.string.clientId));
        ArcGISRuntime.License.setLicense(getString(R.string.licenseString));

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(SquadLeaderActivity.this);
        try {
            usernamePreference = sp.getString(getString(R.string.pref_username), usernamePreference);
        } catch (Throwable t) {
            Log.d(TAG, "Couldn't get preference", t);
        }
        messageController = new MessageController(messagePortPreference, usernamePreference);
        chemLightController = new ChemLightController(messageController, usernamePreference);
        try {
            int wkid = Integer.parseInt(sp.getString(getString(R.string.pref_angularUnits), Integer.toString(AngularUnit.Code.DEGREE)));
            angularUnitPreference = (AngularUnit) AngularUnit.create(wkid);
        } catch (Throwable t) {
            Log.d(TAG, "Couldn't get preference", t);
        }
        try {
            messagePortPreference = Integer.parseInt(sp.getString(getString(R.string.pref_messagePort), Integer.toString(messagePortPreference)));
            changePort(messagePortPreference);
            messageController.startReceiving();
        } catch (Throwable t) {
            Log.d(TAG, "Couldn't get preference", t);
        }
        try {
            positionReportsPreference = sp.getBoolean(getString(R.string.pref_positionReports), false);
        } catch (Throwable t) {
            Log.d(TAG, "Couldn't get preference", t);
        }
        try {
            positionReportsPeriodPreference = Integer.parseInt(sp.getString(
                    getString(R.string.pref_positionReportPeriod),
                    Integer.toString(positionReportsPeriodPreference)));
            if (0 >= positionReportsPeriodPreference) {
                positionReportsPeriodPreference = PositionReportController.DEFAULT_PERIOD;
                sp.edit().putString(getString(R.string.pref_positionReportPeriod), Integer.toString(positionReportsPeriodPreference)).commit();
            }
        } catch (Throwable t) {
            Log.d(TAG, "Couldn't get preference", t);
        }
        try {
            vehicleTypePreference = sp.getString(getString(R.string.pref_vehicleType), vehicleTypePreference);
        } catch (Throwable t) {
            Log.d(TAG, "Couldn't get preference", t);
        }
        try {
            uniqueIdPreference = sp.getString(getString(R.string.pref_uniqueId), uniqueIdPreference);
            //Make sure this one gets set in case we just generated it
            sp.edit().putString(getString(R.string.pref_uniqueId), uniqueIdPreference).commit();
        } catch (Throwable t) {
            Log.d(TAG, "Couldn't get preference", t);
        }
        try {
            sicPreference = sp.getString(getString(R.string.pref_sic), sicPreference);
        } catch (Throwable t) {
            Log.d(TAG, "Couldn't get preference", t);
        }

        PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
                .registerOnSharedPreferenceChangeListener(preferenceChangeListener);
        
//        //TODO implement Geo URIs
//        Uri intentData = getIntent().getData();
//        if (null != intentData) {
//            //intentData should be a Geo URI with a location to which we should navigate
//        }
        
        setContentView(R.layout.main);
        adjustLayoutForOrientation(getResources().getConfiguration().orientation);

        final MapView mapView = (MapView) findViewById(R.id.map);
        
        mapView.setOnPanListener(new OnPanListener() {
            
            private static final long serialVersionUID = 0x58d30af8d168f63aL;

            @Override
            public void prePointerUp(float fromx, float fromy, float tox, float toy) {}
            
            @Override
            public void prePointerMove(float fromx, float fromy, float tox, float toy) {
                setFollowMe(false);
            }
            
            @Override
            public void postPointerUp(float fromx, float fromy, float tox, float toy) {}
            
            @Override
            public void postPointerMove(float fromx, float fromy, float tox, float toy) {}
            
        });

        mapController = new MapController(mapView, getAssets(), new LayerErrorListener(this));
        northArrowView = (NorthArrowView) findViewById(R.id.northArrowView);
        northArrowView.setMapController(mapController);
        northArrowView.startRotation();
        try {
            mil2525cController = new AdvancedSymbolController(
                    mapController,
                    getAssets(),
                    getString(R.string.sym_dict_dirname),
                    getResources().getDrawable(R.drawable.ic_spot_report),
                    messageController);
        } catch (IOException e) {
            Log.e(TAG, "Couldn't instantiate AdvancedSymbolController", e);
        }
        
        spotReportController = new SpotReportController(mapController, messageController);
        
        positionReportController = new PositionReportController(
                mapController.getLocationController(),
                messageController,
                usernamePreference,
                vehicleTypePreference,
                uniqueIdPreference,
                sicPreference);
        positionReportController.setPeriod(positionReportsPeriodPreference);
        positionReportController.setEnabled(positionReportsPreference);

        mapController.getLocationController().addListener(new LocationListener() {
            
            @Override
            public void onLocationChanged(final Location location) {
                if (null != location) {
                    //Do this in a thread in case we need to calculate the speed
                    new Thread() {
                        public void run() {
                            Message msg = new Message();
                            msg.obj = location;
                            locationChangeHandler.sendMessage(msg);
                        }
                    }.start();
                }
            }
            
            @Override
            public void onStateChanged(LocationProviderState state) {
                
            }
        });
        
        messageController.addListener(new MessageListener(mil2525cController));        

        if (null != mapController.getLastMapConfig()) {
            String viewshedElevationPath = mapController.getLastMapConfig().getViewshedElevationPath();
            if (null == viewshedElevationPath) {
                try {
                    viewshedElevationPath = Utilities.readMapConfig(getApplicationContext(), getAssets()).getViewshedElevationPath();
                } catch (Throwable t) {
                    Log.e(TAG, "Couldn't set up viewshed", t);
                }
            }
            createViewshedController(viewshedElevationPath);
        }
        mapController.addMapConfigListener(new MapConfigListener() {
            
            @Override
            public void mapConfigRead(MapConfig mapConfig) {
                String viewshedElevationPath = mapConfig.getViewshedElevationPath();
                if (null == viewshedElevationPath) {
                    try {
                        viewshedElevationPath = Utilities.readMapConfig(getApplicationContext(), getAssets()).getViewshedElevationPath();
                    } catch (Throwable t) {
                        Log.e(TAG, "Couldn't set up viewshed", t);
                    }
                }
                createViewshedController(viewshedElevationPath);
            }
        });

        clockTimerTask = new TimerTask() {
            
            private final Handler handler = new Handler() {
                @Override
                public void handleMessage(Message msg) {
                    try {
                        if (null != msg.obj) {
                            ((TextView) findViewById(R.id.textView_displayTime)).setText(getString(R.string.display_time) + msg.obj);
                        }
                    } catch (Throwable t) {
                        Log.i(TAG, "Couldn't update time", t);
                    }
                }
            };
            
            @Override
            public void run() {                
                if (null != mapController) {
                    Message msg = new Message();
                    msg.obj = Utilities.DATE_FORMAT_MILITARY_ZULU.format(new Date());
                    handler.sendMessage(msg);
                }
            }
            
        };
        clockTimer.schedule(clockTimerTask, 0, Utilities.ANIMATION_PERIOD_MS);
        
        ((RadioGroup) findViewById(R.id.radioGroup_chemLightButtons)).setOnCheckedChangeListener(chemLightCheckedChangeListener);
    }
    
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        
        if (null != getSpatialReference()) {
            outState.putInt(LAST_WKID_KEY, getSpatialReference().getID());
        }
    }
    
    @Override
    protected void onRestoreInstanceState(Bundle savedInstanceState) {
        super.onRestoreInstanceState(savedInstanceState);
        
        int wkid = savedInstanceState.getInt(LAST_WKID_KEY);
        if (0 != wkid) {
            lastSpatialReference = SpatialReference.create(wkid);
        }
    }
    
    private SpatialReference getSpatialReference() {
        if (null != mapController && null != mapController.getSpatialReference()) {
            return mapController.getSpatialReference();
        } else {
            return lastSpatialReference;
        }
    }
    
    private void createViewshedController(String elevationPath) {
        if (null != viewshedController && null != viewshedController.getLayer()) {
            mapController.removeLayer(viewshedController.getLayer());
        }
        try {
            viewshedController = new ViewshedController(elevationPath);
            mapController.addLayer(viewshedController.getLayer());
            findViewById(R.id.toggleButton_viewshed).setVisibility(View.VISIBLE);
        } catch (Exception e) {
            Log.d(TAG, "Couldn't set up ViewshedController", e);
            findViewById(R.id.toggleButton_viewshed).setVisibility(View.INVISIBLE);
        }
    }
    
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        adjustLayoutForOrientation(newConfig.orientation);
    }
    
    private void adjustLayoutForOrientation(int orientation) {
        View displayView = findViewById(R.id.tableLayout_display);
        if (displayView.getLayoutParams() instanceof RelativeLayout.LayoutParams) {
            RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) displayView.getLayoutParams();
            switch (orientation) {
                case Configuration.ORIENTATION_LANDSCAPE: {
                    params.addRule(RelativeLayout.RIGHT_OF, R.id.toggleButton_grid);
                    params.addRule(RelativeLayout.LEFT_OF, R.id.toggleButton_followMe);
                    params.addRule(RelativeLayout.ALIGN_BOTTOM, R.id.imageButton_zoomOut);
                    params.addRule(RelativeLayout.ABOVE, -1);
                    break;
                }
                case Configuration.ORIENTATION_PORTRAIT:
                default: {
                    params.addRule(RelativeLayout.RIGHT_OF, -1);
                    params.addRule(RelativeLayout.LEFT_OF, R.id.imageButton_zoomIn);
                    params.addRule(RelativeLayout.ALIGN_BOTTOM, R.id.imageButton_zoomIn);
                    params.addRule(RelativeLayout.ABOVE, R.id.toggleButton_grid);
                }
            }
            displayView.setLayoutParams(params);
        }
    }
    
    private boolean isFollowMe() {
        ToggleButton followMeButton = (ToggleButton) findViewById(R.id.toggleButton_followMe);
        if (null != followMeButton) {
            return followMeButton.isChecked();
        } else {
            return false;
        }
    }
    
    private void setFollowMe(boolean isFollowMe) {
        ToggleButton followMeButton = (ToggleButton) findViewById(R.id.toggleButton_followMe);
        if (null != followMeButton) {
            if (isFollowMe != followMeButton.isChecked()) {
                followMeButton.performClick();
            }
        }
    }
    
    public MapController getMapController() {
        return mapController;
    }

    @Override
    public void beforePanToMgrs(String mgrs) {
        wasFollowMeBeforeMgrs = isFollowMe();
        setFollowMe(false);
    }

    @Override
    public void onPanToMgrsError(String mgrs) {
        if (wasFollowMeBeforeMgrs) {
            setFollowMe(true);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        PreferenceManager.getDefaultSharedPreferences(getApplicationContext())
                .unregisterOnSharedPreferenceChangeListener(preferenceChangeListener);
        if (null != viewshedController) {
            viewshedController.dispose();
        }
    }
    
    @Override
    protected void onPause() {
        super.onPause();
        mapController.pause();
        northArrowView.stopRotation();
        messageController.stopReceiving();
        positionReportController.setEnabled(false);
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        mapController.unpause();
        northArrowView.startRotation();
        messageController.startReceiving();
        positionReportController.setEnabled(true);
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.map_menu, menu);
        return super.onCreateOptionsMenu(menu);
    }
    
    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        String key = getString(R.string.pref_labels);
        if (!prefs.contains(key)) {
            prefs.edit().putBoolean(key, true).commit();
        }
        boolean labelsOn = prefs.getBoolean(key, true);
        mil2525cController.setShowLabels(labelsOn);
        MenuItem menuItem_toggleLabels = menu.findItem(R.id.toggle_labels);
        menuItem_toggleLabels.setIcon(labelsOn ? R.drawable.ic_action_labels : R.drawable.ic_action_labels_off);
        menuItem_toggleLabels.setChecked(labelsOn);
        return super.onPrepareOptionsMenu(menu);
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.add_layer:
                //Present Add Layer from Web dialog
                if (null == addLayerDialogFragment) {
                    addLayerDialogFragment = new AddLayerDialogFragment();
                    addLayerDialogFragment.setAddLayerFromFileRequestCode(ADD_LAYER_FROM_FILE);
                }
                addLayerDialogFragment.show(getFragmentManager(), getString(R.string.add_layer_fragment_tag));
                return true;
            case R.id.clear_messages:
                //Present Clear Messages dialog
                if (null == clearMessagesDialogFragment) {
                    clearMessagesDialogFragment = new ClearMessagesDialogFragment();
                }
                clearMessagesDialogFragment.show(getFragmentManager(), getString(R.string.clear_messages_fragment_tag));
                return true;
            case R.id.go_to_mgrs:
                //Present Go to MGRS dialog
                if (null == goToMgrsDialogFragment) {
                    goToMgrsDialogFragment = new GoToMgrsDialogFragment();
                }
                goToMgrsDialogFragment.show(getFragmentManager(), getString(R.string.go_to_mgrs_fragment_tag));
                return true;
            case R.id.set_location_mode:
                //Present Set Location Mode dialog
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle(R.string.set_location_mode)
                        .setNegativeButton(R.string.cancel, null)
                        .setSingleChoiceItems(
                                new String[] {
                                        getString(R.string.option_location_service),
                                        getString(R.string.option_simulation_builtin),
                                        getString(R.string.option_simulation_file)},
                                mapController.getLocationController().getMode() == LocationMode.LOCATION_SERVICE ? 0 : 
                                    null == mapController.getLocationController().getGpxFile() ? 1 : 2,
                                new DialogInterface.OnClickListener() {

                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                try {
                                    if (2 == which) {
                                        //Present file chooser
                                        Intent getContentIntent = FileUtils.createGetContentIntent();
                                        Intent intent = Intent.createChooser(getContentIntent, "Select a file");
                                        startActivityForResult(intent, REQUEST_CHOOSER);
                                    } else {
                                        mapController.getLocationController().setGpxFile(null, true);
                                        mapController.getLocationController().setMode(
                                                0 == which ? LocationMode.LOCATION_SERVICE : LocationMode.SIMULATOR,
                                                true);
                                        mapController.getLocationController().start();
                                    }
                                } catch (Exception e) {
                                    Log.d(TAG, "Couldn't set location mode", e);
                                } finally {
                                    dialog.dismiss();
                                }
                            }
                            
                        });
                AlertDialog dialog = builder.create();
                dialog.show();
                return true;
            case R.id.settings:
                Intent intent = new Intent(this, SettingsActivity.class);
                startActivityForResult(intent, SETTINGS_ACTIVITY);
                return true;
            case R.id.toggle_labels:
                item.setChecked(!item.isChecked());
                item.setIcon(item.isChecked() ? R.drawable.ic_action_labels : R.drawable.ic_action_labels_off);
                SharedPreferences prefs = getPreferences(MODE_PRIVATE);
                String key = getString(R.string.pref_labels);
                prefs.edit().putBoolean(key, item.isChecked()).commit();
                mil2525cController.setShowLabels(item.isChecked());
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }
    
    /**
     * Called when an activity called by this activity returns a result. This method was initially
     * added to handle the result of choosing a GPX file for the LocationSimulator.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, final Intent data) {
        switch (requestCode) {
        case REQUEST_CHOOSER:
            if (resultCode == RESULT_OK) {  
                final Uri uri = data.getData();
                File file = new File(FileUtils.getPath(this, uri));
                mapController.getLocationController().setGpxFile(file, true);
                try {
                    mapController.getLocationController().setMode(LocationMode.SIMULATOR, true);
                    mapController.getLocationController().start();
                } catch (Exception e) {
                    Log.d(TAG, "Could not start simulator", e);
                }
            }
            break;
        case SETTINGS_ACTIVITY:
            if (null != data && data.getBooleanExtra(getString(R.string.pref_resetApp), false)) {
                try {
                    mapController.reset();
                } catch (Throwable t) {
                    Log.e(TAG, "Could not reset map", t);
                }
            }
            break;
        case SPOT_REPORT_ACTIVITY:
            if (null != data && null != data.getExtras()) {
                final SpotReport spotReport = (SpotReport) data.getExtras().get(getPackageName() + "." + SpotReportActivity.SPOT_REPORT_EXTRA_NAME);
                if (null != spotReport) {
                    new Thread() {
                        
                        @Override
                        public void run() {
                            String mgrs = (String) data.getExtras().get(getPackageName() + "." + SpotReportActivity.MGRS_EXTRA_NAME);
                            if (null != mgrs) {
                                Point pt = mapController.mgrsToPoint(mgrs);
                                if (null != pt) {
                                    spotReport.setLocationX(pt.getX());
                                    spotReport.setLocationY(pt.getY());
                                    if (null != getSpatialReference()) {
                                        spotReport.setLocationWkid(getSpatialReference().getID());
                                    }
                                }
                            }                
                            try {
                                spotReportController.sendSpotReport(spotReport, usernamePreference);
                            } catch (Exception e) {
                                Log.e(TAG, "Could not send spot report", e);
                                //TODO notify user?
                            }
                        }
                    }.start();
                }
            }
            break;
        case ADD_LAYER_FROM_FILE:
            addLayerDialogFragment.onActivityResult(requestCode, resultCode, data);
            break;
        default:
            super.onActivityResult(requestCode, resultCode, data);
        }
    }
    
    public void imageButton_zoomIn_clicked(View view) {
	mapController.zoomIn();
    }
    
    public void imageButton_zoomOut_clicked(View view) {
	mapController.zoomOut();
    }
    
    public void imageButton_openBasemapPanel_clicked(final View view) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.choose_basemap)
                .setNegativeButton(R.string.cancel, null);
        List<BasemapLayer> basemapLayers = mapController.getBasemapLayers();
        String[] basemapLayerNames = new String[basemapLayers.size()];
        for (int i = 0; i < basemapLayers.size(); i++) {
            basemapLayerNames[i] = basemapLayers.get(i).getLayer().getName();
        }
        builder.setSingleChoiceItems(
                basemapLayerNames,
                mapController.getVisibleBasemapLayerIndex(),
                new DialogInterface.OnClickListener() {
            
            public void onClick(DialogInterface dialog, int which) {
                mapController.setVisibleBasemapLayerIndex(which);
                dialog.dismiss();
            }
            
        });
        AlertDialog dialog = builder.create();
        dialog.show();
    }
    
    public void toggleButton_status911_clicked(final View view) {
        positionReportController.setStatus911(((ToggleButton) view).isChecked());
    }

    public void onValidLayerInfos(LayerInfo[] layerInfos) {
        for (int i = layerInfos.length - 1; i >= 0; i--) {
            mapController.addLayer(layerInfos[i]);
        }
    }
    
    public void toggleButton_grid_clicked(final View view) {
        mapController.setGridVisible(((ToggleButton) view).isChecked());
    }
    
    public void northArrowView_clicked(View view) {
        mapController.setRotation(0);
    }

    public void toggleButton_followMe_clicked(final View view) {
        mapController.setAutoPan(((ToggleButton) view).isChecked());
    }
    
    public void toggleButton_chemLightRed_clicked(final View view) {
        listenForChemLightTap(view, Color.RED);
    }

    public void toggleButton_chemLightYellow_clicked(final View view) {
        listenForChemLightTap(view, Color.YELLOW);
    }

    public void toggleButton_chemLightGreen_clicked(final View view) {
        listenForChemLightTap(view, Color.GREEN);
    }

    public void toggleButton_chemLightBlue_clicked(final View view) {
        listenForChemLightTap(view, Color.BLUE);
    }
    
    private void listenForChemLightTap(View button, final int color) {
        if (null != button && null != button.getParent() && button.getParent() instanceof RadioGroup) {
            ((RadioGroup) button.getParent()).check(button.getId());
            ((CompoundButton) findViewById(R.id.toggleButton_spotReport)).setChecked(false);
            ((CompoundButton) findViewById(R.id.toggleButton_viewshed)).setChecked(false);
        }
        if (null != button && button instanceof ToggleButton && ((ToggleButton) button).isChecked()) {
            mapController.setOnSingleTapListener(new OnSingleTapListener() {
                
                private static final long serialVersionUID = 7556722404624511983L;

                @Override
                public void onSingleTap(final float x, final float y) {
                    new Thread() {
                        public void run() {
                            final double[] mapPoint = mapController.toMapPoint((int) x, (int) y);
                            if (null != mapPoint && null != getSpatialReference()) {
                                chemLightController.sendChemLight(mapPoint[0], mapPoint[1], getSpatialReference().getID(), color);
                            } else {
                                Log.i(TAG, "Couldn't convert chem light to map coordinates");
                            }
                        };
                    }.start();
                }
            });
        } else {
            mapController.setOnSingleTapListener(defaultOnSingleTapListener);
        }
    }
    
    public void toggleButton_spotReport_clicked(final View button) {
        ((RadioGroup) findViewById(R.id.radioGroup_chemLightButtons)).clearCheck();
        ((CompoundButton) findViewById(R.id.toggleButton_viewshed)).setChecked(false);
        if (null != button && button instanceof ToggleButton && ((ToggleButton) button).isChecked()) {
            mapController.setOnSingleTapListener(new OnSingleTapListener() {
                
                private static final long serialVersionUID = -1281957679086948899L;

                @Override
                public void onSingleTap(final float x, final float y) {
                    Point pt = mapController.toMapPointObject((int) x, (int) y);
                    Intent intent = new Intent(SquadLeaderActivity.this, SpotReportActivity.class);
                    if (null != pt) {
                        intent.putExtra(getPackageName() + "." + SpotReportActivity.MGRS_EXTRA_NAME, mapController.pointToMgrs(pt));
                    }
                    startActivityForResult(intent, SPOT_REPORT_ACTIVITY);
                }
            });
        } else {
            mapController.setOnSingleTapListener(defaultOnSingleTapListener);
        }
    }
    
    public void toggleButton_viewshed_clicked(final View button) {
        ((RadioGroup) findViewById(R.id.radioGroup_chemLightButtons)).clearCheck();
        ((CompoundButton) findViewById(R.id.toggleButton_spotReport)).setChecked(false);
        if (null != button && button instanceof ToggleButton && ((ToggleButton) button).isChecked()) {
            mapController.setOnSingleTapListener(new OnSingleTapListener() {
                
                private static final long serialVersionUID = 4291964186019821102L;

                @Override
                public void onSingleTap(final float x, final float y) {
                    if (null != viewshedController) {
                        Point pt = mapController.toMapPointObject((int) x, (int) y);
                        viewshedController.calculateViewshed(pt);
                        findViewById(R.id.imageButton_clearViewshed).setVisibility(View.VISIBLE);
                    }
                }
            });
        } else {
            mapController.setOnSingleTapListener(defaultOnSingleTapListener);
        }
    }
    
    public void imageButton_clearViewshed_clicked(final View button) {
        if (null != viewshedController) {
            viewshedController.getLayer().setVisible(false);
        }
        button.setVisibility(View.INVISIBLE);
    }
    
    private void changePort(int newPort) {
        messageController.setPort(newPort);
    }

    @Override
    public AdvancedSymbolController getAdvancedSymbolController() {
        return mil2525cController;
    }
    
    private OnSingleTapListener createDefaultOnSingleTapListener() {
        return new OnSingleTapListener() {
            
            private static final long serialVersionUID = 3247725674465463146L;

            @Override
            public void onSingleTap(float x, float y) {
                Callout callout = mapController.getCallout();
                //Identify a chem light
                poppedUpChemLight = mil2525cController.identifyOneGraphic("chemlights", x, y, 5);
                if (null != poppedUpChemLight) {
                    View calloutView = getLayoutInflater().inflate(R.layout.chem_light_callout, null);
                    callout.setStyle(R.xml.chem_light_callout_style);
                    callout.refresh();
                    callout.animatedShow((Point) poppedUpChemLight.getGeometry(), calloutView);
                } else {
                    callout.animatedHide();
                }
            }
        };
    }
    
    public void chemLightColorChangeClicked(View view) {
        if (null != poppedUpChemLight && null != view && null != view.getTag() && view.getTag() instanceof String) {
            try {
                final Point pt = (Point) poppedUpChemLight.getGeometry();
                final SpatialReference sr = (null != poppedUpChemLight.getSpatialReference())
                        ? poppedUpChemLight.getSpatialReference() : getSpatialReference();
                final int rgb = Integer.parseInt((String) view.getTag());
                final String id = (String) poppedUpChemLight.getAttributeValue(Geomessage.ID_FIELD_NAME);
                new Thread() {
                    public void run() {
                        chemLightController.sendChemLight(pt.getX(), pt.getY(), sr.getID(), rgb, id);
                    }
                }.start();
            } catch (NumberFormatException nfe) {
                Log.e(TAG, "Couldn't parse RGB " + view.getTag(), nfe);
            }
        }
        
        closeChemLightCallout();
    }
    
    public void chemLightRemoveClicked(View view) {
        if (null != poppedUpChemLight) {
            final String id = (String) poppedUpChemLight.getAttributeValue(Geomessage.ID_FIELD_NAME);
            new Thread() {
                public void run() {
                    chemLightController.removeChemLight(id);
                }
            }.start();
        }
        
        closeChemLightCallout();
    }
    
    private void closeChemLightCallout() {
        poppedUpChemLight = null;
        mapController.getCallout().animatedHide();
    }
    
}