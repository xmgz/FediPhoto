package com.fediphoto;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;


import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.text.InputType;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.work.Data;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkContinuation;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.impl.WorkManagerImpl;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;


public class MainActivity extends AppCompatActivity {
    private final int CAMERA_REQUEST = 169;
    private final int TOKEN_REQUEST = 269;
    private final int REQUEST_PERMISSION_CAMERA = 369;
    private final int REQUEST_PERMISSION_STORAGE = 469;
    private final int REQUEST_ACCOUNT = 569;
    private final int REQUEST_STATUS = 769;
    public static final int REQUEST_ACCOUNT_RETURN = 669;
    private static final String CHECKMARK = "✔";
    private final static String TAG = "com.fediphoto.MainActivity";
    private final Context context = this;
    private final Activity activity = this;
    private JsonObject createAppResults = new JsonObject();
    private Button buttonCamera;
    private String token;
    private String instance;
    private String photoFileName;
    private final MainActivity mainActivity = this;

    private void dispatchTakePictureIntent() {
        Intent intent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (intent.resolveActivity(getPackageManager()) != null) {
            try {
                File file = createPhotoFile();
                Uri photoUri = FileProvider.getUriForFile(context, "com.fediphoto.fileprovider", file);
                //Uri photoUri = Uri.fromFile(file);
                Log.i(TAG, String.format("photo URI: %s", photoUri.toString()));
                intent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
                startActivityForResult(intent, CAMERA_REQUEST);
            } catch (IOException e) {
                Toast.makeText(context, e.getLocalizedMessage(), Toast.LENGTH_LONG).show();
                e.printStackTrace();
            }
        } else {
            Log.i(TAG, "Camera activity missing.");
        }
    }

    private void setButtonCameraText() {
        JsonObject account = Utils.getAccountActiveFromSettings(context);
        JsonObject status = Utils.getStatusActiveFromSettings(context);
        String me = Utils.getProperty(account, Literals.me.name());
        String label = Utils.getProperty(status, Literals.label.name());
        String buttonCameraText = String.format(getString(R.string.button_camera_text), me, label);
        buttonCamera.setText(buttonCameraText);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        buttonCamera = findViewById(R.id.button_camera);
        JsonObject account = Utils.getAccountSelectedFromSettings(context);
        JsonObject status = Utils.getStatusSelectedFromSettings(context);
        if (account != null
                && status != null
                && Utils.getAccountActiveFromSettings(context) != null
                && Utils.getAccountActiveFromSettings(context).get(Literals.me.name()) != null) {
            setButtonCameraText();
        }
        buttonCamera.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (Utils.getAccountQuantity(context) == 0) {
                    askForInstance();
                    return;             
                }
                dispatchTakePictureIntent();
            }
        });
        checkPermissionCamera();
        checkPermissionStorage();
        JsonObject settings = Utils.getSettings(context);
        boolean cameraOnStart = false;
        if (!Utils.isJsonObject(settings)
                || settings.getAsJsonArray(Literals.accounts.name()) == null
                || settings.getAsJsonArray(Literals.accounts.name()).size() == 0) {
            askForInstance();
        } else {
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
            cameraOnStart = sharedPreferences.getBoolean(getString(R.string.camera_on_start), false);
        }
        Log.i(TAG, String.format("Camera on start setting: %s", cameraOnStart));
        if (cameraOnStart) {
            dispatchTakePictureIntent();
        }
        workerStatus(Literals.worker_tag_media_upload.name());
        workerStatus(Literals.worker_tag_post_status.name());
        setTitle();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        switch (requestCode) {
            case REQUEST_PERMISSION_CAMERA: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(context, R.string.camera_permission_granted, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(context, R.string.camera_permission_denied, Toast.LENGTH_LONG).show();
                }
            }
            case REQUEST_PERMISSION_STORAGE: {
                if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(context, R.string.external_storage_permission_granted, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(context, R.string.external_storage_permission_denied, Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private void checkPermissionCamera() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(activity,
                    Manifest.permission.CAMERA)) {
                Toast.makeText(context, R.string.need_camera_permission, Toast.LENGTH_LONG).show();
            } else {
                ActivityCompat.requestPermissions(activity,
                        new String[]{Manifest.permission.CAMERA},
                        REQUEST_PERMISSION_CAMERA);
            }
        } else {
            Log.i(TAG, "Camera permission already granted.");
        }
    }

    private void checkPermissionStorage() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(activity,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                Toast.makeText(context, R.string.external_storage_permission_needed, Toast.LENGTH_LONG).show();
            } else {
                ActivityCompat.requestPermissions(activity,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_PERMISSION_STORAGE);
            }
        } else {
            Log.i(TAG, "External storage permission already granted.");
        }
    }

    private File createPhotoFile() throws IOException {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_", Locale.US).format(new Date());
        String fileName = String.format("photo_%s", timestamp);
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (storageDir == null || (!storageDir.exists() && !storageDir.mkdir())) {
            Log.w(TAG, "Couldn't create photo folder.");
        }
        File file = File.createTempFile(fileName, ".jpg", storageDir);
        Log.i(TAG, String.format("Photo file: %s", file.getAbsoluteFile()));
        photoFileName = file.getAbsolutePath();
        return file;
    }

    private void submitWorkerUpload() {
        File file = new File(photoFileName);
        Log.i(TAG, String.format("File %s exists %s", file.getAbsoluteFile(), file.exists()));
        @SuppressLint("RestrictedApi")
        Data data = new Data.Builder()
                .put(Literals.fileName.name(), file.getAbsolutePath())
                .build();
        final OneTimeWorkRequest uploadWorkRequest = new OneTimeWorkRequest
                .Builder(com.fediphoto.WorkerUpload.class)
                .addTag(Literals.worker_tag_media_upload.name())
                .addTag(String.format(Locale.ENGLISH,"%s%d",Literals.created_milliseconds.name(), System.currentTimeMillis()))
                .setInputData(data).build();
        WorkContinuation workContinuation = WorkManager.getInstance(context).beginWith(uploadWorkRequest);
        OneTimeWorkRequest postStatusWorkRequest = new OneTimeWorkRequest
                .Builder(com.fediphoto.WorkerPostStatus.class)
                .addTag(Literals.worker_tag_post_status.name())
                .addTag(String.format(Locale.ENGLISH,"%s%d",Literals.created_milliseconds.name(), System.currentTimeMillis()))
                .setInputData(data).build();
        workContinuation = workContinuation.then(postStatusWorkRequest);
        workContinuation.enqueue();
    }


    protected void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        Log.i(TAG, String.format("Request code %d Result code %d", requestCode, resultCode));
        if (requestCode == CAMERA_REQUEST) {
            if (resultCode == Activity.RESULT_OK) {
                Log.i(TAG, "Camera request returned OK.");
                submitWorkerUpload();
            } else {
                if (photoFileName != null) {
                    File file = new File(photoFileName);
                    boolean fileDeleted = file.delete();
                    Log.i(TAG, String.format("File %s deleted %s", photoFileName, fileDeleted));
                }
            }
        }
        if (requestCode == TOKEN_REQUEST && resultCode == Activity.RESULT_OK) {
            token = intent.getStringExtra(Literals.token.name());
            Log.i(TAG, String.format("Token \"%s\"", token));
            if (token == null || token.length() < 20) {
                String message = String.format(getString(R.string.token_does_not_look_valid), token);
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
                return;
            }
            WorkerAuthorize worker = new WorkerAuthorize(mainActivity);
            worker.execute(instance);
        }
        if (requestCode == REQUEST_ACCOUNT) {
            setTitle();
            if (resultCode == REQUEST_ACCOUNT_RETURN) {
                askForInstance();
            }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater menuInflater = getMenuInflater();
        menuInflater.inflate(R.menu.menu_main, menu);
        return true;
    }

    public enum Literals {
        client_name, redirect_uris, scopes, website, access_token, POST, urlString, authorization_code,
        token, client_id, client_secret, redirect_uri, me,
        grant_type, code, accounts, instance, text, visibility, unlisted, dateFormat,
        OK, Cancel, media_ids, id, status, url, gpsCoordinatesFormat, direct, fileName,
        accountIndexSelected, accountIndexActive, statuses, label, statusIndexActive, statusIndexSelected,
        leave, copy, move, delete, display_name, username, acct, worker_tag_media_upload, worker_tag_post_status, created_milliseconds,
        threading, never, daily, always, threadingDate, threadingId, in_reply_to_id
    }


    private static class WorkerCreateApp extends AsyncTask<String, Void, JsonObject> {
        private String instance;
        private final WeakReference<MainActivity> weakReference;

        WorkerCreateApp(MainActivity context) {
            this.weakReference = new WeakReference<>(context);
        }

        @Override
        protected JsonObject doInBackground(String... instance) {
            JsonObject params = new JsonObject();
            this.instance = instance[0];
            params.addProperty(Literals.client_name.name(), "Fedi Photo for Android ");
            params.addProperty(Literals.redirect_uris.name(), "fediphoto://fediphotoreturn");
            params.addProperty(Literals.scopes.name(), "read write follow push");
            params.addProperty(Literals.website.name(), "https://fediphoto.com");
            String urlString = String.format("https://%s/api/v1/apps", this.instance);
            Log.i(TAG, "URL " + urlString);
            HttpsURLConnection urlConnection;
            InputStream inputStream = null;
            OutputStream outputStream = null;
            JsonObject jsonObject = null;
            try {
                URL url = new URL(urlString);
                urlConnection = (HttpsURLConnection) url.openConnection();
                urlConnection.setRequestProperty("Cache-Control", "no-cache");
                urlConnection.setRequestProperty("Accept", "application/json");
                urlConnection.setRequestProperty("User-Agent", "FediPhoto");
                urlConnection.setUseCaches(false);
                urlConnection.setRequestMethod(Literals.POST.name());
                urlConnection.setRequestProperty("Content-type", "application/json; charset=UTF-8");
                urlConnection.setDoOutput(true);
                String json = params.toString();
            //    urlConnection.setRequestProperty("Content-length", Integer.toString(json.length()));
                outputStream = urlConnection.getOutputStream();
                outputStream.write(json.getBytes());
                outputStream.flush();
                int responseCode = urlConnection.getResponseCode();
                Log.i(TAG, String.format("Response code: %d in WorkerCreateApp.\n", responseCode));
                urlConnection.setInstanceFollowRedirects(true);
                inputStream = urlConnection.getInputStream();
                InputStreamReader isr = new InputStreamReader(inputStream);
                Gson gson = new Gson();
                jsonObject = gson.fromJson(isr, JsonObject.class);
            } catch (Exception e) {
                e.printStackTrace();
                Log.e(TAG, e.getLocalizedMessage());
            } finally {
                Utils.close(inputStream, outputStream);
            }
            return jsonObject;
        }

        @Override
        protected void onPostExecute(JsonObject jsonObject) {
            super.onPostExecute(jsonObject);
            Log.i(TAG, "WorkerCreateApp OUTPUT: " + jsonObject.toString());
            weakReference.get().createAppResults = jsonObject;
            String urlString = String.format("https://%s/oauth/authorize?scope=%s&response_type=code&redirect_uri=%s&client_id=%s",
                    instance, Utils.urlEncodeComponent("write read follow push"), Utils.urlEncodeComponent(jsonObject.get("redirect_uri").getAsString()), jsonObject.get("client_id").getAsString());
            Intent intent = new Intent(weakReference.get(), WebviewActivity.class);
            intent.putExtra("urlString", urlString);
            weakReference.get().startActivityForResult(intent, weakReference.get().TOKEN_REQUEST);

        }
    }


    private static class WorkerAuthorize extends AsyncTask<String, Void, JsonObject> {
        final WeakReference<MainActivity> weakReference;

        WorkerAuthorize(MainActivity mainActivity) {
            this.weakReference = new WeakReference<>(mainActivity);
        }

        @Override
        protected JsonObject doInBackground(String... instance) {
            JsonObject params = new JsonObject();
            params.addProperty(Literals.client_id.name(), weakReference.get().createAppResults.get(Literals.client_id.name()).getAsString());
            params.addProperty(Literals.client_secret.name(), weakReference.get().createAppResults.get(Literals.client_secret.name()).getAsString());
            params.addProperty(Literals.grant_type.name(), Literals.authorization_code.name());
            params.addProperty(Literals.code.name(), weakReference.get().token);
            params.addProperty(Literals.redirect_uri.name(), weakReference.get().createAppResults.get(Literals.redirect_uri.name()).getAsString());
            String urlString = String.format("https://%s/oauth/token", instance[0]);
            Log.i(TAG, "URL in WorkerAuthorize: " + urlString);
            HttpsURLConnection urlConnection;
            InputStream inputStream = null;
            OutputStream outputStream = null;
            JsonObject jsonObject = null;
            try {
                URL url = new URL(urlString);
                urlConnection = (HttpsURLConnection) url.openConnection();
                urlConnection.setRequestProperty("Cache-Control", "no-cache");
                urlConnection.setRequestProperty("Accept", "application/json");
                urlConnection.setRequestProperty("User-Agent", "FediPhoto");
                urlConnection.setUseCaches(false);
                urlConnection.setRequestMethod(Literals.POST.name());
                urlConnection.setRequestProperty("Content-type", "application/json; charset=UTF-8");
                urlConnection.setDoOutput(true);
                String json = params.toString();
           //     urlConnection.setRequestProperty("Content-length", Integer.toString(json.length()));
                outputStream = urlConnection.getOutputStream();
                outputStream.write(json.getBytes());
                outputStream.flush();
                int responseCode = urlConnection.getResponseCode();
                Log.i(TAG, String.format("WorkerAuthorize Response code: %d\n", responseCode));
                urlConnection.setInstanceFollowRedirects(true);
                inputStream = urlConnection.getInputStream();
                InputStreamReader isr = new InputStreamReader(inputStream);
                Gson gson = new Gson();
                jsonObject = gson.fromJson(isr, JsonObject.class);
                Log.i(TAG, String.format("WorkerAuthorize JSON from oauth/token %s", jsonObject.toString()));
                JsonObject settings = Utils.getSettings(weakReference.get());
                JsonArray accounts = settings.getAsJsonArray(Literals.accounts.name());
                if (accounts == null) {
                    accounts = new JsonArray();
                }
                jsonObject.addProperty(Literals.instance.name(), instance[0]);
                urlString = String.format("https://%s/api/v1/accounts/verify_credentials", instance[0]);
                JsonObject verifyCredentials = weakReference.get().getJsonObject(urlString, Utils.getProperty(jsonObject, Literals.access_token.name()));
                if (Utils.isJsonObject(verifyCredentials)) {
                    jsonObject.addProperty(Literals.me.name(), Utils.getProperty(verifyCredentials, Literals.url.name()));
                    jsonObject.addProperty(Literals.display_name.name(), Utils.getProperty(verifyCredentials, Literals.display_name.name()));
                    jsonObject.addProperty(Literals.username.name(), Utils.getProperty(verifyCredentials, Literals.username.name()));
                    jsonObject.addProperty(Literals.acct.name(), Utils.getProperty(verifyCredentials, Literals.acct.name()));
                }
                accounts.add(jsonObject);
                settings.add(Literals.accounts.name(), accounts);
                Utils.writeSettings(weakReference.get(), settings);
                Log.i(TAG, String.format("WorkerAuthorize settings after save:\n%s\n", Utils.getSettings(weakReference.get()).toString()));
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                Utils.close(inputStream, outputStream);
            }
            return jsonObject;
        }

        @Override
        protected void onPostExecute(JsonObject jsonObject) {
            super.onPostExecute(jsonObject);
            Log.i(TAG, "OUTPUT: " + jsonObject.toString());
            JsonObject settings = Utils.getSettings(weakReference.get());
            if (settings.get(Literals.statuses.name()) == null) {
                Toast.makeText(weakReference.get(), R.string.account_created, Toast.LENGTH_LONG).show();
                Intent intent = new Intent(weakReference.get(), StatusConfigActivity.class);
                weakReference.get().startActivity(intent);
            }
        }
    }

    private JsonObject getJsonObject(String urlString, String token) {
        Log.i(TAG, String.format("getJsonObject %s token %s.", urlString, token));
        URL url = Utils.getUrl(urlString);
        HttpsURLConnection urlConnection;
        try {
            urlConnection = (HttpsURLConnection) url.openConnection();
            String authorization = String.format("Bearer %s", token);
            urlConnection.setRequestProperty("Authorization", authorization);
            InputStream is = urlConnection.getInputStream();
            InputStreamReader isr = new InputStreamReader(is);
            Gson gson = new Gson();
            return gson.fromJson(isr, JsonObject.class);
        } catch (IOException e) {
            e.printStackTrace();
            Log.w(TAG, String.format("Error in getJsonObject URL %s ERROR %s.", urlString, e.getLocalizedMessage()));
        }
        return null;
    }


    private void askForInstance() {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(R.string.enter_an_instance_name);
        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        builder.setView(input);
        builder.setPositiveButton(Literals.OK.name(), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                instance = input.getText().toString();
                String message = String.format(getString(R.string.instance_string_format), instance);
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
                WorkerCreateApp workerCreateApp = new WorkerCreateApp(mainActivity);
                workerCreateApp.execute(instance);
            }
        });
        builder.setNegativeButton(Literals.Cancel.name(), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dialog.cancel();
            }
        });
        builder.show();
    }

    private void multipleChoiceAccount() {
        AlertDialog.Builder alertDialog = new AlertDialog.Builder(this);
        alertDialog.setTitle(R.string.select_account);
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        JsonObject settings = Utils.getSettings(context);
        JsonElement accounts = settings.get(Literals.accounts.name());
        JsonArray jsonArray = accounts.getAsJsonArray();
        int index = 0;
        for (JsonElement jsonElement : jsonArray) {
            String me = Utils.getProperty(jsonElement, Literals.me.name());
            String checkMark = "";
            if (index == Utils.getInt(Utils.getProperty(settings, Literals.accountIndexActive.name()))) {
                checkMark = CHECKMARK;
            }
            adapter.add(String.format(Locale.US, "%s %d %s", checkMark, index++, me));
        }
        alertDialog.setAdapter(adapter, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                JsonObject settings = Utils.getSettings(context);
                settings.addProperty(Literals.accountIndexSelected.name(), which);
                Utils.writeSettings(context, settings);
                String selectedItem = adapter.getItem(which);
                Log.i(TAG, "Selected instance: " + selectedItem);
                Intent intent = new Intent(context, AccountActivity.class);
                startActivityForResult(intent, REQUEST_ACCOUNT);
            }
        });
        alertDialog.show();

    }

    private void multipleChoiceStatuses() {
        AlertDialog.Builder alertDialog = new AlertDialog.Builder(this);
        alertDialog.setTitle(R.string.select_status);
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1);
        JsonObject settings = Utils.getSettings(context);
        JsonElement statuses = settings.get(Literals.statuses.name());
        JsonArray jsonArray = statuses.getAsJsonArray();
        int index = 0;
        for (JsonElement jsonElement : jsonArray) {
            String label = Utils.getProperty(jsonElement, Literals.label.name());
            String checkmark = "";
            if (index == Utils.getInt(Utils.getProperty(settings, Literals.statusIndexActive.name()))) {
                checkmark = CHECKMARK;
            }
            adapter.add(String.format(Locale.US, "%s %d %s", checkmark, index++, label));
        }
        alertDialog.setAdapter(adapter, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                JsonObject settings = Utils.getSettings(context);
                settings.addProperty(Literals.statusIndexSelected.name(), which);
                Utils.writeSettings(context, settings);
                String selectedItem = adapter.getItem(which);
                Log.i(TAG, "Selected status: " + selectedItem);
                Intent intent = new Intent(context, StatusConfigActivity.class);
                startActivityForResult(intent, REQUEST_STATUS);
            }
        });
        alertDialog.show();

    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        JsonObject settings = Utils.getSettings(context);
        JsonElement accounts = settings.get(Literals.accounts.name());
        JsonElement statuses = settings.get(Literals.statuses.name());
        Intent intent;
        switch (item.getItemId()) {
            case R.id.accounts:
                if (accounts == null || accounts.getAsJsonArray().size() == 0) {
                    askForInstance();
                } else {
                    if (accounts.getAsJsonArray().size() > 1) {
                        multipleChoiceAccount();
                    } else {
                        intent = new Intent(context, AccountActivity.class);
                        startActivityForResult(intent, REQUEST_ACCOUNT);
                    }
                }
                Log.i(TAG, "Accounts activity");
                return true;
            case R.id.settings:
                Log.i(TAG, "Settings menu option.");
                intent = new Intent(context, SettingsActivity.class);
                startActivity(intent);
                return true;
            case R.id.status_config:
                if (statuses != null && !statuses.isJsonNull() && statuses.getAsJsonArray().size() > 1) {
                    multipleChoiceStatuses();
                } else {
                    intent = new Intent(context, StatusConfigActivity.class);
                    startActivityForResult(intent, REQUEST_STATUS);
                }
                Log.i(TAG, "Statuses activity");
                return true;
            case R.id.about:
                intent = new Intent(Intent.ACTION_VIEW);
                intent.setData(Uri.parse("https://fediphoto.com"));
                startActivity(intent);
                return true;
            default:
                Log.i(TAG, "Default menu option.");
                return super.onContextItemSelected(item);
        }
    }

    private void workerStatus(String tag) {
        ListenableFuture<List<WorkInfo>> workInfoList = WorkManager.getInstance(context).getWorkInfosByTag(tag);
        int quantityNotFinishedDayOrOlder = 0;
        try {
            List<WorkInfo> workInfos = workInfoList.get(5, TimeUnit.SECONDS);
            int quantityNotFinished = 0;
            boolean isDebuggable = (0 != (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE));
            for (WorkInfo workInfo : workInfos) {
                if (workInfo != null && !workInfo.getState().isFinished()) {
                    long createdMilliseconds = 0;
                    Set<String> tags = workInfo.getTags();
                    for (String workerTag:tags) {
                        Log.i(TAG, String.format("Worker tag: %s", workerTag));
                        if (workerTag.startsWith(Literals.created_milliseconds.name())) {
                           createdMilliseconds = Utils.getLong(workerTag.substring(Literals.created_milliseconds.name().length()));
                        }
                    }
                    Log.i(TAG, String.format("Worker created %s info %s. Worker Info Tag %s.",  new Date(createdMilliseconds), workInfo.toString(), tag));
                    quantityNotFinished++;
                    if (createdMilliseconds > 0
                            && (System.currentTimeMillis() - createdMilliseconds > Utils.DAY_ONE
                            || (isDebuggable && System.currentTimeMillis() - createdMilliseconds > Utils.MINUTES_ONE))) {
                        quantityNotFinishedDayOrOlder++;
                    }
                }
            }
            Log.i(TAG, String.format("%d worker info quantity. Worker Info Tag %s. %d not finished.", workInfos.size(), tag, quantityNotFinished));
        } catch (Exception e) {
            Log.e(TAG, e.getLocalizedMessage());
        }
        if (quantityNotFinishedDayOrOlder > 0) {
            promptUserToDeleteWorkers(quantityNotFinishedDayOrOlder);
        }
    }

    private void promptUserToDeleteWorkers(final int quantity) {
        DialogInterface.OnClickListener dialogClickListener = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                switch (which) {
                    case DialogInterface.BUTTON_POSITIVE:
                        WorkManager.getInstance(context).cancelAllWork();
                        String message = String.format(Locale.getDefault(), getString(R.string.workers_cancelled), quantity);
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
                        break;

                    case DialogInterface.BUTTON_NEGATIVE:
                        break;
                }
            }
        };
        String message = String.format(Locale.getDefault(), getString(R.string.older_workers_prompt), quantity);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setMessage(message).setPositiveButton(R.string.yes, dialogClickListener)
                .setNegativeButton(R.string.no, dialogClickListener).show();

    }

    private void setTitle() {
        getSupportActionBar().setDisplayShowHomeEnabled(true);
        getSupportActionBar().setIcon(R.drawable.fediphoto_foreground);
        String version = "";
        try {
            PackageInfo pInfo = context.getPackageManager().getPackageInfo(getPackageName(), 0);
            version = pInfo.versionName;

        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
        }
        setTitle(String.format("%s %s", Utils.getApplicationName(context), version));
    }

}
