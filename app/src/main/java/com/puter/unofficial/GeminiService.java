package com.puter.unofficial;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.puter.unofficial.models.GroundingSource;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Native Network Service for Google Gemini APIs.
 * Pure Java implementation with direct Java callback listeners.
 * Handles background REST, streaming execution, active call cancellation,
 * URL context extraction/fetching, and live model discovery utilizing OkHttp.
 */
public class GeminiService {

    private static final String TAG = "GeminiService";
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[a-zA-Z0-9./_\\-~%#?=&]+");

    public interface GeminiStreamListener {
        void onChunk(String chunkText);
        void onComplete(String fullResponse);
        void onCitationsReceived(List<GroundingSource> citations);
        void onError(String errorMessage);
    }

    public interface GeminiModelsListener {
        void onModelsFetched(String json);
        void onError(String errorMessage);
    }

    private final Context context;
    private final OkHttpClient client;
    private final ExecutorService executorService;
    private final Handler mainHandler;

    private Call activeCall = null;
    private final Object callLock = new Object();

    public GeminiService(Context context) {
        this.context = context.getApplicationContext();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executorService = Executors.newSingleThreadExecutor();

        this.client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .build();
    }

    public void cancelCurrentCall() {
        synchronized (callLock) {
            if (activeCall != null && !activeCall.isCanceled()) {
                Log.d(TAG, "Aborting active Gemini network call.");
                activeCall.cancel();
                activeCall = null;
            }
        }
    }

    public void executeCall(final String modelId, final String payloadJson, final boolean isStream, final GeminiStreamListener listener) {
        executorService.execute(() -> {
            Call call = null;
            try {
                // 1. Guard against non-Gemini router models accidentally routed to Google API
                if (isNonGeminiModel(modelId)) {
                    deliverError(listener, "Invalid model for Google Gemini API: " + modelId + 
                            ". This model belongs to Puter/OpenRouter. Please switch active provider to Puter.");
                    return;
                }

                String sanitizedModel = sanitizeModelId(modelId);

                SharedPreferences prefs = context.getSharedPreferences(AppConstants.PREF_NAME, Context.MODE_PRIVATE);
                String apiKey = prefs.getString(AppConstants.KEY_GEMINI_API_KEY, "");

                if (apiKey == null || apiKey.trim().isEmpty()) {
                    apiKey = "AIzaSyBbwPg-mPKe4FNtfI8j07REFFjV6C-xVMw";
                }

                String cleanPayload = processUrlContextAndCleanPayload(payloadJson);

                String baseUrl = "https://generativelanguage.googleapis.com/v1beta/models/" + sanitizedModel;
                String action = isStream ? ":streamGenerateContent?alt=sse&key=" : ":generateContent?key=";
                String targetUrl = baseUrl + action + apiKey;

                MediaType mediaType = MediaType.parse("application/json; charset=utf-8");
                RequestBody body = RequestBody.create(cleanPayload, mediaType);

                Request request = new Request.Builder()
                        .url(targetUrl)
                        .addHeader("x-goog-api-key", apiKey)
                        .post(body)
                        .build();

                call = client.newCall(request);
                synchronized (callLock) {
                    this.activeCall = call;
                }

                Response response = call.execute();

                if (!response.isSuccessful()) {
                    String errorText = response.body() != null ? response.body().string() : "Empty response body";
                    deliverError(listener, "HTTP " + response.code() + ": " + errorText);
                    return;
                }

                ResponseBody responseBody = response.body();
                if (responseBody == null) {
                    deliverError(listener, "Response body was empty.");
                    return;
                }

                if (isStream) {
                    parseStreamResponse(responseBody, listener);
                } else {
                    String responseData = responseBody.string();
                    parseSingleResponse(responseData, listener);
                }

            } catch (IOException e) {
                if (call != null && call.isCanceled()) {
                    deliverError(listener, "Request canceled.");
                    return;
                }
                deliverError(listener, "Connection failed: " + e.getMessage());
            } catch (Exception ex) {
                deliverError(listener, "Processing error: " + ex.getMessage());
            } finally {
                synchronized (callLock) {
                    if (this.activeCall == call) {
                        this.activeCall = null;
                    }
                }
            }
        });
    }

    private boolean isNonGeminiModel(String modelId) {
        if (modelId == null) return true;
        String m = modelId.toLowerCase(Locale.US);
        return m.contains("openrouter") || m.contains("togetherai") || m.contains("puter") || 
               m.contains("claude") || m.contains("deepseek") || m.contains("gpt-") || 
               m.contains("mistral") || m.contains("qwen") || m.contains(":") || m.contains("/");
    }

    private String sanitizeModelId(String rawModelId) {
        if (rawModelId == null || rawModelId.trim().isEmpty()) {
            return "gemini-1.5-flash";
        }
        String cleaned = rawModelId.trim();
        if (cleaned.startsWith("models/")) {
            cleaned = cleaned.substring(7);
        }
        return cleaned;
    }

    private void parseSingleResponse(String jsonStr, GeminiStreamListener listener) {
        try {
            JSONObject root = new JSONObject(jsonStr);
            String text = extractTextFromGeminiJson(root);
            List<GroundingSource> citations = extractCitations(root);

            mainHandler.post(() -> {
                if (citations != null && !citations.isEmpty()) {
                    listener.onCitationsReceived(citations);
                }
                listener.onComplete(text);
            });
        } catch (Exception e) {
            deliverError(listener, "Failed to parse Gemini response: " + e.getMessage());
        }
    }

    private void parseStreamResponse(ResponseBody responseBody, GeminiStreamListener listener) {
        InputStream inputStream = responseBody.byteStream();
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        String line;
        StringBuilder fullAccumulator = new StringBuilder();
        List<GroundingSource> allCitations = new ArrayList<>();

        try {
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("data:")) {
                    String data = line.substring(5).trim();
                    if (data.isEmpty() || "[DONE]".equals(data)) continue;

                    try {
                        JSONObject chunkObj = new JSONObject(data);
                        String text = extractTextFromGeminiJson(chunkObj);
                        List<GroundingSource> citations = extractCitations(chunkObj);
                        if (citations != null) allCitations.addAll(citations);

                        if (text != null && !text.isEmpty()) {
                            fullAccumulator.append(text);
                            mainHandler.post(() -> listener.onChunk(text));
                        }
                    } catch (Exception ignored) {}
                }
            }

            mainHandler.post(() -> {
                if (!allCitations.isEmpty()) {
                    listener.onCitationsReceived(allCitations);
                }
                listener.onComplete(fullAccumulator.toString());
            });

        } catch (IOException e) {
            synchronized (callLock) {
                if (activeCall != null && activeCall.isCanceled()) {
                    return;
                }
            }
            deliverError(listener, "Streaming interrupted: " + e.getMessage());
        } finally {
            try { reader.close(); } catch (IOException ignored) {}
            try { inputStream.close(); } catch (IOException ignored) {}
        }
    }

    private String extractTextFromGeminiJson(JSONObject root) {
        try {
            if (root.has("candidates")) {
                JSONArray candidates = root.getJSONArray("candidates");
                if (candidates.length() > 0) {
                    JSONObject cand = candidates.getJSONObject(0);
                    if (cand.has("content") && cand.getJSONObject("content").has("parts")) {
                        JSONArray parts = cand.getJSONObject("content").getJSONArray("parts");
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < parts.length(); i++) {
                            JSONObject p = parts.getJSONObject(i);
                            if (p.has("text")) sb.append(p.getString("text"));
                        }
                        return sb.toString();
                    }
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private List<GroundingSource> extractCitations(JSONObject root) {
        List<GroundingSource> list = new ArrayList<>();
        try {
            if (root.has("candidates")) {
                JSONArray candidates = root.getJSONArray("candidates");
                if (candidates.length() > 0) {
                    JSONObject cand = candidates.getJSONObject(0);
                    if (cand.has("groundingMetadata")) {
                        JSONObject gm = cand.getJSONObject("groundingMetadata");
                        if (gm.has("groundingChunks")) {
                            JSONArray chunks = gm.getJSONArray("groundingChunks");
                            for (int i = 0; i < chunks.length(); i++) {
                                JSONObject c = chunks.getJSONObject(i);
                                if (c.has("web")) {
                                    JSONObject w = c.getJSONObject("web");
                                    String uri = w.optString("uri", "");
                                    String title = w.optString("title", uri);
                                    list.add(new GroundingSource(uri, title, "", ""));
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return list;
    }

    private String processUrlContextAndCleanPayload(String rawPayloadJson) {
        try {
            JSONObject root = new JSONObject(rawPayloadJson);

            boolean isUrlContextEnabled = false;
            if (root.has("urlContext")) {
                isUrlContextEnabled = root.optBoolean("urlContext", false);
                root.remove("urlContext");
            }

            if (!isUrlContextEnabled || !root.has("contents")) {
                return root.toString();
            }

            JSONArray contents = root.getJSONArray("contents");
            List<String> detectedUrls = new ArrayList<>();

            for (int i = 0; i < contents.length(); i++) {
                JSONObject turn = contents.getJSONObject(i);
                if (turn.optString("role", "").equals("user") && turn.has("parts")) {
                    JSONArray parts = turn.getJSONArray("parts");
                    for (int j = 0; j < parts.length(); j++) {
                        JSONObject part = parts.getJSONObject(j);
                        if (part.has("text")) {
                            String text = part.getString("text");
                            Matcher matcher = URL_PATTERN.matcher(text);
                            while (matcher.find()) {
                                String url = matcher.group();
                                if (!detectedUrls.contains(url)) {
                                    detectedUrls.add(url);
                                }
                            }
                        }
                    }
                }
            }

            if (!detectedUrls.isEmpty()) {
                StringBuilder fetchedContextBuilder = new StringBuilder();
                int fetchedCount = 0;
                int maxFetches = Math.min(detectedUrls.size(), 15);

                for (int i = 0; i < maxFetches; i++) {
                    String url = detectedUrls.get(i);
                    String fetchedData = fetchUrlContentDirectly(url);
                    if (fetchedData != null && !fetchedData.trim().isEmpty()) {
                        fetchedContextBuilder.append("\n\n--- START OF FILE CONTEXT [URL: ").append(url).append("] ---\n");
                        fetchedContextBuilder.append(fetchedData);
                        fetchedContextBuilder.append("\n--- END OF FILE CONTEXT ---\n");
                        fetchedCount++;
                    }
                }

                if (fetchedCount > 0) {
                    JSONObject lastTurn = contents.getJSONObject(contents.length() - 1);
                    JSONArray parts = lastTurn.getJSONArray("parts");

                    JSONObject contextPart = new JSONObject();
                    contextPart.put("text", "\n[System URL Context Retrieval: The following " + fetchedCount +
                            " source file(s) were successfully fetched and verified]\n" +
                            fetchedContextBuilder.toString());

                    parts.put(contextPart);
                }
            }

            return root.toString();
        } catch (Exception e) {
            return rawPayloadJson;
        }
    }

    private String fetchUrlContentDirectly(String url) {
        try {
            Request req = new Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0")
                    .get()
                    .build();

            try (Response resp = client.newCall(req).execute()) {
                if (resp.isSuccessful() && resp.body() != null) {
                    String bodyStr = resp.body().string();
                    if (bodyStr.length() > 40000) {
                        return bodyStr.substring(0, 40000) + "\n...[Content truncated for token safety]...";
                    }
                    return bodyStr;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    public void fetchAvailableModels(final String apiKey, final GeminiModelsListener listener) {
        executorService.execute(() -> {
            try {
                String keyToUse = (apiKey != null && !apiKey.trim().isEmpty()) ? apiKey.trim() : "";
                if (keyToUse.isEmpty()) {
                    SharedPreferences prefs = context.getSharedPreferences(AppConstants.PREF_NAME, Context.MODE_PRIVATE);
                    keyToUse = prefs.getString(AppConstants.KEY_GEMINI_API_KEY, "");
                }
                if (keyToUse.isEmpty()) {
                    keyToUse = "AIzaSyBbwPg-mPKe4FNtfI8j07REFFjV6C-xVMw";
                }

                String targetUrl = AppConstants.GEMINI_MODELS_LIST_URL + "?key=" + keyToUse;
                Request request = new Request.Builder()
                        .url(targetUrl)
                        .addHeader("x-goog-api-key", keyToUse)
                        .get()
                        .build();

                Response response = client.newCall(request).execute();
                if (!response.isSuccessful()) {
                    String err = response.body() != null ? response.body().string() : "Empty error";
                    mainHandler.post(() -> listener.onError("HTTP " + response.code() + ": " + err));
                    return;
                }

                ResponseBody body = response.body();
                String json = body != null ? body.string() : "{}";
                mainHandler.post(() -> listener.onModelsFetched(json));

            } catch (Exception e) {
                mainHandler.post(() -> listener.onError(e.getMessage()));
            }
        });
    }

    private void deliverError(GeminiStreamListener listener, String err) {
        mainHandler.post(() -> listener.onError(err));
    }

    public void shutdown() {
        cancelCurrentCall();
        if (!executorService.isShutdown()) {
            executorService.shutdownNow();
        }
    }
}