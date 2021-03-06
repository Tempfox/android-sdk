package com.getirkit.irkit.net;

import android.os.Handler;
import android.util.Log;

import com.getirkit.irkit.IRKit;
import com.getirkit.irkit.IRPeripheral;
import com.getirkit.irkit.IRPeripherals;
import com.getirkit.irkit.IRSignal;
import com.getirkit.irkit.IRState;
import com.getirkit.irkit.IRWifiInfo;
import com.squareup.okhttp.OkHttpClient;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import retrofit.Callback;
import retrofit.RestAdapter;
import retrofit.RetrofitError;
import retrofit.client.OkClient;
import retrofit.client.Response;
import retrofit.mime.TypedInput;
import retrofit.mime.TypedString;

/**
 * HTTP client
 */
public class IRHTTPClient {
    public static final String TAG = IRHTTPClient.class.getSimpleName();

    public static final String APIENDPOINT_BASE = "https://api.getirkit.com";
    public static final String DEVICE_API_ENDPOINT_IRKITWIFI = "http://192.168.1.1";

    // Retrofit
    private RestAdapter internetRestAdapter;
    private RestAdapter deviceRestAdapter;
    public IRInternetAPIService internetAPIService;
    public IRDeviceAPIService deviceAPIService;
    private String clientkey;
    private OkHttpClient internetHttpClient;
    private OkHttpClient localHttpClient;
    private Date lastRequestDate;
    private IRInternetAPIService.PostDevicesResponse holdingPostDevicesResponse;
    private Date lastPostDoorRequestDate;
    private IRDeviceEndpoint deviceEndpoint;

    // singleton
    private static IRHTTPClient ourInstance = new IRHTTPClient();
    public static IRHTTPClient sharedInstance() {
        return ourInstance;
    }

    public IRHTTPClient() {
        internetHttpClient = new OkHttpClient();

        // TODO: choose timeout values wisely
        internetHttpClient.setConnectTimeout(10, TimeUnit.SECONDS);
        internetHttpClient.setReadTimeout(0, TimeUnit.SECONDS);

        localHttpClient = new OkHttpClient();
        localHttpClient.setConnectTimeout(5, TimeUnit.SECONDS);
        localHttpClient.setReadTimeout(10, TimeUnit.SECONDS);

        internetRestAdapter = new RestAdapter.Builder()
                .setClient(new OkClient(internetHttpClient))
                .setEndpoint(APIENDPOINT_BASE)
//                .setLogLevel(RestAdapter.LogLevel.FULL)
                .build();
        internetAPIService = internetRestAdapter.create(IRInternetAPIService.class);

        deviceEndpoint = new IRDeviceEndpoint();
        deviceEndpoint.setUrl(DEVICE_API_ENDPOINT_IRKITWIFI);

        deviceRestAdapter = new RestAdapter.Builder()
                .setClient(new OkClient(localHttpClient))
                .setEndpoint(deviceEndpoint)
//                .setLogLevel(RestAdapter.LogLevel.FULL)
                .build();
        deviceAPIService = deviceRestAdapter.create(IRDeviceAPIService.class);
    }

    public void setClientKey(String key) {
        clientkey = key;
    }

    /**
     * Set endpoint for IRKit Device HTTP API
     *
     * @param endpoint e.g. "http://127.0.0.1"
     */
    public void setDeviceAPIEndpoint(String endpoint) {
        deviceEndpoint.setUrl(endpoint);
    }

    public void registerClient(String apiKey, final IRAPICallback<IRInternetAPIService.GetClientsResponse> callback) {
        HashMap<String, String> params = new HashMap<>();
        params.put("apikey", apiKey);
        internetAPIService.postClients(params, new Callback<IRInternetAPIService.GetClientsResponse>() {
            @Override
            public void success(IRInternetAPIService.GetClientsResponse getClientsResponse, Response response) {
                clientkey = getClientsResponse.clientkey;
                IRKit.sharedInstance().savePreference("clientkey", clientkey);
                callback.success(getClientsResponse, response);
            }

            @Override
            public void failure(RetrofitError error) {
                IRAPIError apiError = (IRAPIError) error.getBodyAs(IRAPIError.class);
                Log.e(TAG, "registerClient failed: " + error.getMessage());
                if (apiError != null) {
                    Log.e(TAG, "API error message: " + apiError.message);
                }
                callback.failure(error);
            }
        });
    }

    public void ensureRegisteredAndCall(String apiKey, IRAPICallback<IRInternetAPIService.GetClientsResponse> callback) {
        if (clientkey == null) {
            IRHTTPClient.sharedInstance().registerClient(apiKey, callback);
        } else {
            callback.success(null, null);
        }
    }

    public void sendSignalOverInternet(IRSignal signal, final IRAPICallback<IRInternetAPIService.PostMessagesResponse> callback) {
        HashMap<String, String> params = new HashMap<>();
        params.put("deviceid", signal.getDeviceId());
        params.put("message", signal.toJson());
        this.addClientKey(params);
        internetAPIService.postMessages(params, new IRAPICallback<IRInternetAPIService.PostMessagesResponse>() {
            @Override
            public void success(IRInternetAPIService.PostMessagesResponse postMessagesResponse, Response response) {
                if (callback != null) {
                    callback.success(postMessagesResponse, response);
                }
            }

            @Override
            public void failure(RetrofitError error) {
                if (callback != null) {
                    callback.failure(error);
                }
            }
        });
    }

    /**
     * Send IRSignal over local network
     *
     * @param signal
     * @param result
     * @param timeoutMs
     */
    public void sendSignalOverLocalNetwork(final IRSignal signal, final IRAPIResult result, int timeoutMs) {
        IRDeviceAPIService.PostMessagesRequest request = new IRDeviceAPIService.PostMessagesRequest();
        request.format = signal.getFormat();
        request.freq = signal.getFrequency();
        request.data = signal.getData();

        final IRState state = new IRState();
        final Handler handler = new Handler();
        final Runnable r = new Runnable() {
            @Override
            public void run() {
                boolean isTimedOut = false;
                synchronized (state) {
                    if ( !state.isFinished() ) {
                        state.finish();
                        isTimedOut = true;
                    }
                }
                if (isTimedOut) {
                    Log.e(TAG, "sendSignalOverLocalNetwork: timeout");
                    result.onTimeout();
                }
            }
        };
        handler.postDelayed(r, timeoutMs);

        deviceAPIService.postMessages(request, new Callback<IRDeviceAPIService.PostMessagesResponse>() {
            @Override
            public void success(IRDeviceAPIService.PostMessagesResponse postMessagesResponse, Response response) {
                IRPeripherals peripherals = IRKit.sharedInstance().peripherals;
                IRPeripheral peripheral = peripherals.getPeripheralByDeviceId( signal.getDeviceId() );
                if (peripheral != null) {
                    if (peripheral.storeResponseHeaders(response)) {
                        peripherals.save();
                    }
                }

                boolean isTimedOut = false;
                synchronized (state) {
                    if ( state.isFinished() ) {
                        isTimedOut = true;
                    } else {
                        state.finish();
                    }
                }
                if (!isTimedOut) {
                    handler.removeCallbacks(r);
                    if (result != null) {
                        result.onSuccess();
                    }
                }
            }

            @Override
            public void failure(RetrofitError error) {
                Log.e(TAG, "device postMessages failure: " + error.getMessage());
                boolean isTimedOut = false;
                synchronized (state) {
                    if ( state.isFinished() ) {
                        isTimedOut = true;
                    } else {
                        state.finish();
                    }
                }
                if (!isTimedOut) {
                    handler.removeCallbacks(r);
                    if (result != null) {
                        result.onError(new IRAPIError(error.getMessage()));
                    }
                }
            }
        });
    }

    public void waitForSignal(IRAPICallback<IRInternetAPIService.GetMessagesResponse> callback) {
        waitForSignal(callback, true);
    }

    public void waitForSignal(final IRAPICallback<IRInternetAPIService.GetMessagesResponse> callback, boolean clear) {
        HashMap<String, String> params = new HashMap<>(2);
        if (clear) {
            params.put("clear", "1");
        }
        this.addClientKey(params);

        final Date requestDate = lastRequestDate = new Date();
        internetAPIService.getMessages(params, new Callback<IRInternetAPIService.GetMessagesResponse>() {
            @Override
            public void success(IRInternetAPIService.GetMessagesResponse getMessagesResponse, Response response) {
                if (lastRequestDate == null) {
                    // The request has been cancelled. Discard this response.
                    return;
                }
                if (lastRequestDate.after(requestDate)) {
                    // The request is obsolete. Discard this response.
                    return;
                }
                if (getMessagesResponse == null) {
                    // Server returned null response. Try again.
                    IRHTTPClient.sharedInstance().waitForSignal(callback, false);
                } else {
                    callback.success(getMessagesResponse, response);
                }
            }

            @Override
            public void failure(RetrofitError error) {
                Log.e(TAG, "internet getMessages failure: " + error.getMessage());
                callback.failure(error);
            }
        });
    }

    public void cancelRequests() {
        lastRequestDate = null;
    }

    public void addClientKey(Map<String, String> params) {
        if (clientkey != null) {
            params.put("clientkey", clientkey);
        }
    }

    public void clearDeviceKeyCache() {
        holdingPostDevicesResponse = null;
    }

    public void obtainDeviceKey(final IRAPICallback<IRInternetAPIService.PostDevicesResponse> callback) {
        if (clientkey == null) {
            throw new IllegalStateException("clientkey is not set");
        }
        if (holdingPostDevicesResponse != null) {
            // We already have devicekey/id
            callback.success(holdingPostDevicesResponse, null);
            return;
        }
        HashMap<String, String> params = new HashMap<>(1);
        addClientKey(params);
        internetAPIService.postDevices(params, new Callback<IRInternetAPIService.PostDevicesResponse>() {
            @Override
            public void success(IRInternetAPIService.PostDevicesResponse postDevicesResponse, Response response) {
                holdingPostDevicesResponse = postDevicesResponse;
                callback.success(postDevicesResponse, response);
            }

            @Override
            public void failure(RetrofitError error) {
                Log.e(TAG, "failed to get devicekey: " + error.getMessage());
                callback.failure(error);
            }
        });
    }

    public void connectDeviceToWifi(IRWifiInfo irWifiInfo, final IRAPICallback<IRDeviceAPIService.PostWifiResponse> callback) {
        if (holdingPostDevicesResponse == null) {
            throw new IllegalStateException("holdingPostDevicesResponse is null");
        }
        String morseString = irWifiInfo.createMorseString(holdingPostDevicesResponse.devicekey);
        TypedInput in = new TypedString(morseString);
        deviceAPIService.postWifi(in, new Callback<IRDeviceAPIService.PostWifiResponse>() {
            @Override
            public void success(IRDeviceAPIService.PostWifiResponse postWifiResponse, Response response) {
                clearDeviceKeyCache();
                callback.success(postWifiResponse, response);
            }

            @Override
            public void failure(RetrofitError error) {
                Log.e(TAG, "postWifi failure: " + error.getMessage());
                callback.failure(error);
            }
        });
    }

    public void cancelPostDoor() {
        lastPostDoorRequestDate = null;
    }

    public void waitForDoor(final String deviceId, final IRAPICallback<IRInternetAPIService.PostDoorResponse> callback) {
        HashMap<String, String> params = new HashMap<>(2);
        addClientKey(params);
        params.put("deviceid", deviceId);
        final Date requestDate = lastPostDoorRequestDate = new Date();
        internetAPIService.postDoor(params, new Callback<IRInternetAPIService.PostDoorResponse>() {
            @Override
            public void success(IRInternetAPIService.PostDoorResponse postDoorResponse, Response response) {
                if (lastPostDoorRequestDate == null) {
                    // This request has been canceled. Discard this response.
                    return;
                }
                if (lastPostDoorRequestDate.after(requestDate)) {
                    // This request is obsolete. Discard this response.
                    return;
                }
                if (postDoorResponse.hostname == null) {
                    // Empty response. Retry.
                    waitForDoor(deviceId, callback);
                } else {
                    // Success
                    callback.success(postDoorResponse, response);
                }
            }

            @Override
            public void failure(RetrofitError error) {
                if (lastPostDoorRequestDate == null) {
                    // This request has been canceled. Discard this response.
                    return;
                }
                if (lastPostDoorRequestDate.after(requestDate)) {
                    // This request is obsolete. Discard this response.
                    return;
                }
                if (error != null && error.getResponse() != null) {
                    int statusCode = error.getResponse().getStatus();
                    // IRKit server returns 408 when /door didn't success in a certain amount of time
                    if (statusCode >= 400 && statusCode < 500) {
                        // Retry postDoor
                        waitForDoor(deviceId, callback);
                    } else {
                        Log.e(TAG, "postDoor error: statusCode=" + statusCode);
                        callback.failure(error);
                    }
                } else {
                    Log.e(TAG, "postDoor failure: " + error);
                    callback.failure(error);
                }
            }
        });
    }

    public IRInternetAPIService getInternetAPIService() {
        return internetAPIService;
    }

    public IRDeviceAPIService getDeviceAPIService() {
        return deviceAPIService;
    }

    public String getClientKey() {
        return clientkey;
    }

    public boolean hasClientKey() {
        return clientkey != null;
    }
}
