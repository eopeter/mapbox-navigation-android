package com.mapbox.services.android.navigation.testapp.activity.navigationui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.Resources;
import android.location.Location;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.transition.TransitionManager;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.BaseTransientBottomBar;
import com.google.android.material.snackbar.Snackbar;
import com.mapbox.android.core.location.LocationEngine;
import com.mapbox.android.core.location.LocationEngineCallback;
import com.mapbox.android.core.location.LocationEngineProvider;
import com.mapbox.android.core.location.LocationEngineRequest;
import com.mapbox.android.core.location.LocationEngineResult;
import com.mapbox.android.gestures.MoveGestureDetector;
import com.mapbox.api.directions.v5.models.DirectionsRoute;
import com.mapbox.api.directions.v5.models.RouteOptions;
import com.mapbox.geojson.Point;
import com.mapbox.mapboxsdk.Mapbox;
import com.mapbox.mapboxsdk.camera.CameraPosition;
import com.mapbox.mapboxsdk.camera.CameraUpdate;
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory;
import com.mapbox.mapboxsdk.geometry.LatLng;
import com.mapbox.mapboxsdk.geometry.LatLngBounds;
import com.mapbox.mapboxsdk.location.modes.RenderMode;
import com.mapbox.mapboxsdk.maps.MapView;
import com.mapbox.mapboxsdk.maps.MapboxMap;
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback;
import com.mapbox.mapboxsdk.maps.Style;
import com.mapbox.navigation.base.extensions.MapboxRouteOptionsUtils;
import com.mapbox.navigation.base.options.NavigationOptions;
import com.mapbox.navigation.base.trip.model.RouteProgress;
import com.mapbox.navigation.core.MapboxNavigation;
import com.mapbox.navigation.core.directions.session.RoutesObserver;
import com.mapbox.navigation.core.trip.session.LocationObserver;
import com.mapbox.navigation.core.trip.session.OffRouteObserver;
import com.mapbox.navigation.core.trip.session.RouteProgressObserver;
import com.mapbox.navigation.ui.camera.DynamicCamera;
import com.mapbox.navigation.ui.camera.NavigationCamera;
import com.mapbox.navigation.ui.camera.NavigationCameraUpdate;
import com.mapbox.navigation.ui.instruction.InstructionView;
import com.mapbox.navigation.ui.map.NavigationMapboxMap;
import com.mapbox.navigation.ui.voice.NavigationSpeechPlayer;
import com.mapbox.navigation.ui.voice.SpeechPlayerProvider;
import com.mapbox.navigation.ui.voice.VoiceInstructionLoader;
import com.mapbox.services.android.navigation.testapp.R;
import com.mapbox.services.android.navigation.testapp.activity.HistoryActivity;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import okhttp3.Cache;
import timber.log.Timber;

public class ComponentNavigationActivity extends HistoryActivity implements OnMapReadyCallback,
        MapboxMap.OnMapLongClickListener, RouteProgressObserver, LocationObserver, RoutesObserver,
        OffRouteObserver {

  private static final int FIRST = 0;
  private static final int ONE_HUNDRED_MILLISECONDS = 100;
  private static final int BOTTOMSHEET_PADDING_MULTIPLIER = 4;
  private static final int TWO_SECONDS_IN_MILLISECONDS = 2000;
  private static final double BEARING_TOLERANCE = 90d;
  private static final String LONG_PRESS_MAP_MESSAGE = "Long press the map to select a destination.";
  private static final String SEARCHING_FOR_GPS_MESSAGE = "Searching for GPS...";
  private static final String COMPONENT_NAVIGATION_INSTRUCTION_CACHE = "component-navigation-instruction-cache";
  private static final long TEN_MEGABYTE_CACHE_SIZE = 10 * 1024 * 1024;
  private static final int ZERO_PADDING = 0;
  private static final double DEFAULT_ZOOM = 18.0;
  private static final double DEFAULT_TILT = 0d;
  private static final double DEFAULT_BEARING = 0d;
  private static final long UPDATE_INTERVAL_IN_MILLISECONDS = 1000;
  private static final long FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS = 500;

  @BindView(R.id.componentNavigationLayout)
  ConstraintLayout navigationLayout;

  @BindView(R.id.mapView)
  MapView mapView;

  @BindView(R.id.instructionView)
  InstructionView instructionView;

  @BindView(R.id.startNavigationFab)
  FloatingActionButton startNavigationFab;

  @BindView(R.id.cancelNavigationFab)
  FloatingActionButton cancelNavigationFab;

  @BindView(R.id.sendAnomalyFab)
  FloatingActionButton sendAnomalyFab;

  private final ComponentActivityLocationCallback callback = new ComponentActivityLocationCallback(this);
  private LocationEngine locationEngine;
  private MapboxNavigation navigation;
  private NavigationSpeechPlayer speechPlayer;
  private NavigationMapboxMap navigationMap;
  private Location lastLocation;
  private DirectionsRoute route;
  private Point destination;
  private MapState mapState;
  private boolean isFreeDriveEnabled = false;
  private boolean isFreeDriveCameraConfigured = false;
  private Handler handler = new Handler();
  private Runnable updateTracking = () -> navigationMap.updateCameraTrackingMode(NavigationCamera.NAVIGATION_TRACKING_MODE_GPS);

  private enum MapState {
    INFO,
    NAVIGATION
  }

  private DynamicCamera camera;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    // For styling the InstructionView
    setTheme(R.style.CustomInstructionView);
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_component_navigation);
    ButterKnife.bind(this);
    mapView.onCreate(savedInstanceState);

    // Will call onMapReady
    mapView.getMapAsync(this);
  }

  @Override
  public void onMapReady(@NonNull MapboxMap mapboxMap) {
    mapboxMap.setStyle(new Style.Builder().fromUrl(getString(R.string.navigation_guidance_day)), style -> {
      mapState = MapState.INFO;
      navigationMap = new NavigationMapboxMap(mapView, mapboxMap);

      // For Location updates
      initializeLocationEngine();

      // For navigation logic / processing
      initializeNavigation(mapboxMap);
      navigationMap.updateCameraTrackingMode(NavigationCamera.NAVIGATION_TRACKING_MODE_NONE);
      navigationMap.updateLocationLayerRenderMode(RenderMode.GPS);

      // For voice instructions
      initializeSpeechPlayer();

      navigationMap.retrieveMap().addOnMoveListener(new MapboxMap.OnMoveListener() {
        @Override
        public void onMoveBegin(@NonNull MoveGestureDetector detector) {
          handler.removeCallbacks(updateTracking);
        }

        @Override
        public void onMove(@NonNull MoveGestureDetector detector) {

        }

        @Override
        public void onMoveEnd(@NonNull MoveGestureDetector detector) {
          if (isFreeDriveEnabled) {
            trackCameraDelayed();
          }
        }
      });
    });
  }

  @Override
  public boolean onMapLongClick(@NonNull LatLng point) {
    // Only reverse geocode while we are not in navigation
    if (mapState.equals(MapState.NAVIGATION)) {
      return false;
    }

    // Fetch the route with this given point
    destination = Point.fromLngLat(point.getLongitude(), point.getLatitude());
    calculateRouteWith(destination, false);

    // Clear any existing markers and add new one
    navigationMap.clearMarkers();
    navigationMap.addMarker(this, destination);

    // Update camera to new destination
    moveCameraToInclude(destination);
    vibrate();
    return false;
  }

  @OnClick(R.id.startNavigationFab)
  public void onStartNavigationClick(FloatingActionButton floatingActionButton) {
    floatingActionButton.hide();
    quickStartNavigation();
  }

  @OnClick(R.id.cancelNavigationFab)
  public void onCancelNavigationClick(FloatingActionButton floatingActionButton) {
    navigationMap.updateCameraTrackingMode(NavigationCamera.NAVIGATION_TRACKING_MODE_NONE);
    // Transition to info state
    mapState = MapState.INFO;

    floatingActionButton.hide();

    // Hide the InstructionView
    TransitionManager.beginDelayedTransition(navigationLayout);
    instructionView.setVisibility(View.INVISIBLE);

    // Reset map camera and pitch
    resetMapAfterNavigation();

    // Add back regular location listener
    addLocationEngineListener();
  }

  @OnClick(R.id.sendAnomalyFab)
  public void onSendAnomalyClick(FloatingActionButton floatingActionButton) {
    addEventToHistoryFile("anomaly");
  }

  /*
   * LocationObserver
   */

  @Override
  public void onRawLocationChanged(@NotNull Location rawLocation) {
  }

  @Override
  public void onEnhancedLocationChanged(
          @NotNull Location enhancedLocation,
          @NotNull List<? extends Location> keyPoints
  ) {
    checkFirstUpdate(enhancedLocation);
    updateLocation(enhancedLocation);
  }

  /*
   * LocationObserver end
   */

  /*
   * RouteProgressObserver
   */
  @Override
  public void onRouteProgressChanged(@NotNull RouteProgress routeProgress) {
    instructionView.updateDistanceWith(routeProgress);
  }

  /*
   * RouteProgressObserver end
   */


  @Override
  public void onOffRouteStateChanged(boolean offRoute) {
    calculateRouteWith(destination, offRoute);
  }

  /*
   * Activity lifecycle methods
   */

  @Override
  public void onResume() {
    super.onResume();
    mapView.onResume();
  }

  @Override
  public void onPause() {
    super.onPause();
    mapView.onPause();
  }

  @Override
  protected void onStart() {
    super.onStart();
    mapView.onStart();
    if (navigationMap != null) {
      navigationMap.onStart();
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    mapView.onSaveInstanceState(outState);
  }

  @Override
  protected void onStop() {
    super.onStop();
    mapView.onStop();
    if (navigationMap != null) {
      navigationMap.onStop();
    }
  }

  @Override
  public void onLowMemory() {
    super.onLowMemory();
    mapView.onLowMemory();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    mapView.onDestroy();

    // Ensure proper shutdown of the SpeechPlayer
    if (speechPlayer != null) {
      speechPlayer.onDestroy();
    }

    // Prevent leaks
    removeLocationEngineListener();

    camera.clearMap();
    // MapboxNavigation will shutdown the LocationEngine
    navigation.unregisterRouteProgressObserver(this);
    navigation.unregisterLocationObserver(this);
    navigation.unregisterOffRouteObserver(this);
    navigation.onDestroy();

  }

  void checkFirstUpdate(Location location) {
    if (lastLocation == null) {
      moveCameraTo(location);
      // Allow navigationMap clicks now that we have the current Location
      navigationMap.retrieveMap().addOnMapLongClickListener(this);
      showSnackbar(LONG_PRESS_MAP_MESSAGE, BaseTransientBottomBar.LENGTH_LONG);
    }
  }

  void updateLocation(Location location) {
    lastLocation = location;
    navigationMap.updateLocation(location);

    if (isFreeDriveEnabled && !isFreeDriveCameraConfigured) {
      navigationMap.retrieveMap().getLocationComponent().zoomWhileTracking(DEFAULT_ZOOM);
      trackCameraDelayed();
      isFreeDriveCameraConfigured = true;
    }
  }

  private void initializeSpeechPlayer() {
    String english = Locale.US.getLanguage();
    Cache cache = new Cache(new File(getApplication().getCacheDir(), COMPONENT_NAVIGATION_INSTRUCTION_CACHE),
            TEN_MEGABYTE_CACHE_SIZE);
    VoiceInstructionLoader voiceInstructionLoader = new VoiceInstructionLoader(getApplication(),
            Mapbox.getAccessToken(), cache);
    SpeechPlayerProvider speechPlayerProvider = new SpeechPlayerProvider(getApplication(), english, true,
            voiceInstructionLoader);
    speechPlayer = new NavigationSpeechPlayer(speechPlayerProvider);
  }

  @SuppressLint("MissingPermission")
  private void initializeLocationEngine() {
    locationEngine = LocationEngineProvider.getBestLocationEngine(getApplicationContext());
    showSnackbar(SEARCHING_FOR_GPS_MESSAGE, BaseTransientBottomBar.LENGTH_SHORT);
  }

  private void initializeNavigation(MapboxMap mapboxMap) {
    navigation = new MapboxNavigation(this, Mapbox.getAccessToken(),
            new NavigationOptions.Builder().build(), locationEngine);
    addNavigationForHistory(navigation);
    addLocationEngineListener();
    sendAnomalyFab.show();
    camera = new DynamicCamera(mapboxMap);
    navigation.registerRouteProgressObserver(this);
    navigation.registerLocationObserver(this);
    navigation.registerOffRouteObserver(this);
    navigationMap.addProgressChangeListener(navigation);
  }

  private void showSnackbar(String text, int duration) {
    Snackbar.make(navigationLayout, text, duration).show();
  }

  private void moveCameraTo(Location location) {
    CameraPosition cameraPosition = buildCameraPositionFrom(location, location.getBearing());
    navigationMap.retrieveMap().animateCamera(
            CameraUpdateFactory.newCameraPosition(cameraPosition), TWO_SECONDS_IN_MILLISECONDS
    );
  }

  private void moveCameraToInclude(Point destination) {
    LatLng origin = new LatLng(lastLocation);
    LatLngBounds bounds = new LatLngBounds.Builder()
            .include(origin)
            .include(new LatLng(destination.latitude(), destination.longitude()))
            .build();
    Resources resources = getResources();
    int routeCameraPadding = (int) resources.getDimension(R.dimen.component_navigation_route_camera_padding);
    int[] padding = {routeCameraPadding, routeCameraPadding, routeCameraPadding, routeCameraPadding};
    CameraPosition cameraPosition = navigationMap.retrieveMap().getCameraForLatLngBounds(bounds, padding);
    navigationMap.retrieveMap().animateCamera(
            CameraUpdateFactory.newCameraPosition(cameraPosition), TWO_SECONDS_IN_MILLISECONDS
    );
  }

  private void moveCameraOverhead() {
    if (lastLocation == null) {
      return;
    }
    CameraPosition cameraPosition = buildCameraPositionFrom(lastLocation, DEFAULT_BEARING);
    navigationMap.retrieveMap().animateCamera(
            CameraUpdateFactory.newCameraPosition(cameraPosition), TWO_SECONDS_IN_MILLISECONDS
    );
  }

  @Nullable
  private CameraUpdate cameraOverheadUpdate() {
    if (lastLocation == null) {
      return null;
    }
    CameraPosition cameraPosition = buildCameraPositionFrom(lastLocation, DEFAULT_BEARING);
    return CameraUpdateFactory.newCameraPosition(cameraPosition);
  }

  @NonNull
  private CameraPosition buildCameraPositionFrom(Location location, double bearing) {
    return buildCameraPositionFrom(location, bearing, DEFAULT_ZOOM);
  }

  @NonNull
  private CameraPosition buildCameraPositionFrom(Location location, double bearing, double zoom) {
    return new CameraPosition.Builder()
            .zoom(zoom)
            .target(new LatLng(location.getLatitude(), location.getLongitude()))
            .bearing(bearing)
            .tilt(DEFAULT_TILT)
            .build();
  }

  private void adjustMapPaddingForNavigation() {
    Resources resources = getResources();
    int mapViewHeight = mapView.getHeight();
    int bottomSheetHeight = (int) resources.getDimension(R.dimen.component_navigation_bottomsheet_height);
    int topPadding = mapViewHeight - (bottomSheetHeight * BOTTOMSHEET_PADDING_MULTIPLIER);
    navigationMap.retrieveMap().setPadding(ZERO_PADDING, topPadding, ZERO_PADDING, ZERO_PADDING);
  }

  private void resetMapAfterNavigation() {
    navigationMap.removeRoute();
    navigationMap.clearMarkers();
    addEventToHistoryFile("cancel_navigation");
    executeStoreHistoryTask();
    navigation.stopTripSession();
    moveCameraOverhead();
  }

  private void removeLocationEngineListener() {
    if (locationEngine != null) {
//      navigation.disableFreeDrive(); // TODO Electronic horizon
      isFreeDriveEnabled = false;
      navigation.unregisterLocationObserver(this);
    }
  }

  @SuppressLint("MissingPermission")
  private void addLocationEngineListener() {
    if (locationEngine != null) {
      navigation.registerLocationObserver(this);
//      navigation.enableFreeDrive(); // TODO Electronic horizon
      isFreeDriveEnabled = true;
    }
  }

  private void calculateRouteWith(Point destination, boolean isOffRoute) {
    Point origin = Point.fromLngLat(lastLocation.getLongitude(), lastLocation.getLatitude());
    navigation.requestRoutes(
            MapboxRouteOptionsUtils.applyDefaultParams(RouteOptions.builder())
                    .accessToken(Mapbox.getAccessToken())
                    .coordinates(Arrays.asList(origin, destination))
                    .build()
    );
  }

  @SuppressLint("MissingPermission")
  private void quickStartNavigation() {
    // Transition to navigation state
    mapState = MapState.NAVIGATION;

    cancelNavigationFab.show();

    // Show the InstructionView
    TransitionManager.beginDelayedTransition(navigationLayout);
    instructionView.setVisibility(View.VISIBLE);

    adjustMapPaddingForNavigation();
    // Updates camera with last location before starting navigating,
    // making sure the route information is updated
    // by the time the initial camera tracking animation is fired off
    // Alternatively, NavigationMapboxMap#startCamera could be used here,
    // centering the map camera to the beginning of the provided route
    navigationMap.resumeCamera(lastLocation);
    navigation.setRoutes(Arrays.asList(route));
    navigation.startTripSession();
    addEventToHistoryFile("start_navigation");

    // Location updates will be received from ProgressChangeListener
    removeLocationEngineListener();

    // TODO remove example usage
    navigationMap.resetCameraPositionWith(NavigationCamera.NAVIGATION_TRACKING_MODE_GPS);
    CameraUpdate cameraUpdate = cameraOverheadUpdate();
    if (cameraUpdate != null) {
      NavigationCameraUpdate navUpdate = new NavigationCameraUpdate(cameraUpdate);
      navigationMap.retrieveCamera().update(navUpdate);
    }
  }

  @SuppressLint("MissingPermission")
  private void handleRoute(List<? extends DirectionsRoute> routes, boolean isOffRoute) {
    if (!routes.isEmpty()) {
      route = routes.get(FIRST);
      navigationMap.drawRoute(route);
      if (isOffRoute) {
        navigation.setRoutes(routes);
        navigation.startTripSession();
      } else {
        startNavigationFab.show();
      }
    }
  }

  @SuppressLint("MissingPermission")
  private void vibrate() {
    Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      vibrator.vibrate(VibrationEffect.createOneShot(ONE_HUNDRED_MILLISECONDS, VibrationEffect.DEFAULT_AMPLITUDE));
    } else {
      vibrator.vibrate(ONE_HUNDRED_MILLISECONDS);
    }
  }

  private void addEventToHistoryFile(String type) {
    double secondsFromEpoch = new Date().getTime() / 1000.0;
    navigation.addHistoryEvent(type, Double.toString(secondsFromEpoch));
  }

  @NonNull
  private LocationEngineRequest buildEngineRequest() {
    return new LocationEngineRequest.Builder(UPDATE_INTERVAL_IN_MILLISECONDS)
            .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
            .setFastestInterval(FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS)
            .build();
  }

  private void trackCameraDelayed() {
    handler.postDelayed(updateTracking, TWO_SECONDS_IN_MILLISECONDS);
  }

  @Override
  public void onRoutesChanged(@NotNull List<? extends DirectionsRoute> routes) {
    handleRoute(routes, false);
  }

  /*
   * LocationEngine callback
   */

  private static class ComponentActivityLocationCallback implements LocationEngineCallback<LocationEngineResult> {

    private final WeakReference<ComponentNavigationActivity> activityWeakReference;

    ComponentActivityLocationCallback(ComponentNavigationActivity activity) {
      this.activityWeakReference = new WeakReference<>(activity);
    }

    @Override
    public void onSuccess(LocationEngineResult result) {
      ComponentNavigationActivity activity = activityWeakReference.get();
      if (activity != null) {
        Location location = result.getLastLocation();
        if (location == null) {
          return;
        }
        activity.checkFirstUpdate(location);
        activity.updateLocation(location);
      }
    }

    @Override
    public void onFailure(@NonNull Exception exception) {
      Timber.e(exception);
    }
  }
}
