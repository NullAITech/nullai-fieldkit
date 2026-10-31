package tech.nullai.fieldkit.live;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONObject;

/**
 * Mints a short-lived Gemini Live token from the relay (POST /v1/live/token).
 *
 * <p>The relay holds the real API key; the phone only ever gets a single-use
 * token that is locked to the relay's setup (model, voice, system prompt), so
 * the app cannot change the persona or use the key for anything else.
 */
public final class LiveTokenClient {

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .build();

    /** Everything the caller needs to open the Live WebSocket. */
    public static final class Session {
        public final String token;
        public final String model;
        public final String wsUrl;
        public final String apiVersion;

        Session(String token, String model, String wsUrl, String apiVersion) {
            this.token = token;
            this.model = model;
            this.wsUrl = wsUrl;
            this.apiVersion = apiVersion;
        }

        /** Full connect URL; the constrained token is passed as access_token. */
        public String connectUrl() {
            return wsUrl + (wsUrl.contains("?") ? "&" : "?") + "access_token=" + token;
        }

        public String setupFrame() {
            // Constrained token: the relay's locked setup supplies voice, system
            // instruction and modalities, so the client only names the model.
            return "{\"setup\":{\"model\":\"models/" + model + "\"}}";
        }
    }

    private LiveTokenClient() { }

    public static Session mint(String relayBase, String agentToken) throws IOException {
        String url = relayBase.replaceAll("/+$", "") + "/v1/live/token";
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + agentToken)
                .post(RequestBody.create("{}",
                        MediaType.get("application/json; charset=utf-8")))
                .build();
        try (Response r = HTTP.newCall(request).execute()) {
            String body = r.body() != null ? r.body().string() : "";
            if (!r.isSuccessful()) {
                throw new IOException("live token " + r.code() + ": " + body.trim());
            }
            try {
                JSONObject o = new JSONObject(body);
                String tok = o.optString("token", "");
                if (tok.isEmpty()) throw new IOException("live token response had no token");
                return new Session(tok,
                        o.optString("model", "gemini-3.8-live"),
                        o.optString("ws_url", ""),
                        o.optString("api_version", "v1alpha"));
            } catch (org.json.JSONException e) {
                throw new IOException("live token response was not JSON: " + e.getMessage());
            }
        }
    }
}
