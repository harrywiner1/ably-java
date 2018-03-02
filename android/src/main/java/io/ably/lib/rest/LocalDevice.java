package io.ably.lib.rest;

import com.google.gson.JsonObject;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.preference.PreferenceManager;
import io.ably.lib.types.AblyException;
import io.ably.lib.types.Function;
import io.ably.lib.types.RegistrationToken;
import io.ably.lib.types.Callback;
import io.ably.lib.util.Serialisation;
import io.ably.lib.http.Http;
import io.ably.lib.http.HttpScheduler;
import io.ably.lib.http.HttpUtils;
import io.azam.ulidj.ULID;
import android.util.Log;

import java.lang.reflect.Field;

public class LocalDevice extends DeviceDetails {
    private final AblyRest rest;

    private LocalDevice(AblyRest rest) {
        super();
        this.rest = rest;
    }

    protected static LocalDevice load(Context context, AblyRest rest) {
        LocalDevice device = new LocalDevice(rest);
        device.loadPersisted(context, rest);
        return device;
    }

    protected void loadPersisted(Context context, AblyRest rest) {
        this.platform = "android";
        this.clientId = rest.auth.clientId;
        this.formFactor = isTablet(context) ? "tablet" : "phone";

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        String id = prefs.getString(SharedPrefKeys.DEVICE_ID, null);
        this.id = id;
        if (id == null) {
            this.resetId(context);
        }
        this.updateToken = prefs.getString(SharedPrefKeys.UPDATE_TOKEN, null);

        RegistrationToken.Type type = RegistrationToken.Type.fromInt(
            prefs.getInt(SharedPrefKeys.TOKEN_TYPE, -1));
        RegistrationToken token = null;
        if (type != null) {
            token = new RegistrationToken(type, prefs.getString(SharedPrefKeys.TOKEN, null));
        }
        this.setRegistrationToken(token);
    }

    protected RegistrationToken getRegistrationToken() {
        if (push == null) {
            return null;
        }
        JsonObject recipient = push.recipient;
        if (recipient == null) {
            return null;
        }
        return new RegistrationToken(
            RegistrationToken.Type.fromCode(recipient.get("transportType").getAsString()),
            recipient.get("registrationToken").getAsString()
        );
    }

    private void setRegistrationToken(RegistrationToken token) {
        push = new DeviceDetails.Push();
        if (token == null) {
            return;
        }
        push.recipient = new JsonObject();
        push.recipient.addProperty("transportType", token.type.code);
        push.recipient.addProperty("registrationToken", token.token);
    }

    private void setRegistrationToken(RegistrationToken.Type type, String token) {
        setRegistrationToken(new RegistrationToken(type, token));
    }

    protected void setAndPersistRegistrationToken(Context context, RegistrationToken token) {
        setRegistrationToken(token);        
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit()
            .putInt(SharedPrefKeys.TOKEN_TYPE, token.type.toInt())
            .putString(SharedPrefKeys.TOKEN, token.token)
            .apply();
    }

    public void setUpdateToken(Context context, String token) {
        this.updateToken = token;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putString(SharedPrefKeys.UPDATE_TOKEN, token).apply();
    }

    public void reissueUpdateToken(Context context) throws AblyException {
        if (this.id == null || this.updateToken == null) {
            return;
        }

        JsonObject response = rest.http.request(new Http.Execute<JsonObject>() {
            @Override
            public void execute(HttpScheduler http, Callback<JsonObject> callback) throws AblyException {
                http.post("/push/deviceDetails/" + id + "/resetUpdateToken", HttpUtils.defaultAcceptHeaders(rest.options.useBinaryProtocol), null, null, new Serialisation.HttpResponseHandler<JsonObject>(), true, callback);
            }
        }).sync();
        setUpdateToken(context, response.getAsJsonPrimitive("updateToken").getAsString());
    }

    public void resetId(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);

        id = ULID.random();
        boolean ok = prefs.edit().putString(SharedPrefKeys.DEVICE_ID, id).commit();
        if (!ok) {
            id = prefs.getString(SharedPrefKeys.DEVICE_ID, null);
        }
    }

    // Returns a function to be called if the editing succeeds, to refresh the object's fields.
    public Function<Context, Void> reset(SharedPreferences.Editor editor) {
        for (Field f : SharedPrefKeys.class.getDeclaredFields()) {
            try {
                editor.remove((String) f.get(null));
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
        return new Function<Context, Void>() {
            @Override
            public Void call(Context context) {
                LocalDevice.this.loadPersisted(context, rest);
                return null;
            }
        };
    }

    private static boolean isTablet(Context context) {
        return (context.getResources().getConfiguration().screenLayout
                & Configuration.SCREENLAYOUT_SIZE_MASK)
                >= Configuration.SCREENLAYOUT_SIZE_LARGE;
    }

    private static class SharedPrefKeys {
        static final String DEVICE_ID = "ABLY_DEVICE_ID";
        static final String UPDATE_TOKEN = "ABLY_DEVICE_UPDATE_TOKEN";
        static final String TOKEN_TYPE = "ABLY_REGISTRATION_TOKEN_TYPE";
        static final String TOKEN = "ABLY_REGISTRATION_TOKEN";
    }
}
